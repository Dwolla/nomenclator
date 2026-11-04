package com.dwolla.nomenclator.startup

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.dwolla.nomenclator.settings.GoDuration
import org.typelevel.log4cats.StructuredLogger

import scala.concurrent.duration.FiniteDuration

/** How what Nomenclator needs at startup is read: as the reconcile loop reads
  * the agents, every `interval`, each attempt bounded by `attemptTimeout`,
  * until `giveUpAfter` passes without an answer. Something that starts at the
  * same time as Nomenclator then doesn't make it fail.
  *
  * The first failure is logged at WARN, and a later success at INFO; giving up
  * fails with a [[StartupError]] naming what couldn't be read.
  */
final case class StartupRetry(interval: FiniteDuration, attemptTimeout: FiniteDuration, giveUpAfter: FiniteDuration):

  /** Reads `what` with `read`, retrying as described.
    */
  def apply[F[_]: Temporal: StructuredLogger, A](what: String)(read: F[A]): F[A] =
    Temporal[F].monotonic.flatMap: start =>
      def attempt(warned: Boolean): F[A] =
        read
          .timeout(attemptTimeout)
          .flatTap(_ => StructuredLogger[F].info(s"Read $what after retrying").whenA(warned))
          .handleErrorWith: e =>
            Temporal[F].monotonic.flatMap: now =>
              if (now - start >= giveUpAfter)
                StartupError(
                  s"Could not read $what within ${GoDuration.render(giveUpAfter)}: ${StartupRetry.describe(e)}"
                ).raiseError[F, A]
              else
                StructuredLogger[F]
                  .warn(Map("error" -> StartupRetry.describe(e)))(
                    s"Could not read $what yet; retrying every ${GoDuration
                        .render(interval)} for up to ${GoDuration.render(giveUpAfter)}"
                  )
                  .unlessA(warned) >>
                  Temporal[F].sleep(interval) >>
                  attempt(warned = true)
      attempt(warned = false)

  /** Acquires `resource`, retrying its acquisition as described. A failed
    * acquisition has released what it acquired, so nothing leaks between
    * attempts. Stopping Nomenclator interrupts the retries.
    */
  def acquire[F[_]: Temporal: StructuredLogger, A](what: String)(resource: Resource[F, A]): Resource[F, A] =
    Resource
      .makeFull[F, (A, F[Unit])](poll => poll(apply(what)(resource.allocated)))((_, release) => release)
      .map((a, _) => a)
end StartupRetry

object StartupRetry:
  private def describe(e: Throwable): String =
    Option(e.getMessage).fold(e.getClass.getName)(m => s"${e.getClass.getSimpleName}: $m")
