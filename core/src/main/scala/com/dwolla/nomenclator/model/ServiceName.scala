package com.dwolla.nomenclator.model

/** The Consul service name.
  */
opaque type ServiceName = String

object ServiceName:
  def from(value: String): Either[String, ServiceName] = Right(value)

  extension (self: ServiceName) def value: String = self
end ServiceName
