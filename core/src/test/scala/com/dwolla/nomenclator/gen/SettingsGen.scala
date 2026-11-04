package com.dwolla.nomenclator.gen

import com.dwolla.nomenclator.registration.settings.ContainerSettings
import org.scalacheck.Gen

object SettingsGen:

  /** A small pool, so that generated settings often collide on the same port.
    */
  val settingsPort: Gen[Int] = Gen.oneOf(53, 80, 443, 8080)

  val attribute: Gen[String] =
    Gen.oneOf(
      Gen.oneOf("name", "tags", "ignore", "Check_HTTP", "check_interval"),
      ModelGen.identifier
    )

  /** A raw container key: usually `SERVICE_[<port>_]<attribute>`, sometimes
    * with a prefix that must be dropped.
    */
  val rawKey: Gen[String] =
    for
      prefix <- Gen.frequency(
        9 -> Gen.const("SERVICE_"),
        1 -> Gen.const("service_")
      )
      port <- Gen.option(settingsPort)
      attr <- attribute
    yield s"$prefix${port.fold("")(p => s"${p}_")}$attr"

  val rawEntries: Gen[Map[String, String]] =
    Gen.mapOf(Gen.zip(rawKey, Gen.oneOf(Gen.const(""), Gen.alphaNumStr)))

  val containerSettings: Gen[ContainerSettings] =
    Gen
      .zip(rawEntries, rawEntries)
      .map((env, labels) => ContainerSettings.fromRaw(env, labels))
end SettingsGen
