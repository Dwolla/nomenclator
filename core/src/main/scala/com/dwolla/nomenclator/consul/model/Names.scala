package com.dwolla.nomenclator.consul.model

/** The Consul service ID under which a container's port is registered.
  */
opaque type ServiceId = String

object ServiceId:
  def apply(value: String): ServiceId = value

  extension (self: ServiceId) def value: String = self
end ServiceId

/** The Consul service name.
  */
opaque type ServiceName = String

object ServiceName:
  def apply(value: String): ServiceName = value

  extension (self: ServiceName) def value: String = self
end ServiceName

/** A Consul service tag. Empty tags are legal because registrator keeps them.
  */
opaque type Tag = String

object Tag:
  def apply(value: String): Tag = value

  extension (self: Tag) def value: String = self
end Tag

/** The ID the Consul agent gives a check.
  */
opaque type CheckId = String

object CheckId:
  def apply(value: String): CheckId = value

  extension (self: CheckId) def value: String = self
end CheckId

/** The address registered for a service and used as its check's target: an IP
  * address or a host name, as registrator passed it.
  */
opaque type ServiceAddress = String

object ServiceAddress:
  def apply(value: String): ServiceAddress = value

  extension (self: ServiceAddress) def value: String = self
end ServiceAddress
