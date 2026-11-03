package com.dwolla.nomenclator.model

/** The Consul service name.
  */
opaque type ServiceName = String

object ServiceName:
  def apply(value: String): ServiceName = value

  extension (self: ServiceName) def value: String = self
end ServiceName
