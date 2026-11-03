package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.TaskDefinitionRef

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Looks up task definitions within a bounded time, so a hanging ECS API can't
  * hold up a pass however many definitions are uncached.
  */
private[reconcile] object DefinitionLookups:
  private val concurrentLookups = 4

  /** Runs `lookup` for each ref, up to four at a time, each bounded by
    * `lookupTimeout`, and all of them by twice that. A lookup still unfinished
    * at the deadline is cancelled and counts as failed.
    */
  def within[F[_]: Temporal, A](refs: List[TaskDefinitionRef], lookupTimeout: FiniteDuration)
                               (lookup: TaskDefinitionRef => F[A]): F[List[(TaskDefinitionRef, Either[Throwable, A])]] =
    val deadline = lookupTimeout * 2
    for
      finished <- Ref.of[F, Map[TaskDefinitionRef, Either[Throwable, A]]](
        Map.empty
      )
      _ <- refs
        .parTraverseN(concurrentLookups)(ref =>
          lookup(ref)
            .timeout(lookupTimeout)
            .attempt
            .flatMap(result => finished.update(_.updated(ref, result)))
        )
        .void
        .timeoutTo(deadline, ().pure[F])
      results <- finished.get
    yield refs.map(ref =>
      ref -> results.getOrElse(
        ref,
        new TimeoutException(s"lookups did not all finish within $deadline")
          .asLeft[A]
      )
    )
    end for
  end within
end DefinitionLookups
