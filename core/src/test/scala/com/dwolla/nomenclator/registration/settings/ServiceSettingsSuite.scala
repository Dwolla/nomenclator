package com.dwolla.nomenclator.registration.settings

import com.dwolla.nomenclator.ecs.model.ContainerPort
import com.dwolla.nomenclator.gen.{ModelGen, SettingsGen}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.{forAll, propBoolean}

class ServiceSettingsSuite extends ScalaCheckSuite:
  private def port(n: Int): ContainerPort =
    ContainerPort.from(n).fold(fail(_), identity)

  private def settingsFor
    (n: Int, env: Map[String, String] = Map.empty, labels: Map[String, String] = Map.empty): ServiceSettings =
    ServiceSettings.forPort(ContainerSettings.fromRaw(env, labels), port(n))

  test(
    "fromRaw keeps only keys starting with exactly SERVICE_, with values verbatim"
  ):
    val settings = ContainerSettings.fromRaw(
      Map(
        "SERVICE_NAME" -> "a=b",
        "service_name" -> "x",
        "PATH" -> "/bin",
        "SERVICE_TAGS" -> ""
      ),
      Map("SERVICE_X" -> "y", "Service_Y" -> "z", "com.example.label" -> "q")
    )
    assertEquals(
      settings.environment,
      Map("SERVICE_NAME" -> "a=b", "SERVICE_TAGS" -> "")
    )
    assertEquals(settings.labels, Map("SERVICE_X" -> "y"))

  test("a port-specific env entry beats a generic label"):
    val s = settingsFor(
      80,
      env = Map("SERVICE_80_NAME" -> "a"),
      labels = Map("SERVICE_NAME" -> "b")
    )
    assertEquals(s.get("name"), Some("a"))
    assert(s.isPortSpecific("name"))

  test(
    "a port-specific entry beats a generic one whichever is processed first"
  ):
    val generic = Map("SERVICE_NAME" -> "generic")
    val specific = Map("SERVICE_80_NAME" -> "specific")
    assertEquals(
      settingsFor(80, env = specific, labels = generic).get("name"),
      Some("specific")
    )
    assertEquals(
      settingsFor(80, env = generic, labels = specific).get("name"),
      Some("specific")
    )

  test("a label beats an env entry at the same specificity"):
    assertEquals(
      settingsFor(
        80,
        env = Map("SERVICE_NAME" -> "env"),
        labels = Map("SERVICE_NAME" -> "label")
      ).get("name"),
      Some("label")
    )
    assertEquals(
      settingsFor(
        80,
        env = Map("SERVICE_80_NAME" -> "env"),
        labels = Map("SERVICE_80_NAME" -> "label")
      ).get("name"),
      Some("label")
    )

  test(
    "keys that collide after lowercasing resolve by sorted key, the later one winning"
  ):
    val labels = Map("SERVICE_Name" -> "mixed", "SERVICE_NAME" -> "upper")
    assertEquals(settingsFor(80, labels = labels).get("name"), Some("mixed"))

  test(
    "SERVICE_53_NAME applies to port 53 whatever the protocol, and to no other port"
  ):
    val env = Map("SERVICE_53_NAME" -> "dns")
    assertEquals(settingsFor(53, env = env).get("name"), Some("dns"))
    assertEquals(settingsFor(80, env = env).get("name"), None)
    assert(!settingsFor(80, env = env).isPortSpecific("name"))

  test("lowercase prefixes are ignored"):
    assertEquals(
      settingsFor(80, env = Map("service_name" -> "x")).get("name"),
      None
    )
    assertEquals(
      settingsFor(80, labels = Map("service_80_name" -> "x")).get("name"),
      None
    )

  test("only the remainder after SERVICE_ is lowercased"):
    val s = settingsFor(80, env = Map("SERVICE_Check_HTTP" -> "/h"))
    assertEquals(s.get("check_http"), Some("/h"))
    assertEquals(s.get("Check_HTTP"), None)

  test("an empty port-specific value is port-specific yet reads as unset"):
    val s = settingsFor(80, env = Map("SERVICE_80_NAME" -> ""))
    assert(s.isPortSpecific("name"))
    assertEquals(s.get("name"), None)

  test("an empty port-specific value still suppresses a generic one"):
    val s = settingsFor(
      80,
      env = Map("SERVICE_80_NAME" -> "", "SERVICE_NAME" -> "generic")
    )
    assertEquals(s.get("name"), None)

  test("an empty generic value reads as unset and is not port-specific"):
    val s = settingsFor(80, env = Map("SERVICE_NAME" -> ""))
    assertEquals(s.get("name"), None)
    assert(!s.isPortSpecific("name"))

  test("any non-empty ignore value ignores the port, including false and 0"):
    assert(settingsFor(80, env = Map("SERVICE_IGNORE" -> "false")).ignored)
    assert(settingsFor(80, env = Map("SERVICE_IGNORE" -> "0")).ignored)
    assert(!settingsFor(80, env = Map("SERVICE_IGNORE" -> "")).ignored)
    assert(!settingsFor(80).ignored)

  test("SERVICE_<port>_IGNORE ignores only that port"):
    val env = Map("SERVICE_80_IGNORE" -> "1")
    assert(settingsFor(80, env = env).ignored)
    assert(!settingsFor(443, env = env).ignored)

  test(
    "a signed or zero-padded port prefix matches no port and sets nothing"
  ):
    val env = Map(
      "SERVICE_+80_NAME" -> "plus",
      "SERVICE_080_NAME" -> "padded",
      "SERVICE_-80_NAME" -> "minus"
    )
    val s = settingsFor(80, env = env)
    assertEquals(s.values, Map.empty[String, String])
    assert(!s.isPortSpecific("name"))

  test("a number with no attribute part is a generic attribute"):
    val s = settingsFor(80, env = Map("SERVICE_80" -> "x"))
    assertEquals(s.get("80"), Some("x"))
    assert(!s.isPortSpecific("80"))

  test(
    "a number too large for a Go int is a generic attribute prefix, not a port"
  ):
    val s =
      settingsFor(80, env = Map("SERVICE_99999999999999999999_NAME" -> "x"))
    assertEquals(s.get("99999999999999999999_name"), Some("x"))

  test("values keep embedded equals signs and empty tag segments verbatim"):
    assertEquals(
      settingsFor(80, env = Map("SERVICE_TAGS" -> "a,,b=c")).get("tags"),
      Some("a,,b=c")
    )

  /** Mostly the small pool, so P and Q often have colliding entries.
    */
  private val anyOrPooledPort: Gen[ContainerPort] =
    Gen.frequency(
      3 -> SettingsGen.settingsPort.map(port),
      1 -> ModelGen.containerPort
    )

  property("a port-specific key for port P never affects port Q != P"):
    forAll(
      SettingsGen.containerSettings,
      anyOrPooledPort,
      anyOrPooledPort,
      ModelGen.identifier,
      ModelGen.identifier
    ): (settings, p, q, attribute, value) =>
      (p != q) ==> {
        val portSpecific = s"SERVICE_${p.value.value}_$attribute"
        val extended = ContainerSettings(
          settings.environment + (portSpecific -> value),
          settings.labels + (portSpecific -> value)
        )
        ServiceSettings.forPort(extended, q) == ServiceSettings.forPort(
          settings,
          q
        )
      }
end ServiceSettingsSuite
