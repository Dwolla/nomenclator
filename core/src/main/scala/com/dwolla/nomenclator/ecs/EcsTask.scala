package com.dwolla.nomenclator.ecs

final case class EcsTask(
                          arn: String,
                          definition: TaskDefinitionRef,
                          knownStatus: TaskStatus,
                          desiredStatus: TaskStatus,
                          containers: List[EcsContainer]
)
