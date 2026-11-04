package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.algebra.ConsulAgent
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.algebra.{EcsAgentIntrospection, TaskDefinitions}
import com.dwolla.nomenclator.ecs.model.{EcsTask, TaskDefinitionRef}
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
    * that takes longer than `lookupTimeout`, or is unfinished at the lookup
    * phase's deadline, fails, leaving its tasks awaiting their definition, so a
    * hung ECS API can't hold up the rest of the host. The stream fails with
    * [[ReconcileFailedTooLong]] once `policy`'s limit passes without a
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
      .eval(
        (
          ChangeLog[F],
          Ref.of[F, List[TaskDefinitionRef]](Nil),
          Temporal[F].monotonic
        ).tupled
      )
      .flatMap: (changeLog, failedLookups, start) =>
        val pass = Pass(
          mode,
          lookupTimeout,
          host,
          globalTags,
          intro,
          defs,
          consul,
          changeLog,
          failedLookups
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
                                                                      changeLog: ChangeLog[F],
                                                                      failedLookups: Ref[F, List[TaskDefinitionRef]]
  ):

    /** Fails, having written nothing, when the ECS agent or the Consul agent
      * can't be read: a failed read means "unknown", never "empty".
      */
    def run: F[PassOutcome] =
      for
        tasks <- read(Dependency.EcsAgent, intro.tasks)
        _ <- defs.retainOnly(tasks.map(_.definition).toSet)
        running = tasks.filter(_.isRunning)
        settings <- definitions(running)
        desired = Registrations.desired(host, tasks, settings, globalTags)
        _ <- changeLog.skipped(desired.skipped)
        _ <- changeLog.customIdsRequested(desired.customIdRequested)
        _ <- changeLog.unowned(
          desired.registrations
            .map(_.id)
            .filterNot(ServiceIds.owns(host.name))
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
      * A failed lookup leaves its tasks awaiting their definition, and is
      * looked up after the others next pass, taking turns with other failed
      * lookups.
      */
    private def definitions(running: List[EcsTask]) =
      for
        failedBefore <- failedLookups.get
        results <- DefinitionLookups.within(
          running.map(_.definition).distinct,
          lookupTimeout,
          failedBefore
        )(defs.settings)
        _ <- failedLookups.set(DefinitionLookups.failed(failedBefore, results))
        _ <- changeLog.definitionLookups(results)
      yield results
        .collect:
          case (ref, Right(settings)) =>
            ref -> settings
        .toMap

    /** Whether a service ID belongs to one of `tasks`' containers. A task
      * awaiting its definition produces no registrations, so without this the
      * planner would deregister what it registered earlier, and shadow mode
      * would report it as extra.
      */
    private def ownedByAnyOf(tasks: List[EcsTask]): ServiceId => Boolean =
      val belongs = tasks
        .flatMap(_.containers)
        .map(c => ServiceIds.belongsTo(host.name, c.dockerName))
      id => belongs.exists(_(id))

    /** Deregisters, then registers, as the [[com.dwolla.nomenclator.plan.Plan]]
      * contract requires. Every write is attempted whatever happened to the
      * others, and the next pass's plan retries the failures. Each successful
      * write is logged as soon as its kind is known; a replacement's
      * deregistration is reported with its registration, since only then is it
      * known whether both halves succeeded.
      */
    private def applyPlan(plan: Plan, actual: AgentState): F[List[Write]] =
      val onAgent = actual.services.map(s => s.id -> s).toMap
      val registering = plan.register.map(r => r.id -> r).toMap

      def reported(w: Write): F[Write] =
        val service = w.kind match
          case ChangeKind.Deregister =>
            onAgent.get(w.id).fold(Map.empty)(LogContext.of)
          case _ => registering.get(w.id).fold(Map.empty)(LogContext.of)
        StructuredLogger[F]
          .info(
            service ++ Map("kind" -> w.kind.value, "service_id" -> w.id.value)
          )(s"Consul agent change: ${w.kind.value} ${w.id.value}")
          .whenA(w.result.isRight)
          .as(w)

      // Right: logged and done. Left: deregistered so that it can be
      // registered again, which decides how it is reported.
      val deregistrations: F[List[Either[ServiceId, Write]]] =
        plan.deregister.traverse: id =>
          consul
            .deregister(id)
            .attempt
            .flatMap:
              case Right(_) if registering.contains(id) =>
                id.asLeft[Write].pure[F]
              case result =>
                reported(Write(id, ChangeKind.Deregister, result)).map(_.asRight)

      def registrations(removed: Set[ServiceId]): F[List[Write]] =
        plan.register.flatTraverse: r =>
          consul
            .register(r)
            .attempt
            .flatMap: result =>
              val writes = (removed(r.id), result) match
                case (true, Right(_)) =>
                  List(Write(r.id, ChangeKind.Replace, result))
                case (true, Left(_)) =>
                  List(
                    Write(r.id, ChangeKind.Deregister, ().asRight),
                    Write(r.id, ChangeKind.Register, result)
                  )
                case (false, _) =>
                  val kind =
                    if (onAgent.contains(r.id)) ChangeKind.Update
                    else ChangeKind.Register
                  List(Write(r.id, kind, result))
              writes.traverse(reported)

      for
        (removed, deregistered) <- deregistrations.map(_.separate)
        registered <- registrations(removed.toSet)
        writes = deregistered ++ registered
        _ <- changeLog.writes(writes)
      yield writes
    end applyPlan
  end Pass
end Reconciler
