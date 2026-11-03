package com.dwolla.nomenclator.reconcile

import cats.Monad
import cats.effect.Ref
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.plan.Difference
import com.dwolla.nomenclator.registration.*
import com.dwolla.nomenclator.settings.SettingsError
import org.typelevel.log4cats.StructuredLogger

/** Logs what changed between passes rather than everything on every pass. It
  * remembers what it has logged that still holds: a skip, a difference, or a
  * failure is logged when it appears, and forgotten (and, for differences and
  * failures, logged again) when it goes away.
  */
final class ChangeLog[F[_]: Monad: StructuredLogger] private (logged: Ref[F, Set[ChangeLog.Entry]]):
  import ChangeLog.*

  private val logger = StructuredLogger[F]

  def skipped(skips: List[Skipped]): F[Unit] =
    present(skips)({ case Entry.Skip(s) => s }, Entry.Skip(_))
      .flatMap((appeared, _) => appeared.traverse_(logSkip))

  def customIdsRequested(requests: List[(String, ContainerName)]): F[Unit] =
    present(requests)(
      { case Entry.CustomId(task, container) => (task, container) },
      Entry.CustomId(_, _)
    ).flatMap((appeared, _) =>
      appeared.traverse_ : (task, container) =>
        logger.warn(Map("task" -> task, "container" -> container.value))(
          "SERVICE_ID is not honored; registering under the host-shaped ID instead"
        )
    )

  def unowned(ids: List[ServiceId]): F[Unit] =
    present(ids)({ case Entry.Unowned(id) => id }, Entry.Unowned(_))
      .flatMap((appeared, _) =>
        appeared.traverse_(id =>
          logger.warn(Map("service_id" -> id.value))(
            "Not registering a service whose ID this host would not recognize as its own later"
          )
        )
      )

  def differences(found: List[Difference]): F[Unit] =
    present(found)({ case Entry.Shadow(d) => d }, Entry.Shadow(_))
      .flatMap: (appeared, cleared) =>
        appeared.traverse_(d => logger.info(differenceContext(d))("Shadow difference appeared")) >> cleared.traverse_(
          d => logger.info(differenceContext(d))("Shadow difference cleared")
        )

  /** Records whether reading `dependency` succeeded this pass.
    */
  def dependency(dependency: Dependency, result: Either[Throwable, ?]): F[Unit] =
    failures(
      List(dependency -> result.void),
      { case Entry.DependencyFailing(d) => d },
      Entry.DependencyFailing(_),
      forgetUnattempted = false
    ).flatMap: (started, recovered) =>
      started.traverse_ : (d, e) =>
        logger.warn(Map("dependency" -> d.label), e)(
          s"Reading the ${d.label} failed; nothing is written until it succeeds"
        )
      >> recovered.traverse_(d =>
        logger.info(Map("dependency" -> d.label))(
          s"Reading the ${d.label} recovered"
        )
      )

  /** Records whether a pass finished within its timeout. A pass that failed for
    * another reason says nothing about timing, so it changes nothing.
    */
  def passFinished(result: Either[Throwable, ?]): F[Unit] =
    val timing = result match
      case Left(timedOut: PassTimedOut) => List(() -> timedOut.asLeft)
      case Left(_)                      => Nil
      case Right(_)                     => List(() -> ().asRight)
    failures(
      timing,
      { case Entry.PassTimingOut => () },
      _ => Entry.PassTimingOut,
      forgetUnattempted = false
    ).flatMap: (started, recovered) =>
      started.traverse_ : (_, e) =>
        logger.warn(
          s"${e.getMessage}; writes it had not made are retried on the next pass"
        )
      >> recovered.traverse_(_ => logger.info("Reconcile passes finish in time again"))
  end passFinished

  /** Records the task definition lookups made this pass. Only the error's type
    * is logged: a failure while describing a task definition could carry part
    * of its environment, which may hold secrets.
    */
  def definitionLookups(results: List[(TaskDefinitionRef, Either[Throwable, ?])]): F[Unit] =
    failures(
      results.map((ref, result) => ref -> result.void),
      { case Entry.DefinitionFailing(ref) => ref },
      Entry.DefinitionFailing(_),
      forgetUnattempted = true
    ).flatMap: (started, recovered) =>
      started.traverse_ : (ref, e) =>
        logger.warn(
          Map(
            "task_definition" -> render(ref),
            "error_type" -> e.getClass.getName
          )
        )(
          "Task definition lookup failed; its tasks wait, and their existing registrations are kept"
        )
      >> recovered.traverse_(ref =>
        logger.info(Map("task_definition" -> render(ref)))(
          "Task definition lookup succeeded after failing"
        )
      )

  /** Records the writes made to the Consul agent this pass. An ID written twice
    * (a replacement) is failing if either write failed.
    */
  def writes(results: List[(ServiceId, Either[Throwable, Unit])]): F[Unit] =
    val byId = results
      .groupMapReduce(_._1)(_._2)((a, b) => a.flatMap(_ => b))
      .toList
      .sortBy(_._1.value)
    failures(
      byId,
      { case Entry.WriteFailing(id) => id },
      Entry.WriteFailing(_),
      forgetUnattempted = true
    ).flatMap: (started, recovered) =>
      started.traverse_ : (id, e) =>
        logger.warn(Map("service_id" -> id.value), e)(
          "Consul agent write failed; retrying on the next pass"
        )
      >> recovered.traverse_(id =>
        logger.info(Map("service_id" -> id.value))(
          "Consul agent write succeeded after failing"
        )
      )
  end writes

  /** Makes `current` the remembered set of entries of one kind, and returns the
    * entries that appeared (in `current`'s order) and those that cleared.
    */
  private def present[K](current: List[K])
                        (select: PartialFunction[Entry, K], entry: K => Entry): F[(List[K], List[K])] =
    logged.modify: all =>
      val previous = all.collect(select)
      val now = current.toSet
      val appeared = current.distinct.filterNot(previous)
      val cleared = previous.filterNot(now).toList
      (
        all -- cleared.map(entry) ++ appeared.map(entry),
        (appeared, cleared)
      )

  /** Remembers which of `results` failed, and returns the failures that started
    * and the keys that succeeded after failing. A remembered failure that
    * wasn't attempted is forgotten when `forgetUnattempted`, and kept
    * otherwise.
    */
  private def failures[K](
                           results: List[(K, Either[Throwable, Unit])],
                           select: PartialFunction[Entry, K],
                           entry: K => Entry,
                           forgetUnattempted: Boolean
  ): F[(List[(K, Throwable)], List[K])] =
    logged.modify: all =>
      val previous = all.collect(select)
      val failed = results.collect:
        case (k, Left(e)) => k -> e
      val succeeded = results.collect:
        case (k, Right(_)) => k
      val attempted = results.map(_._1).toSet
      val started = failed.filterNot((k, _) => previous(k))
      val recovered = succeeded.filter(previous)
      val forgotten =
        if (forgetUnattempted) previous.filterNot(attempted) else Set.empty
      (
        all -- (recovered ++ forgotten).map(entry) ++ failed.map((k, _) => entry(k)),
        (started, recovered)
      )

  private def logSkip(s: Skipped): F[Unit] =
    val context = Map(
      "task" -> s.task,
      "container" -> s.container.value,
      "port" -> s.port.value.value.toString,
      "reason" -> reasonLabel(s.reason)
    )
    s.reason match
      case SkipReason.Ignored =>
        logger.info(context)(
          "Not registering a container port that SERVICE_IGNORE excludes"
        )
      case SkipReason.ScriptCheck =>
        logger.warn(context)(
          "Not registering a container port that asks for a script check, which Nomenclator refuses"
        )
      case SkipReason.TtlCheck =>
        logger.warn(context)(
          "Not registering a container port that asks for a TTL check, which Nomenclator refuses"
        )
      case SkipReason.NoHostPort =>
        logger.warn(context)(
          "Not registering a container port that is not published on the host"
        )
      case SkipReason.InvalidSettings(errors) =>
        logger.warn(
          context + ("errors" -> errors.map(describe).mkString_("; "))
        )(
          "Not registering a container port whose SERVICE_* settings are invalid"
        )
    end match
  end logSkip
