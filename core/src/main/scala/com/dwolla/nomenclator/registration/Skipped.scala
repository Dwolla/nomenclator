package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.model.{ContainerName, ContainerPort, TaskArn}

/** A container port that was deliberately not registered.
  */
final case class Skipped(task: TaskArn, container: ContainerName, port: ContainerPort, reason: SkipReason)
