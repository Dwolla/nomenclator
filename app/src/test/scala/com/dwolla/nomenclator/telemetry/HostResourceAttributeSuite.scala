package com.dwolla.nomenclator.telemetry

import cats.effect.*
import cats.effect.std.{Env, SystemProperties}
import cats.syntax.all.*
import com.dwolla.nomenclator.registration.HostName
import munit.CatsEffectSuite

class HostResourceAttributeSuite extends CatsEffectSuite:
  private val host = HostName.from("ip-10-0-0-1").fold(fail(_), identity)
  private val property = "otel.resource.attributes"

  test("with no resource attributes set, host.name is the only one"):
    assertEquals(
      HostResourceAttribute.merged(host, None, None),
      "host.name=ip-10-0-0-1"
    )

  test("keeps the environment's and the property's attributes, property last"):
    assertEquals(
      HostResourceAttribute.merged(host, "a=1,b=2".some, "b=3".some),
      "a=1,b=2,b=3,host.name=ip-10-0-0-1"
    )

  test("an existing host.name in the environment wins"):
    assertEquals(
      HostResourceAttribute.merged(host, "host.name=other".some, None),
      "host.name=other"
    )

  test("an existing host.name in the property wins"):
    assertEquals(
      HostResourceAttribute
        .merged(host, "a=1".some, " host.name = other ".some),
      "a=1, host.name = other "
    )

  test("a host.name with no value doesn't count, as OpenTelemetry drops it"):
    assertEquals(
      HostResourceAttribute.merged(host, "host.name=".some, None),
      "host.name=,host.name=ip-10-0-0-1"
    )

  test("blank settings are ignored"):
    assertEquals(
      HostResourceAttribute.merged(host, " ".some, "".some),
      "host.name=ip-10-0-0-1"
    )

  test("characters that would break the list are percent-encoded"):
    val odd = HostName.from("a,b=c%d é").fold(fail(_), identity)
    assertEquals(
      HostResourceAttribute.merged(odd, None, None),
      "host.name=a%2Cb%3Dc%25d%20%C3%A9"
    )

  test("install sets the property from the environment and the property"):
    val environment = Map("OTEL_RESOURCE_ATTRIBUTES" -> "b=2")
    given Env[IO] = new Env[IO]:
      override def get(name: String): IO[Option[String]] =
        environment.get(name).pure[IO]
      override val entries: IO[scala.collection.immutable.Iterable[(String, String)]] =
        environment.pure[IO].widen
    for
      properties <- InMemorySystemProperties.of(Map(property -> "a=1"))
      _ <-
        given SystemProperties[IO] = properties
        HostResourceAttribute.install[IO](host)
      stored <- properties.get(property)
    yield assertEquals(
      stored,
      HostResourceAttribute
        .merged(host, "b=2".some, "a=1".some)
        .some
    )
end HostResourceAttributeSuite
