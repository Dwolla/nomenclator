package com.dwolla.nomenclator.registration

import cats.data.NonEmptyChain
import com.dwolla.nomenclator.ecs.model.{ContainerName, ContainerPort, TaskArn, TaskDefinitionRef}
import com.dwolla.nomenclator.registration.settings.SettingsError

/** Everything the running tasks ask to have registered.
  *
  * @param awaitingDefinitions
  *   task definitions whose settings are not known yet; their tasks produce
  *   nothing until they are
  * @param customIdRequested
  *   the containers that set `SERVICE_ID` or `SERVICE_<port>_ID`, which is not
  *   honored
  */
final case class Desired(
                          registrations: List[Registration],
                          skipped: List[Skipped],
                          awaitingDefinitions: Set[TaskDefinitionRef],
                          customIdRequested: List[CustomIdRequest]
)

/** A container port that was deliberately not registered.
  */
final case class Skipped(task: TaskArn, container: ContainerName, port: ContainerPort, reason: SkipReason)

/** Why a container port was not turned into a registration.
  */
enum SkipReason:
  case Ignored
  case ScriptCheck
  case TtlCheck

  /** Unpublished ports never reach `core` (every `PortBinding` has a host
    * port), so nothing here produces this case. It exists so the skipped-services
    * metric can report it, as zero.
    */
  case NoHostPort
  case InvalidSettings(errors: NonEmptyChain[SettingsError])
end SkipReason

/** A task's container that set `SERVICE_ID` or `SERVICE_<port>_ID`, which is
  * not honored: its ports are registered under host-shaped IDs instead.
  */
final case class CustomIdRequest(task: TaskArn, container: ContainerName)
