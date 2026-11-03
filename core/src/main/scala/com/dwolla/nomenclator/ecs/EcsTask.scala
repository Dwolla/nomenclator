package com.dwolla.nomenclator.ecs

import com.dwolla.nomenclator.model.TaskArn

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
