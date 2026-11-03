package com.dwolla.nomenclator.model

/** The Consul service ID under which a container's port is registered.
  */
opaque type ServiceId = String

object ServiceId:
  def apply(value: String): ServiceId = value

  extension (self: ServiceId) def value: String = self
end ServiceId
