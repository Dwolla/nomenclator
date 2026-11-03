package com.dwolla.nomenclator.telemetry

import cats.Monad
import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.Ownership
import com.dwolla.nomenclator.ecs.api.LookupResult
import com.dwolla.nomenclator.model.HostName
import com.dwolla.nomenclator.reconcile.*
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.metrics.*

/** Nomenclator's own metrics: how each reconcile pass went, and what the latest
  * successful one found. Every attribute takes one of a small, fixed set of
  * values; service names and IDs never become attributes.
  */
trait NomenclatorMetrics[F[_]]:

  /** Records how long the pass took and whether it succeeded. A successful pass
    * also counts the changes it wrote in apply mode, and becomes what the
    * gauges report. After a failed pass the gauges report nothing, since what
    * they measure is unknown.
    */
  def record(mode: Mode, report: PassReport): F[Unit]

  /** Counts one task definition lookup.
    */
  def taskDefinitionLookup(result: LookupResult): F[Unit]
end NomenclatorMetrics

object NomenclatorMetrics:

  /** The gauges report the latest pass if it succeeded, and nothing otherwise.
    * Their callback is unregistered when the resource is released.
    *
    * @param host
    *   this host's name, which tells the services it owns from the others
    */
  def apply[F[_]: Monad: Ref.Make: Meter](host: HostName): Resource[F, NomenclatorMetrics[F]] =
    for
      latest <- Ref.of[F, Option[(Mode, PassOutcome)]](None).toResource
      duration <- Meter[F]
        .histogram[Double]("nomenclator.reconcile.duration")
        .withUnit("s")
        .withDescription("How long a reconcile pass takes.")
        .withExplicitBucketBoundaries(durationBuckets)
        .create
        .toResource
      changes <- Meter[F]
        // Counted from a successful pass's outcome, so a pass that fails or
        // times out partway through applying its plan records no changes.
        .counter[Long]("nomenclator.changes")
        .withUnit("{change}")
        .withDescription("Changes written to the Consul agent in apply mode.")
        .create
        .toResource
      lookups <- Meter[F]
        .counter[Long]("nomenclator.task_definition.lookups")
        .withUnit("{lookup}")
        .withDescription("Task definition lookups, by how they were answered.")
        .create
        .toResource
      // `desired` is what the pass's tasks asked for, and `registered` the
      // agent's state as the pass read it, before the pass's writes.
      _ <- Meter[F].batchCallback.of(
        serviceGauge(
          "nomenclator.services.desired",
          "Services this host's tasks ask to advertise, as of the latest pass."
        ),
        serviceGauge(
          "nomenclator.services.registered",
          "Services the Consul agent held before the latest pass's writes, by whether Nomenclator owns them."
        ),
        serviceGauge(
          "nomenclator.services.skipped",
          "Container ports deliberately not registered, by reason."
        ),
        serviceGauge(
          "nomenclator.shadow.differences",
          "In shadow mode, how the agent's registrations differ from Nomenclator's."
        )
      ): (desired, registered, skipped, differences) =>
        latest.get.flatMap(_.traverse_ : (mode, outcome) =>
          val (owned, other) =
            outcome.actual.services.partition(s => Ownership.owns(host, s.id))
          desired.record(outcome.desired.registrations.size.toLong) >>
            registered.record(owned.size.toLong, ownership("owned")) >>
            registered.record(other.size.toLong, ownership("other")) >>
            // Every label is reported, as zero when nothing has it: zero
            // differences is the bar for leaving shadow mode.
            SkipReasonLabel.values.toList.traverse_ : label =>
              skipped.record(
                outcome.desired.skipped
                  .count(s => SkipReasonLabel.of(s.reason) == label)
                  .toLong,
                Attribute("nomenclator.skip.reason", label.value)
              )
            >>
            DifferenceLabel.values.toList
              .traverse_ : label =>
                differences.record(
                  outcome.differences
                    .count(DifferenceLabel.of(_) == label)
                    .toLong,
                  Attribute("nomenclator.difference.kind", label.value)
                )
              .whenA(mode == Mode.Shadow))
    yield new NomenclatorMetrics[F]:
      override def record(mode: Mode, report: PassReport): F[Unit] =
        duration.record(
          report.duration.toNanos.toDouble / 1e9,
          Attribute("nomenclator.mode", modeValue(mode)),
          Attribute(
            "nomenclator.reconcile.outcome",
            if (report.outcome.isRight) "ok" else "error"
          )
        ) >> latest.set(report.outcome.toOption.tupleLeft(mode)) >>
          report.outcome.traverse_ : outcome =>
            written(outcome).toList
              .traverse_ :
                case ((kind, result), n) =>
                  changes.add(
                    n,
                    Attribute("nomenclator.change.kind", kind),
                    Attribute("nomenclator.change.outcome", result)
                  )
              .whenA(mode == Mode.Apply)

      override def taskDefinitionLookup(result: LookupResult): F[Unit] =
        lookups.inc(Attribute("nomenclator.lookup.result", lookupValue(result)))

  /** Up to the pass timeout's default of 30 s, finest below a second, where
    * most passes finish.
    */
  private val durationBuckets = BucketBoundaries(
    0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10, 30
  )

  private def serviceGauge[F[_]: Meter](name: String, description: String) =
    Meter[F]
      .observableGauge[Long](name)
      .withUnit("{service}")
      .withDescription(description)
      .createObserver

  private def ownership(value: String) =
    Attribute("nomenclator.ownership", value)

  private def modeValue(mode: Mode): String = mode match
    case Mode.Apply  => "apply"
    case Mode.Shadow => "shadow"

  private def lookupValue(result: LookupResult): String = result match
    case LookupResult.Cached  => "cached"
    case LookupResult.Fetched => "fetched"
    case LookupResult.Failed  => "failed"

  /** How many changes of each kind the pass wrote, and whether they succeeded.
    * An ID both deregistered and registered is one `replace`; a registration of
    * an ID the agent already held is an `update`.
    */
  private def written(outcome: PassOutcome): Map[(String, String), Long] =
    val plan = outcome.plan
    val registering = plan.register.map(_.id)
    val deregistering = plan.deregister.toSet
    val onAgent = outcome.actual.services.map(_.id).toSet
    val failed = outcome.failures.map(_._1).toSet
    val kinds =
      registering.map: id =>
        id -> (
          if (deregistering(id)) "replace"
          else if (onAgent(id)) "update"
          else "register"
        )
      ++ plan.deregister.filterNot(registering.toSet).map(_ -> "deregister")
    kinds
      .groupMapReduce((id, kind) => kind -> (if (failed(id)) "error" else "ok"))(_ => 1L)(_ + _)
  end written
end NomenclatorMetrics
