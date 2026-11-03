package com.dwolla.nomenclator.ecs

import com.dwolla.nomenclator.model.*

final case class PortBinding(containerPort: ContainerPort, hostPort: HostPort, protocol: Protocol)
