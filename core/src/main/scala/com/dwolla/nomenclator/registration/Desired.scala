package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.model.ContainerName

/** Everything the running tasks ask to have registered.
  *
  * @param awaitingDefinitions
  *   task definitions whose settings are not known yet; their tasks produce
  *   nothing until they are
  * @param customIdRequested
  *   (task ARN, container) pairs that set `SERVICE_ID` or `SERVICE_<port>_ID`,
  *   which is not honored
  */
final case class Desired(
                          registrations: List[Registration],
                          skipped: List[Skipped],
                          awaitingDefinitions: Set[TaskDefinitionRef],
                          customIdRequested: List[(String, ContainerName)]
)
