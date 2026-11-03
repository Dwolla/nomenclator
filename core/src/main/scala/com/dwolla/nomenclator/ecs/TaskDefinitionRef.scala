package com.dwolla.nomenclator.ecs

import com.dwolla.nomenclator.model.TaskFamily

/** One revision of a task definition, which never changes once registered.
  */
final case class TaskDefinitionRef(family: TaskFamily, revision: Int):

  /** `family:revision`, as ECS names a revision.
    */
  def value: String = s"${family.value}:$revision"
end TaskDefinitionRef
