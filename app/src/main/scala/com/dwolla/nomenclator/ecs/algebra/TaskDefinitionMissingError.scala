package com.dwolla.nomenclator.ecs.algebra

import com.dwolla.nomenclator.ecs.model.TaskDefinitionRef

/** ECS answered DescribeTaskDefinition without a task definition.
  */
final case class TaskDefinitionMissingError(ref: TaskDefinitionRef)
    extends RuntimeException(
      s"ECS returned no task definition for ${ref.value}"
    )
