package com.dwolla.nomenclator.gen

import com.dwolla.nomenclator.model.*
import org.scalacheck.{Arbitrary, Gen}

object ModelGen:
  private val hostNameChar: Gen[Char] =
    Gen.oneOf(Gen.alphaNumChar, Gen.const('-'), Gen.const('.'))
  val identifier: Gen[String] =
    Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val hostName: Gen[HostName] =
    Gen
      .nonEmptyListOf(hostNameChar)
      .map(_.mkString)
      .map(s => HostName.from(s).fold(sys.error, identity))
  val serviceId: Gen[ServiceId] =
    identifier.map(s => ServiceId.from(s).fold(sys.error, identity))
  val serviceName: Gen[ServiceName] =
    identifier.map(s => ServiceName.from(s).fold(sys.error, identity))
  val tag: Gen[Tag] = Gen.alphaNumStr.map(Tag(_))
  val containerName: Gen[ContainerName] =
    identifier.map(s => ContainerName.from(s).fold(sys.error, identity))
  val dockerName: Gen[DockerName] =
    identifier.map(s => DockerName.from(s).fold(sys.error, identity))
  val imageName: Gen[ImageName] =
    identifier.map(s => ImageName.from(s).fold(sys.error, identity))
  private val portNumber: Gen[Int] = Gen.choose(0, 65535)
  val containerPort: Gen[ContainerPort] =
    portNumber.map(n => ContainerPort.from(n).fold(sys.error, identity))
  val hostPort: Gen[HostPort] =
    portNumber.map(n => HostPort.from(n).fold(sys.error, identity))
  val protocol: Gen[Protocol] = Gen.oneOf(Protocol.values.toSeq)

  given Arbitrary[HostName] = Arbitrary(hostName)
  given Arbitrary[ServiceId] = Arbitrary(serviceId)
  given Arbitrary[ServiceName] = Arbitrary(serviceName)
  given Arbitrary[Tag] = Arbitrary(tag)
  given Arbitrary[ContainerName] = Arbitrary(containerName)
  given Arbitrary[DockerName] = Arbitrary(dockerName)
  given Arbitrary[ImageName] = Arbitrary(imageName)
  given Arbitrary[ContainerPort] = Arbitrary(containerPort)
  given Arbitrary[HostPort] = Arbitrary(hostPort)
  given Arbitrary[Protocol] = Arbitrary(protocol)
end ModelGen
