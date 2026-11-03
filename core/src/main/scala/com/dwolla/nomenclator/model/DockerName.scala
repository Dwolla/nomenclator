package com.dwolla.nomenclator.model

/** The Docker container name reported by ECS agent introspection.
  */
opaque type DockerName = String

object DockerName:
  def from(value: String): Either[String, DockerName] = Right(value)

  extension (self: DockerName) def value: String = self
end DockerName
