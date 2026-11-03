package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.TaskDefinitionRef

import scala.concurrent.duration.FiniteDuration

/** Looks up task definitions within a bounded time, so a hanging ECS API can't
  * hold up a pass however many definitions are uncached.
  */
private[nomenclator] object DefinitionLookups:
  private val concurrentLookups = 4

  /** How long the lookup phase may take: two rounds of lookups that run to
    * their own timeout, and half a round more for lookups that answer quickly.
    * Not a multiple of `lookupTimeout`, so in rounds of hung lookups a lookup's
    * own timeout never falls at the deadline, and a lookup cut off by the
    * deadline has run for less than its own timeout.
    */
  def deadline(lookupTimeout: FiniteDuration): FiniteDuration =
    lookupTimeout * 5 / 2

  /** A lookup that didn't get a full turn, which says nothing about its task
    * definition.
    */
  sealed abstract class NoFullTurn(message: String)
      extends RuntimeException(message)

  /** A lookup that never started: the others held every slot until the
    * deadline.
    */
  final case class NotStarted(deadline: FiniteDuration)
      extends NoFullTurn(
        s"the lookup did not start within $deadline: other lookups held every slot"
      )

  /** A lookup still running at the deadline, cancelled before its own timeout.
    */
  final case class CutOff(deadline: FiniteDuration)
      extends NoFullTurn(
        s"the lookup was still running at the $deadline deadline"
      )

  /** Runs `lookup` for each ref, up to four at a time, each bounded by
    * `lookupTimeout`, and all of them by [[deadline]]. A lookup still running
    * at the deadline is cancelled and fails with [[CutOff]], and one not yet
    * started fails with [[NotStarted]]. Refs in `failedBefore` are started
    * last, in its order, so lookups that keep hanging can't hold every slot
    * until the deadline and starve the others. Results are in `refs`' order.
    */
  def within[F[_]: Temporal, A]
    (refs: List[TaskDefinitionRef], lookupTimeout: FiniteDuration, failedBefore: List[TaskDefinitionRef])
    (lookup: TaskDefinitionRef => F[A]): F[List[(TaskDefinitionRef, Either[Throwable, A])]] =
    val deadline = DefinitionLookups.deadline(lookupTimeout)
    val turn = failedBefore.zipWithIndex.toMap
    for
      started <- Ref.of[F, Set[TaskDefinitionRef]](Set.empty)
      finished <- Ref.of[F, Map[TaskDefinitionRef, Either[Throwable, A]]](
        Map.empty
      )
      _ <- refs
        .sortBy(turn.get)
        .parTraverseN(concurrentLookups)(ref =>
          started.update(_ + ref) >>
            lookup(ref)
              .timeout(lookupTimeout)
              .attempt
              .flatMap(result => finished.update(_.updated(ref, result)))
        )
        .void
        .timeoutTo(deadline, ().pure[F])
      begun <- started.get
      results <- finished.get
    yield refs.map(ref =>
      ref -> results.getOrElse(
        ref,
        (if (begun(ref)) CutOff(deadline) else NotStarted(deadline)).asLeft[A]
      )
    )
    end for
  end within

  /** The refs [[within]] should start last next time, in the order to start
    * them: those of `failedBefore` that didn't get a full turn, then those
    * whose own lookup failed or timed out this time. A ref that didn't get a
    * full turn keeps its place, so failing refs take turns and a ref that
    * recovers is looked up again, while refs that never failed still go first.
    */
  def failed[A](
                 failedBefore: List[TaskDefinitionRef],
                 results: List[(TaskDefinitionRef, Either[Throwable, A])]
  ): List[TaskDefinitionRef] =
    val noFullTurn = results
      .collect:
        case (ref, Left(_: NoFullTurn)) =>
          ref
      .toSet
    val failedNow = results.collect:
      case (ref, Left(e)) if !e.isInstanceOf[NoFullTurn] => ref
    failedBefore.filter(noFullTurn) ++ failedNow
end DefinitionLookups
