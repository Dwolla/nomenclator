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

  /** Forgets what is remembered about every other task definition, so that
    * memory is bounded by the tasks on the host rather than by every revision
    * it has ever run.
    */
  def retainOnly(refs: Set[TaskDefinitionRef]): F[Unit]
end TaskDefinitions
