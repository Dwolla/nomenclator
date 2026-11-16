package com.dwolla.nomenclator.ecs.algebra

import com.dwolla.nomenclator.ecs.model.{ContainerName, TaskDefinitionRef}
import com.dwolla.nomenclator.registration.settings.ContainerSettings

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
