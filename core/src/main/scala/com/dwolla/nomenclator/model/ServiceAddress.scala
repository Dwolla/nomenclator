package com.dwolla.nomenclator.model

/** The address registered for a service and used as its check's target: an IP
  * address or a host name, as registrator passed it.
  */
opaque type ServiceAddress = String

object ServiceAddress:
  def apply(value: String): ServiceAddress = value

  extension (self: ServiceAddress) def value: String = self
end ServiceAddress
