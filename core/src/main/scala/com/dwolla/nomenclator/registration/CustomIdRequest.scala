package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.model.{ContainerName, TaskArn}

/** A task's container that set `SERVICE_ID` or `SERVICE_<port>_ID`, which is
  * not honored: its ports are registered under host-shaped IDs instead.
  */
final case class CustomIdRequest(task: TaskArn, container: ContainerName)
