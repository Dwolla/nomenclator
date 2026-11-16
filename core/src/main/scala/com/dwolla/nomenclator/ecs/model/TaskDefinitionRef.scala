package com.dwolla.nomenclator.ecs.model

/** One revision of a task definition, which never changes once registered.
  */
final case class TaskDefinitionRef(family: TaskFamily, revision: Int):

  /** `family:revision`, as ECS names a revision.
    */
  def value: String = s"${family.value}:$revision"
end TaskDefinitionRef
