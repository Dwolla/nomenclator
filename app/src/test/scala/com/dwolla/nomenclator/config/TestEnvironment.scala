package com.dwolla.nomenclator.config

import ciris.*

/** Environment variables a test sets, read as `ciris.env` reads the real ones.
  */
object TestEnvironment:
  def apply(variables: (String, String)*): String => ConfigValue[Effect, String] =
    val environment = variables.toMap
    name =>
      environment
        .get(name)
        .fold(ConfigValue.missing[String](ConfigKey.env(name)))(
          ConfigValue.loaded(ConfigKey.env(name), _)
        )
