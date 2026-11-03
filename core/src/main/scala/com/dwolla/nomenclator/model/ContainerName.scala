package com.dwolla.nomenclator.model

/** The ECS container definition name.
  */
opaque type ContainerName = String

object ContainerName:
  def apply(value: String): ContainerName = value

  extension (self: ContainerName) def value: String = self
end ContainerName
