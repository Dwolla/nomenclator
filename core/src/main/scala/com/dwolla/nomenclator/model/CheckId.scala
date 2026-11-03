package com.dwolla.nomenclator.model

/** The ID the Consul agent gives a check.
  */
opaque type CheckId = String

object CheckId:
  def apply(value: String): CheckId = value

  extension (self: CheckId) def value: String = self
end CheckId
