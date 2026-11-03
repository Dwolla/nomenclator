package com.dwolla.nomenclator.aws

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Decoder
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.typelevel.log4cats.Logger
import smithy4s.aws.AwsCredentials

import java.time.Instant
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** Credentials from the ECS task-role endpoint, refreshed in the background.
  *
  * This replaces smithy4s's default credentials chain, which swallows errors
  * and whose refresh loop ends silently after one failure.
  */
object ContainerCredentials:

  /** Where the ECS agent serves task-role credentials.
    */
  val defaultBase: Uri = uri"http://169.254.170.2"

  private val refreshBeforeExpiration = 5.minutes
  private val initialBackoff = 1.second
  private val maxBackoff = 1.minute
  private val fetchTimeout = 10.seconds

  /** No wait is longer than this, so a bogus `Expiration` can't park the
    * refresh loop for years.
    */
  private val maxRefreshDelay = 1.hour

  private final case class Fetched(credentials: AwsCredentials, expiration: Instant)

  /** Credentials whose `toString` never shows the secret or session token.
    */
  private final case class Redacted(accessKeyId: String, secretAccessKey: String, sessionToken: Option[String])
      extends AwsCredentials:
    override def toString: String = s"AwsCredentials($accessKeyId, <redacted>)"

  private given Decoder[Fetched] = Decoder.instance: c =>
    (
      c.downField("AccessKeyId").as[String],
      c.downField("SecretAccessKey").as[String],
      c.downField("Token").as[Option[String]],
      c.downField("Expiration").as[Instant]
    ).mapN((id, secret, token, expiration) => Fetched(Redacted(id, secret, token), expiration))

  /** How long to wait before refreshing credentials that expire at
    * `expiration`. Total: never throws, whatever the instant, and always
    * between one minute and `maxRefreshDelay`. The one-minute floor keeps an
    * endpoint that returns near-expiry credentials from being polled in a tight
    * loop.
    */
  private def delayUntilRefresh(now: FiniteDuration, expiration: Instant): FiniteDuration =
    val secondsLeft = java.time.Duration
      .between(Instant.ofEpochMilli(now.toMillis), expiration)
      .getSeconds
    val clampedLeft = secondsLeft.min(2.hours.toSeconds).max(0L).seconds
    (clampedLeft - refreshBeforeExpiration).max(1.minute).min(maxRefreshDelay)

  /** Fetches credentials once, then keeps them fresh until the resource is
    * released. Acquisition fails if the first fetch fails. Afterwards the
    * returned effect only reads the latest good credentials, and refresh
    * failures are retried forever with capped exponential backoff.
    *
    * @param relativeUri
    *   the value of `AWS_CONTAINER_CREDENTIALS_RELATIVE_URI`
    */
  def resource[F[_]: Temporal](
                                client: Client[F],
                                relativeUri: String,
                                logger: Logger[F],
                                base: Uri = defaultBase
  ): Resource[F, F[AwsCredentials]] =
    def fetch: F[Fetched] =
      (for
        uri <- Uri.fromString(base.renderString + relativeUri).liftTo[F]
        fetched <- client
          .run(Request[F](Method.GET, uri))
          .use: response =>
            if (response.status.isSuccess)
              // The body holds the secret, so decoding failures must not echo it.
              response.as[Fetched](using
                summon,
                jsonOfSensitive[F, Fetched](_ => "<redacted>")
              )
            else
              response.body.compile.drain >>
                ContainerCredentialsError(
                  s"endpoint answered with status ${response.status.code}"
                ).raiseError[F, Fetched]
      yield fetched)
        .timeout(fetchTimeout)
        .adaptError:
          case _: MalformedMessageBodyFailure | _: InvalidMessageBodyFailure =>
            ContainerCredentialsError("response body could not be decoded")
          case _: TimeoutException =>
            ContainerCredentialsError(s"no answer within $fetchTimeout")

    // Only the failure's type, or a ContainerCredentialsError's reason, is
    // logged: other messages could echo part of the credentials.
    def describe(e: Throwable): String = e match
      case e: ContainerCredentialsError => e.getMessage
      case other                        => other.getClass.getName

    def hasExpired(expiration: Instant): F[Boolean] =
      Clock[F].realTime.map(now => Instant.ofEpochMilli(now.toMillis).isAfter(expiration))

    // Retries forever. The credentials being served stay in the Ref
    // meanwhile; expiredLogged remembers that we told the operator they ran out.
    def refetch(serving: Instant, backoff: FiniteDuration, failing: Boolean, expiredLogged: Boolean): F[Fetched] =
      fetch.attempt.flatMap:
        case Right(fetched) =>
          logger
            .info("Task-role credentials refreshed after failures")
            .whenA(failing) >>
            logger
              .info("Serving unexpired task-role credentials again")
              .whenA(expiredLogged) >>
            fetched.pure[F]
        case Left(e) =>
          for
            _ <- logger
              .warn(
                s"Refreshing task-role credentials failed, retrying with backoff: ${describe(e)}"
              )
              .unlessA(failing)
            expired <- hasExpired(serving)
            _ <- logger
              .error(
                "Task-role credentials have expired and refreshing keeps failing; AWS calls will fail"
              )
              .whenA(expired && !expiredLogged)
            _ <- Temporal[F].sleep(backoff)
            next <- refetch(
              serving,
              (backoff * 2).min(maxBackoff),
              failing = true,
              expiredLogged || expired
            )
          yield next

    // One wait-then-refresh cycle. Whatever goes wrong in it, the loop
    // carries on with the credentials it has.
    def refreshOnce(ref: Ref[F, AwsCredentials])(current: Fetched): F[Fetched] =
      (for
        now <- Clock[F].realTime
        _ <- Temporal[F].sleep(delayUntilRefresh(now, current.expiration))
        next <- refetch(
          current.expiration,
          initialBackoff,
          failing = false,
          expiredLogged = false
        )
        _ <- ref.set(next.credentials)
      yield next).handleErrorWith: e =>
        logger.error(
          s"Task-role credentials refresh loop hit an unexpected error: ${e.getClass.getName}"
        ) >> Temporal[F].sleep(maxBackoff).as(current)

    for
      first <- fetch.toResource
      ref <- Ref.of[F, AwsCredentials](first.credentials).toResource
      _ <- first.iterateForeverM(refreshOnce(ref)).background
    yield ref.get
  end resource
end ContainerCredentials
