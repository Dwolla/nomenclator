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
import smithy4s.http.UnknownErrorResponse

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

  def customIdsRequested(requests: List[CustomIdRequest]): F[Unit] =
    present(requests)(
      { case Entry.CustomId(request) => request },
      Entry.CustomId(_)
    ).flatMap((appeared, _) =>
      appeared.traverse_ : request =>
        logger.warn(
          Map(
            "task" -> request.task.value,
            "container" -> request.container.value
          )
        )(
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
    ).flatMap: (started, recovered, _) =>
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
    ).flatMap: (started, recovered, _) =>
      started.traverse_ : (_, e) =>
        logger.warn(
          s"${e.getMessage}; writes it had not made are retried on the next pass"
        )
      >> recovered.traverse_(_ => logger.info("Reconcile passes finish in time again"))
  end passFinished

  /** Records the task definition lookups made this pass. Only the error's type
    * is logged, plus the HTTP status of an answer the client couldn't decode as
    * a modeled error: a failure while describing a task definition could carry
    * part of its environment, which may hold secrets, and an unrecognized
    * answer's message embeds its headers and body.
    *
    * A lookup that got no full turn says nothing about its definition, so it
    * neither starts nor clears a failure. It is logged on its own, at INFO,
    * unless its definition is already remembered as failing.
    */
  def definitionLookups(results: List[(TaskDefinitionRef, Either[Throwable, ?])]): F[Unit] =
    val noFullTurn = results.collect:
      case (ref, Left(e: DefinitionLookups.NoFullTurn)) => ref -> e
    val fullTurns = results.filterNot((_, result) => result.left.exists(_.isInstanceOf[DefinitionLookups.NoFullTurn]))
    failures(
      fullTurns.map((ref, result) => ref -> result.void),
      { case Entry.DefinitionFailing(ref) => ref },
      Entry.DefinitionFailing(_),
      forgetUnattempted = true,
      inconclusive = noFullTurn.map(_._1)
    ).flatMap: (started, recovered, notFailing) =>
      noFullTurn
        .filter((ref, _) => notFailing.contains(ref))
        .traverse_(logNoFullTurn) >> started.traverse_ : (ref, e) =>
        logger.warn(
          Map(
            "task_definition" -> ref.value,
            "error_type" -> e.getClass.getName
          ) ++ httpStatus(e)
        )(
          "Task definition lookup failed; its tasks wait, and their existing registrations are kept"
        )
      >> recovered.traverse_(ref =>
        logger.info(Map("task_definition" -> ref.value))(
          "Task definition lookup succeeded after failing"
        )
      )
  end definitionLookups

  private def logNoFullTurn(ref: TaskDefinitionRef, noFullTurn: DefinitionLookups.NoFullTurn): F[Unit] =
    val context = Map("task_definition" -> ref.value)
    noFullTurn match
      case DefinitionLookups.NotStarted(_) =>
        logger.info(context)(
          "Task definition lookup waited behind other lookups until the lookup phase's deadline; its tasks wait, and their existing registrations are kept"
        )
      case DefinitionLookups.CutOff(_) =>
        logger.info(context)(
          "Task definition lookup was cut off at the lookup phase's deadline, before its own timeout; its tasks wait, and their existing registrations are kept"
        )

  /** Records the writes made to the Consul agent this pass. An ID written twice
    * (a replacement that did not fully succeed) is failing if either write
    * failed.
    */
  def writes(made: List[Write]): F[Unit] =
    val byId = made
      .groupMapReduce(_.id)(_.result)((a, b) => a.flatMap(_ => b))
      .toList
      .sortBy(_._1.value)
    failures(
      byId,
      { case Entry.WriteFailing(id) => id },
      Entry.WriteFailing(_),
      forgetUnattempted = true
    ).flatMap: (started, recovered, _) =>
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

  /** Remembers which of `results` failed, and returns the failures that
    * started, the keys that succeeded after failing, and the `inconclusive`
    * keys not remembered as failing. An inconclusive key was attempted, but its
    * result says nothing either way, so whether it is remembered as failing
    * doesn't change. A remembered failure that wasn't attempted is forgotten
    * when `forgetUnattempted`, and kept otherwise.
    */
  private def failures[K](
                           results: List[(K, Either[Throwable, Unit])],
                           select: PartialFunction[Entry, K],
                           entry: K => Entry,
                           forgetUnattempted: Boolean,
                           inconclusive: List[K] = Nil
  ): F[(List[(K, Throwable)], List[K], List[K])] =
    logged.modify: all =>
      val previous = all.collect(select)
      val failed = results.collect:
        case (k, Left(e)) => k -> e
      val succeeded = results.collect:
        case (k, Right(_)) => k
      val attempted = results.map(_._1).toSet ++ inconclusive
      val started = failed.filterNot((k, _) => previous(k))
      val recovered = succeeded.filter(previous)
      val forgotten =
        if (forgetUnattempted) previous.filterNot(attempted) else Set.empty
      (
        all -- (recovered ++ forgotten).map(entry) ++ failed.map((k, _) => entry(k)),
        (started, recovered, inconclusive.filterNot(previous))
      )

  private def logSkip(s: Skipped): F[Unit] =
    val context = Map(
      "task" -> s.task.value,
      "container" -> s.container.value,
      "port" -> s.port.value.value.toString,
      "reason" -> SkipReasonLabel.of(s.reason).value
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

  private def httpStatus(e: Throwable): Map[String, String] =
    e match
      case UnknownErrorResponse(status, _, _) =>
        Map("status" -> status.toString)
      case _ => Map.empty

  /** What a pass reads before it can plan.
    */
  enum Dependency(val label: String):
    case EcsAgent extends Dependency("ECS agent")
    case Consul extends Dependency("Consul agent")

  private[reconcile] enum Entry:
    case Skip(skipped: Skipped)
    case CustomId(request: CustomIdRequest)
    case Unowned(id: ServiceId)
    case Shadow(difference: Difference)
    case DependencyFailing(dependency: Dependency)
    case PassTimingOut
    case DefinitionFailing(ref: TaskDefinitionRef)
    case WriteFailing(id: ServiceId)

  def apply[F[_]: Monad: Ref.Make: StructuredLogger]: F[ChangeLog[F]] =
    Ref.of[F, Set[Entry]](Set.empty).map(new ChangeLog(_))

  private def describe(error: SettingsError): String = error match
    case SettingsError.InvalidDuration(key, value) =>
      s"$key '$value' is not a valid duration"

  private def differenceContext(d: Difference): Map[String, String] =
    Map("difference" -> DifferenceLabel.of(d).value) ++ (d match
      case Difference.Missing(r)              => LogContext.of(r)
      case Difference.Extra(s)                => LogContext.of(s)
      case Difference.Different(r, s, fields) =>
        Map(
          "fields" -> fields.toList.mkString(","),
          "agent_service_id" -> s.id.value
        ) ++ LogContext.of(r))
end ChangeLog
