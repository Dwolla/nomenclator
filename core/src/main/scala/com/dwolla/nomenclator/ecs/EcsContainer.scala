package com.dwolla.nomenclator.ecs

import com.dwolla.nomenclator.model.*

final case class EcsContainer(name: ContainerName, dockerName: DockerName, image: ImageName, ports: List[PortBinding])
