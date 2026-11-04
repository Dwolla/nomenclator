package com.dwolla.nomenclator.telemetry

import cats.effect.Sync
import cats.effect.std.Env
import cats.syntax.all.*
import com.dwolla.nomenclator.registration.HostName

import java.nio.charset.StandardCharsets

/** Identifies the host on every metric and span through the `host.name`
  * resource attribute, which the OpenTelemetry SDK reads from
  * `otel.resource.attributes` when it starts.
  */
object HostResourceAttribute:
  private val property = "otel.resource.attributes"
  private val variable = "OTEL_RESOURCE_ATTRIBUTES"
  private val key = "host.name"

  /** Sets `otel.resource.attributes` to [[merged]]. Call it before the SDK
    * starts.
    */
  def install[F[_]: Sync: Env](host: HostName): F[Unit] =
    for
      fromEnvironment <- Env[F].get(variable)
      fromProperty <- Sync[F].delay(sys.props.get(property))
      _ <- Sync[F].delay(
        sys.props.update(property, merged(host, fromEnvironment, fromProperty))
      )
    yield ()

  /** The attributes already set, then `host.name` unless one of them sets it.
    *
    * The SDK reads the property instead of the environment variable when both
    * are set, so both are kept here; listed last, the property's attributes win
    * over the environment's, as they would have.
    */
  def merged(host: HostName, fromEnvironment: Option[String], fromProperty: Option[String]): String =
    val existing =
      List(fromEnvironment, fromProperty).flatten.filterNot(_.isBlank)
    val hostAlreadyNamed = existing
      .flatMap(_.split(","))
      .map(_.split("=", 2).map(_.trim))
      .exists:
        case Array(`key`, value) => value.nonEmpty
        case _                   => false
    (if (hostAlreadyNamed) existing
     else existing :+ s"$key=${percentEncoded(host.value)}").mkString(",")

  /** Values are percent-decoded by the SDK, so anything that isn't plainly safe
    * in a comma-separated `key=value` list is encoded.
    */
  private def percentEncoded(value: String): String =
    value
      .getBytes(StandardCharsets.UTF_8)
      .toList
      .map: byte =>
        val c = (byte & 0xff).toChar
        if ((c.isLetterOrDigit && c < 128) || "-._~".contains(c)) c.toString
        else f"%%${byte & 0xff}%02X"
      .mkString
end HostResourceAttribute
