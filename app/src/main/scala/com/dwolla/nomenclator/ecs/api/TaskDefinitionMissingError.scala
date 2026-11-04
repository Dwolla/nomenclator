package com.dwolla.nomenclator.ecs.api

import com.dwolla.nomenclator.ecs.TaskDefinitionRef

/** ECS answered DescribeTaskDefinition without a task definition.
  */
final case class TaskDefinitionMissingError(ref: TaskDefinitionRef)
    extends RuntimeException(
      s"ECS returned no task definition for ${ref.value}"
    )
