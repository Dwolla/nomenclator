package com.dwolla.nomenclator.ecs

final case class EcsTask(
                          arn: String,
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