end ChangeLog

object ChangeLog:

  /** What a pass reads before it can plan.
    */
  enum Dependency(val label: String):
    case EcsAgent extends Dependency("ECS agent")
    case Consul extends Dependency("Consul agent")

  private[reconcile] enum Entry:
    case Skip(skipped: Skipped)
    case CustomId(task: String, container: ContainerName)
    case Unowned(id: ServiceId)
    case Shadow(difference: Difference)
    case DependencyFailing(dependency: Dependency)
    case PassTimingOut
    case DefinitionFailing(ref: TaskDefinitionRef)
    case WriteFailing(id: ServiceId)

  def apply[F[_]: Monad: Ref.Make: StructuredLogger]: F[ChangeLog[F]] =
    Ref.of[F, Set[Entry]](Set.empty).map(new ChangeLog(_))

  private def render(ref: TaskDefinitionRef): String =
    s"${ref.family}:${ref.revision}"

  private def reasonLabel(reason: SkipReason): String = reason match
    case SkipReason.Ignored            => "ignored"
    case SkipReason.ScriptCheck        => "script_check"
    case SkipReason.TtlCheck           => "ttl_check"
    case SkipReason.NoHostPort         => "no_host_port"
    case SkipReason.InvalidSettings(_) => "invalid_settings"

  private def describe(error: SettingsError): String = error match
    case SettingsError.InvalidDuration(key, value) =>
      s"$key '$value' is not a valid duration"

  private def differenceContext(d: Difference): Map[String, String] =
    d match
      case Difference.Missing(r) =>
        Map("difference" -> "missing") ++ LogContext.of(r)
      case Difference.Extra(s) =>
        Map("difference" -> "extra") ++ LogContext.of(s)
      case Difference.Different(r, s, fields) =>
        Map(
          "difference" -> "different",
          "fields" -> fields.toList.mkString(","),
          "agent_service_id" -> s.id.value
        ) ++ LogContext.of(r)
end ChangeLog
