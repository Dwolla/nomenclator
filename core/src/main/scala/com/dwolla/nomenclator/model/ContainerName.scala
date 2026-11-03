package com.dwolla.nomenclator.model

/** The ECS container definition name.
  */
opaque type ContainerName = String

object ContainerName:
  def from(value: String): Either[String, ContainerName] = Right(value)

  extension (self: ContainerName) def value: String = self
end ContainerName
