package com.dwolla.nomenclator.settings

import cats.data.NonEmptyChain
import com.dwolla.nomenclator.model.ContainerPort
import munit.FunSuite

import scala.concurrent.duration.*

class CheckSettingsSuite extends FunSuite:
  private def checkFor(env: (String, String)*) =
    CheckSettings.from(
      ServiceSettings.forPort(
        ContainerSettings.fromRaw(env.toMap, Map.empty),
        ContainerPort.from(80).fold(fail(_), identity)
      )
    )

  test(
    "check_http builds an HTTP check with the default interval and no timeout"
  ):
    assertEquals(
      checkFor("SERVICE_CHECK_HTTP" -> "/health"),
      Right(CheckSettings.Http("/health", 10.seconds, None))
    )

  test("check_http takes Go-style interval and timeout"):
    assertEquals(
      checkFor(
        "SERVICE_CHECK_HTTP" -> "/h",
        "SERVICE_CHECK_INTERVAL" -> "1m30s",
        "SERVICE_CHECK_TIMEOUT" -> "500ms"
      ),
      Right(CheckSettings.Http("/h", 90.seconds, Some(500.millis)))
    )

  test("the HTTP path is kept verbatim, without a leading slash added"):
    assertEquals(
      checkFor("SERVICE_CHECK_HTTP" -> "health"),
      Right(CheckSettings.Http("health", 10.seconds, None))
    )

  test("check_tcp with no interval defaults to 10 seconds and no timeout"):
    assertEquals(
      checkFor("SERVICE_CHECK_TCP" -> "true"),
      Right(CheckSettings.Tcp(10.seconds, None))
    )

  test("check_tcp=false still creates a TCP check"):
    assertEquals(
      checkFor("SERVICE_CHECK_TCP" -> "false"),
      Right(CheckSettings.Tcp(10.seconds, None))
    )

  test("check_tcp takes interval and timeout"):
    assertEquals(
      checkFor(
        "SERVICE_CHECK_TCP" -> "1",
        "SERVICE_CHECK_INTERVAL" -> "15s",
        "SERVICE_CHECK_TIMEOUT" -> "2s"
      ),
      Right(CheckSettings.Tcp(15.seconds, Some(2.seconds)))
    )

  test("check_script and check_cmd both give Script"):
    assertEquals(
      checkFor("SERVICE_CHECK_SCRIPT" -> "x"),
      Right(CheckSettings.Script)
    )
    assertEquals(
      checkFor("SERVICE_CHECK_CMD" -> "x"),
      Right(CheckSettings.Script)
    )

  test("check_ttl gives Ttl and reads no interval or timeout"):
    assertEquals(
      checkFor("SERVICE_CHECK_TTL" -> "30s"),
      Right(CheckSettings.Ttl)
    )
    assertEquals(
      checkFor(
        "SERVICE_CHECK_TTL" -> "30s",
        "SERVICE_CHECK_INTERVAL" -> "soon"
      ),
      Right(CheckSettings.Ttl)
    )

  test("script and cmd beat ttl, and ttl beats tcp"):
    assertEquals(
      checkFor("SERVICE_CHECK_CMD" -> "x", "SERVICE_CHECK_TTL" -> "1s"),
      Right(CheckSettings.Script)
    )
    assertEquals(
      checkFor("SERVICE_CHECK_TTL" -> "1s", "SERVICE_CHECK_TCP" -> "1"),
      Right(CheckSettings.Ttl)
    )

  test("a non-positive interval is invalid"):
    List("0s", "-5s", "0").foreach: interval =>
      assertEquals(
        checkFor(
          "SERVICE_CHECK_TCP" -> "1",
          "SERVICE_CHECK_INTERVAL" -> interval
        ),
        Left(
          NonEmptyChain.one(
            SettingsError.InvalidDuration("check_interval", interval)
          )
        ),
        interval
      )

  test("a negative timeout is invalid, and a zero timeout is allowed"):
    assertEquals(
      checkFor("SERVICE_CHECK_HTTP" -> "/h", "SERVICE_CHECK_TIMEOUT" -> "-1s"),
      Left(
        NonEmptyChain.one(SettingsError.InvalidDuration("check_timeout", "-1s"))
      )
    )
    assertEquals(
      checkFor("SERVICE_CHECK_HTTP" -> "/h", "SERVICE_CHECK_TIMEOUT" -> "0s"),
      Right(CheckSettings.Http("/h", 10.seconds, Some(Duration.Zero)))
    )

  test("no check settings give NoCheck"):
    assertEquals(checkFor(), Right(CheckSettings.NoCheck))
    assertEquals(
      checkFor("SERVICE_CHECK_INTERVAL" -> "soon"),
      Right(CheckSettings.NoCheck)
    )

  test("empty check values count as unset"):
    assertEquals(
      checkFor("SERVICE_CHECK_HTTP" -> "", "SERVICE_CHECK_TCP" -> ""),
      Right(CheckSettings.NoCheck)
    )

  test(
    "http beats script and cmd, which beat tcp (registrator's buildCheck order)"
  ):
    assertEquals(
      checkFor(
        "SERVICE_CHECK_HTTP" -> "/h",
        "SERVICE_CHECK_SCRIPT" -> "x",
        "SERVICE_CHECK_TCP" -> "1"
      ),
      Right(CheckSettings.Http("/h", 10.seconds, None))
    )
    assertEquals(
      checkFor("SERVICE_CHECK_CMD" -> "x", "SERVICE_CHECK_TCP" -> "1"),
      Right(CheckSettings.Script)
    )
    assertEquals(
      checkFor("SERVICE_CHECK_SCRIPT" -> "x", "SERVICE_CHECK_TCP" -> "1"),
      Right(CheckSettings.Script)
    )

  test("an invalid interval names its key and value"):
    assertEquals(
      checkFor("SERVICE_CHECK_TCP" -> "1", "SERVICE_CHECK_INTERVAL" -> "soon"),
      Left(
        NonEmptyChain.one(
          SettingsError.InvalidDuration("check_interval", "soon")
        )
      )
    )

  test("invalid interval and timeout are both reported"):
    assertEquals(
      checkFor(
        "SERVICE_CHECK_HTTP" -> "/h",
        "SERVICE_CHECK_INTERVAL" -> "soon",
        "SERVICE_CHECK_TIMEOUT" -> "later"
      ),
      Left(
        NonEmptyChain(
          SettingsError.InvalidDuration("check_interval", "soon"),
          SettingsError.InvalidDuration("check_timeout", "later")
        )
      )
    )

  test("an invalid timeout is reported alone when the interval is fine"):
    assertEquals(
      checkFor("SERVICE_CHECK_TCP" -> "1", "SERVICE_CHECK_TIMEOUT" -> "later"),
      Left(
        NonEmptyChain.one(
          SettingsError.InvalidDuration("check_timeout", "later")
        )
      )
    )
end CheckSettingsSuite
