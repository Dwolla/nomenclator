package com.dwolla.nomenclator.startup

/** Nomenclator can't start, for the reason given, which says what to fix.
  */
final case class StartupError(reason: String) extends RuntimeException(reason)
