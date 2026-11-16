package com.dwolla.nomenclator.registration.settings

import cats.data.EitherNec
import cats.syntax.all.*

import scala.concurrent.duration.*

/** The health check requested by a service's `check_*` settings.
  */
enum CheckSettings:
  case Http(path: String, interval: FiniteDuration, timeout: Option[FiniteDuration])
  case Tcp(interval: FiniteDuration, timeout: Option[FiniteDuration])

  /** `check_script` or `check_cmd`: Nomenclator refuses to register these.
    */
  case Script

  /** `check_ttl`: Nomenclator refuses to register these.
    */
  case Ttl
  case NoCheck
end CheckSettings

object CheckSettings:
  private val defaultInterval = 10.seconds

  /** First match wins, in registrator's `buildCheck` order: `check_http`, then
    * `check_cmd`/`check_script`, then `check_ttl`, then `check_tcp`. Every
    * invalid duration is reported. Only HTTP and TCP checks read an interval
    * and timeout.
    */
  def from(settings: ServiceSettings): EitherNec[SettingsError, CheckSettings] =
    settings
      .get("check_http")
      .map(path => durations(settings).map((interval, timeout) => Http(path, interval, timeout)))
      .orElse(
        settings
          .get("check_cmd")
          .orElse(settings.get("check_script"))
          .map(_ => Script.asRight)
      )
      .orElse(settings.get("check_ttl").map(_ => Ttl.asRight))
      .orElse(
        settings
          .get("check_tcp")
          .map(_ =>
            durations(settings)
              .map((interval, timeout) => Tcp(interval, timeout))
          )
      )
      .getOrElse(NoCheck.asRight)

  private def durations(settings: ServiceSettings): EitherNec[SettingsError, (FiniteDuration, Option[FiniteDuration])] =
    (
      duration(settings, "check_interval")(_ > Duration.Zero)
        .map(_.getOrElse(defaultInterval)),
      duration(settings, "check_timeout")(_ >= Duration.Zero)
    ).parTupled

  /** A value that is not a Go duration, or that fails `isValid`, is an error.
    */
  private def duration(settings: ServiceSettings, key: String)
                      (isValid: FiniteDuration => Boolean): EitherNec[SettingsError, Option[FiniteDuration]] =
    settings
      .get(key)
      .traverse: value =>
        GoDuration
          .parse(value)
          .toOption
          .filter(isValid)
          .toRight(SettingsError.InvalidDuration(key, value))
          .toEitherNec
end CheckSettings
