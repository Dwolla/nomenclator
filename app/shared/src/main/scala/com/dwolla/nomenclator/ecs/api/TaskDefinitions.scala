package com.dwolla.nomenclator.ecs.api

import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.model.ContainerName
import com.dwolla.nomenclator.settings.ContainerSettings

/** What a task definition says about how its containers are registered.
  */
trait TaskDefinitions[F[_]]:

  /** The `SERVICE_*` settings of each named container in the task definition.
    * Fails if ECS can't describe it.
    */
  def settings(ref: TaskDefinitionRef): F[Map[ContainerName, ContainerSettings]]
end TaskDefinitions
