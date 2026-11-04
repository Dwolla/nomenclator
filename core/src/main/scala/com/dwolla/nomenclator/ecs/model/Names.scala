package com.dwolla.nomenclator.ecs.model

/** The ARN of an ECS task.
  */
opaque type TaskArn = String

object TaskArn:
  def apply(value: String): TaskArn = value

  extension (self: TaskArn) def value: String = self
end TaskArn

/** The family of an ECS task definition, which its revisions share.
  */
opaque type TaskFamily = String

object TaskFamily:
  def apply(value: String): TaskFamily = value

  extension (self: TaskFamily) def value: String = self
end TaskFamily

/** The ECS container definition name.
  */
opaque type ContainerName = String

object ContainerName:
  def apply(value: String): ContainerName = value

  extension (self: ContainerName) def value: String = self
end ContainerName

/** The Docker container name reported by ECS agent introspection.
  */
opaque type DockerName = String

object DockerName:
  def apply(value: String): DockerName = value

  extension (self: DockerName) def value: String = self
end DockerName

/** A container image reference.
  */
opaque type ImageName = String

object ImageName:
  def apply(value: String): ImageName = value

  extension (self: ImageName) def value: String = self
end ImageName
