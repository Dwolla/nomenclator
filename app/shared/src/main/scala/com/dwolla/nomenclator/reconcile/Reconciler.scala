package com.dwolla.nomenclator.reconcile

import cats.MonadThrow
import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.consul.agent.ConsulAgent
import com.dwolla.nomenclator.ecs.EcsTask
import com.dwolla.nomenclator.ecs.agent.EcsAgentIntrospection
import com.dwolla.nomenclator.ecs.api.TaskDefinitions
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.plan.*
import com.dwolla.nomenclator.reconcile.ChangeLog.Dependency
import com.dwolla.nomenclator.registration.*
import fs2.Stream
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.FiniteDuration

/** Reconciles the Consul agent with the tasks the ECS agent runs.
  */
object Reconciler:

  /** Runs a pass every `interval`, starting immediately, and emits a report of
    * each, failed passes included. A pass that can't read the ECS agent or the
    * Consul agent writes nothing. A pass still running after `passTimeout` is
    * abandoned and fails with [[PassTimedOut]]; the writes it made stay made,
    * and the next pass's plan retries the rest. The stream fails with
    * [[ReconcileFailedTooLong]] once `policy`'s limit passes without a
    * successful pass; otherwise it runs until it is interrupted, and never
    * deregisters anything on the way out.
    */
  def stream[F[_]: Temporal: StructuredLogger: Tracer](
                                                        mode: Mode,
                                                        interval: FiniteDuration,
                                                        passTimeout: FiniteDuration,
                                                        host: HostIdentity,
                                                        globalTags: List[Tag],
                                                        intro: EcsAgentIntrospection[F],
                                                        defs: TaskDefinitions[F],
                                                        consul: ConsulAgent[F],
                                                        policy: FailurePolicy
  ): Stream[F, PassReport] =
    Stream
      .eval((ChangeLog[F], Temporal[F].monotonic).tupled)
      .flatMap: (changeLog, start) =>
        val pass = Pass(mode, host, globalTags, intro, defs, consul, changeLog)
        Stream
          .fixedRateStartImmediately[F](interval, dampen = true)
          .evalMapAccumulate(start): (lastSuccess, _) =>
            for
              (duration, outcome) <- Temporal[F].timed(
                Tracer[F]
                  .rootSpan("reconcile")
                  .surround(
                    pass.run
                      .timeoutTo(
                        passTimeout,
                        PassTimedOut(passTimeout).raiseError[F, PassOutcome]
                      )
                      .attempt
                      .flatTap(changeLog.passFinished)
                      .rethrow
                  )
                  .attempt
              )
              now <- Temporal[F].monotonic
              since = if (outcome.isRight) now else lastSuccess
            yield (since, (PassReport(duration, outcome), now - since))
          .flatMap:
            case (_, (report, failingFor)) =>
              Stream.emit(report) ++ (
                if (policy.exceededBy(failingFor))
                  Stream.raiseError[F](ReconcileFailedTooLong(policy.limit))
                else Stream.empty
              )

  private final class Pass[F[_]: MonadThrow: StructuredLogger](
                                                                mode: Mode,
                                                                host: HostIdentity,
                                                                globalTags: List[Tag],
                                                                intro: EcsAgentIntrospection[F],
                                                                defs: TaskDefinitions[F],
                                                                consul: ConsulAgent[F],
                                                                changeLog: ChangeLog[F]
  ):

    /** Fails, having written nothing, when the ECS agent or the Consul agent
      * can't be read: a failed read means "unknown", never "empty".
      */
    def run: F[PassOutcome] =
      for
        tasks <- read(Dependency.EcsAgent, intro.tasks)
        running = tasks.filter(_.isRunning)
        settings <- definitions(running)
        desired = Registrations.desired(host, tasks, settings, globalTags)
        _ <- changeLog.skipped(desired.skipped)
        _ <- changeLog.customIdsRequested(desired.customIdRequested)
        _ <- changeLog.unowned(
          desired.registrations
            .map(_.id)
            .filterNot(Ownership.owns(host.name, _))
        )
        actual <- read(Dependency.Consul, consul.state)
        plan = keepRegistrationsOfAwaitingTasks(
          Planner.plan(host.name, desired.registrations, actual),
          running.filter(t => desired.awaitingDefinitions(t.definition))
        )
        outcome <- mode match
          case Mode.Apply =>
            applyPlan(plan, actual).map(PassOutcome(desired, plan, Nil, _))
          case Mode.Shadow =>
            val differences =
              ShadowReport.compare(desired.registrations, actual)
            changeLog
              .differences(differences)
              .as(PassOutcome(desired, plan, differences, Nil))
      yield outcome

    private def read[A](dependency: Dependency, fa: F[A]): F[A] =
      fa.attempt.flatTap(changeLog.dependency(dependency, _)).rethrow

    /** The settings of every running task's definition that could be looked up.
      * A failed lookup leaves its tasks awaiting their definition.
      */
    private def definitions(running: List[EcsTask]) =
      running
        .map(_.definition)
        .distinct
        .traverse(ref => defs.settings(ref).attempt.tupleLeft(ref))
        .flatTap(changeLog.definitionLookups)
        .map(_.collect:
          case (ref, Right(settings)) => ref -> settings
        .toMap)

    /** A task whose definition can't be looked up produces no registrations, so
      * the planner would deregister what it registered earlier. Its services'
      * IDs start with this host's name and one of its containers' Docker names,
      * which can't contain the `:` that separates them.
      */
    private def keepRegistrationsOfAwaitingTasks(plan: Plan, awaiting: List[EcsTask]): Plan =
      val prefixes = awaiting
        .flatMap(_.containers)
        .map(c => s"${host.name.value}:${c.dockerName.value}:")
      plan.copy(deregister = plan.deregister.filterNot(id => prefixes.exists(id.value.startsWith)))

    /** Deregisters, then registers, as the [[com.dwolla.nomenclator.plan.Plan]]
      * contract requires. Every write is attempted whatever happened to the
      * others; the failures are returned, and the next pass's plan retries
      * them.
      */
    private def applyPlan(plan: Plan, actual: AgentState): F[List[(ServiceId, Throwable)]] =
      val onAgent = actual.services.map(s => s.id -> s).toMap
      val replaced =
        plan.register.map(_.id).toSet.intersect(plan.deregister.toSet)

      val deregistrations = plan.deregister.traverse: id =>
        consul
          .deregister(id)
          .attempt
          .flatTap(
            _.traverse_(_ =>
              changed(
                "deregister",
                id,
                onAgent.get(id).fold(Map.empty)(LogContext.of)
              ).unlessA(replaced(id))
            )
          )
          .tupleLeft(id)

      val registrations = plan.register.traverse: r =>
        val kind =
          if (replaced(r.id)) "replace"
          else if (onAgent.contains(r.id)) "update"
          else "register"
        consul
          .register(r)
          .attempt
          .flatTap(_.traverse_(_ => changed(kind, r.id, LogContext.of(r))))
          .tupleLeft(r.id)

      for
        deregistered <- deregistrations
        registered <- registrations
        results = deregistered ++ registered
        _ <- changeLog.writes(results)
      yield results.collect:
        case (id, Left(e)) => id -> e
    end applyPlan

    private def changed(kind: String, id: ServiceId, service: Map[String, String]): F[Unit] =
      StructuredLogger[F].info(
        service ++ Map("kind" -> kind, "service_id" -> id.value)
      )(s"Consul agent change: $kind ${id.value}")
  end Pass
end Reconciler
