package com.dwolla.nomenclator.ecs.model

import java.util.Locale

final case class EcsTask(
                          arn: TaskArn,
                          definition: TaskDefinitionRef,
                          knownStatus: TaskStatus,
                          desiredStatus: TaskStatus,
                          containers: List[EcsContainer]
):

  /** The agent reports the task running and ECS still wants it running. Only
    * such tasks are registered.
    */
  def isRunning: Boolean =
    knownStatus == TaskStatus.Running && desiredStatus == TaskStatus.Running
end EcsTask

final case class EcsContainer(name: ContainerName, dockerName: DockerName, image: ImageName, ports: List[PortBinding])

enum TaskStatus:
  case Pending, Running, Stopped
  case Other(value: String)

object TaskStatus:
  def fromString(value: String): TaskStatus =
    value.toUpperCase(Locale.ROOT) match
      case "PENDING" => Pending
      case "RUNNING" => Running
      case "STOPPED" => Stopped
      case _         => Other(value)
