package com.dwolla.nomenclator.gen

import com.dwolla.nomenclator.ecs.*
import org.scalacheck.{Arbitrary, Gen}

object EcsGen:
  private val identifier: Gen[String] = ModelGen.identifier

  val portBinding: Gen[PortBinding] =
    for
      containerPort <- ModelGen.containerPort
      hostPort <- ModelGen.hostPort
      protocol <- ModelGen.protocol
    yield PortBinding(containerPort, hostPort, protocol)

  val taskDefinitionRef: Gen[TaskDefinitionRef] =
    for
      family <- identifier
      revision <- Gen.posNum[Int]
    yield TaskDefinitionRef(family, revision)

  val taskStatus: Gen[TaskStatus] =
    Gen.oneOf(
      Gen.const(TaskStatus.Pending),
      Gen.const(TaskStatus.Running),
      Gen.const(TaskStatus.Stopped),
      identifier
        .suchThat(s => TaskStatus.fromString(s) == TaskStatus.Other(s))
        .map(TaskStatus.Other(_))
    )

  val ecsContainer: Gen[EcsContainer] =
    for
      name <- ModelGen.containerName
      dockerName <- ModelGen.dockerName
      image <- ModelGen.imageName
      ports <- Gen.listOf(portBinding)
    yield EcsContainer(name, dockerName, image, ports)

  val ecsTask: Gen[EcsTask] =
    for
      arn <- identifier.map("arn:aws:ecs:task/" + _)
      definition <- taskDefinitionRef
      known <- taskStatus
      desired <- taskStatus
      containers <- Gen.listOf(ecsContainer)
    yield EcsTask(arn, definition, known, desired, containers)

  val instanceMetadata: Gen[InstanceMetadata] =
    for
      cluster <- identifier
      arn <- identifier.map("arn:aws:ecs:container-instance/" + _)
    yield InstanceMetadata(cluster, arn)

  given Arbitrary[PortBinding] = Arbitrary(portBinding)
  given Arbitrary[TaskDefinitionRef] = Arbitrary(taskDefinitionRef)
  given Arbitrary[TaskStatus] = Arbitrary(taskStatus)
  given Arbitrary[EcsContainer] = Arbitrary(ecsContainer)
  given Arbitrary[EcsTask] = Arbitrary(ecsTask)
  given Arbitrary[InstanceMetadata] = Arbitrary(instanceMetadata)
end EcsGen
