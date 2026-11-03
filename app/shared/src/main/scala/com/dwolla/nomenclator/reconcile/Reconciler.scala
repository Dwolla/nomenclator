package com.dwolla.nomenclator.reconcile

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
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.FiniteDuration

/** Reconciles the Consul agent with the tasks the ECS agent runs.
  */
object Reconciler:

  /** Runs a pass every `interval`, starting immediately, and emits a report of
    * each, failed passes included. A pass that can't read the ECS agent or the
    * Consul agent writes nothing. A pass still running after `passTimeout` is
    * abandoned and fails with [[PassTimedOut]]; the writes it made stay made,
    * and the next pass's plan retries the rest. Each task definition lookup
    * that takes longer than `lookupTimeout`, or is unfinished when the lookups
    * have taken twice that, fails, leaving its tasks awaiting their definition,
    * so a hung ECS API can't hold up the rest of the host. The stream fails
    * with [[ReconcileFailedTooLong]] once `policy`'s limit passes without a
    * successful pass; otherwise it runs until it is interrupted, and never
    * deregisters anything on the way out.
    */
  def stream[F[_]: Temporal: StructuredLogger: Tracer](
                                                        mode: Mode,
                                                        interval: FiniteDuration,
                                                        passTimeout: FiniteDuration,
                                                        lookupTimeout: FiniteDuration,
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
        val pass = Pass(
          mode,
          lookupTimeout,
          host,
          globalTags,
          intro,
          defs,
          consul,
          changeLog
        )
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

  private final class Pass[F[_]: Temporal: StructuredLogger: Tracer](
                                                                      mode: Mode,
                                                                      lookupTimeout: FiniteDuration,
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
        awaitingTaskOwns = ownedByAnyOf(
          running.filter(t => desired.awaitingDefinitions(t.definition))
        )
        planned = Planner.plan(host.name, desired.registrations, actual)
        plan = planned.copy(deregister = planned.deregister.filterNot(awaitingTaskOwns))
        _ <- countsOnSpan(desired, actual, plan)
        outcome <- mode match
          case Mode.Apply =>
            applyPlan(plan, actual).map(
              PassOutcome(desired, actual, plan, Nil, _)
            )
          case Mode.Shadow =>
            val differences = ShadowReport.compare(
              desired.registrations,
              actual.copy(services = actual.services.filterNot(s => awaitingTaskOwns(s.id)))
            )
            changeLog
              .differences(differences)
              .as(PassOutcome(desired, actual, plan, differences, Nil))
      yield outcome

    /** Puts the pass's counts on the current span, the pass's own. A replaced
      * service, deregistered and registered again, is one planned change.
      */
    private def countsOnSpan(desired: Desired, actual: AgentState, plan: Plan): F[Unit] =
      Tracer[F].withCurrentSpanOrNoop(
        _.addAttributes(
          Attribute(
            "nomenclator.services.desired",
            desired.registrations.size.toLong
          ),
          Attribute(
            "nomenclator.services.registered",
            actual.services.size.toLong
          ),
          Attribute(
            "nomenclator.changes.planned",
            (plan.register.map(_.id) ++ plan.deregister).distinct.size.toLong
          ),
          Attribute("nomenclator.services.skipped", desired.skipped.size.toLong)
        )
      )

    private def read[A](dependency: Dependency, fa: F[A]): F[A] =
      fa.attempt.flatTap(changeLog.dependency(dependency, _)).rethrow

    /** The settings of every running task's definition that could be looked up.
      * A failed lookup leaves its tasks awaiting their definition.
      */
    private def definitions(running: List[EcsTask]) =
      DefinitionLookups
        .within(running.map(_.definition).distinct, lookupTimeout)(
          defs.settings
        )
        .flatTap(changeLog.definitionLookups)
        .map(_.collect:
          case (ref, Right(settings)) => ref -> settings
        .toMap)

    /** Whether a service ID belongs to one of `tasks`' containers. A task
      * awaiting its definition produces no registrations, so without this the
      * planner would deregister what it registered earlier, and shadow mode
      * would report it as extra. Its services' IDs start with this host's name
      * and one of its containers' Docker names, which can't contain the `:`
      * that separates them.
      */
    private def ownedByAnyOf(tasks: List[EcsTask]): ServiceId => Boolean =
      val prefixes = tasks
        .flatMap(_.containers)
        .map(c => s"${host.name.value}:${c.dockerName.value}:")
      id => prefixes.exists(id.value.startsWith)

    /** Deregisters, then registers, as the [[com.dwolla.nomenclator.plan.Plan]]
      * contract requires. Every write is attempted whatever happened to the
      * others; the failures are returned, and the next pass's plan retries
      * them.
      */
    private def applyPlan(plan: Plan, actual: AgentState): F[List[(ServiceId, Throwable)]] =
      val onAgent = actual.services.map(s => s.id -> s).toMap
      val replaced =
        plan.register.map(_.id).toSet.intersect(plan.deregister.toSet)

      def deregisteredLog(id: ServiceId): F[Unit] =
        changed(
          "deregister",
          id,
          onAgent.get(id).fold(Map.empty)(LogContext.of)
        )

      // A replacement is logged once both halves are known: as `replace` if
      // both succeeded, as `deregister` if only the deregistration did.
      val deregistrations = plan.deregister.traverse: id =>
        consul
          .deregister(id)
          .attempt
          .flatTap(_.traverse_(_ => deregisteredLog(id).unlessA(replaced(id))))
          .tupleLeft(id)

      def registrations(deregistered: Set[ServiceId]) =
        plan.register.traverse: r =>
          val removed = replaced(r.id) && deregistered(r.id)
          val kind =
            if (removed) "replace"
            else if (onAgent.contains(r.id)) "update"
            else "register"
          consul
            .register(r)
            .attempt
            .flatTap:
              case Right(_) => changed(kind, r.id, LogContext.of(r))
              case Left(_)  => deregisteredLog(r.id).whenA(removed)
            .tupleLeft(r.id)

      for
        deregistered <- deregistrations
        registered <- registrations(
          deregistered
            .collect:
              case (id, Right(_)) => id
            .toSet
        )
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
