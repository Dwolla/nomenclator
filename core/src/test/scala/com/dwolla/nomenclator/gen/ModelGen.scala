package com.dwolla.nomenclator.gen

import com.dwolla.nomenclator.model.*
import org.scalacheck.{Arbitrary, Gen}

object ModelGen:

  /** For generators whose values are valid by construction; a rejection fails
    * the generator instead of throwing.
    */
  private def validated[A, B](from: A => Either[String, B])(a: A): Gen[B] =
    from(a).fold(_ => Gen.fail, Gen.const)

  private val hostNameChar: Gen[Char] =
    Gen.oneOf(Gen.alphaNumChar, Gen.const('-'), Gen.const('.'))
  val identifier: Gen[String] =
    Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  val hostName: Gen[HostName] =
    Gen
      .nonEmptyListOf(hostNameChar)
      .map(_.mkString)
      .flatMap(validated(HostName.from))
  val serviceId: Gen[ServiceId] =
    identifier.map(s => ServiceId(s))
  val serviceName: Gen[ServiceName] =
    identifier.map(s => ServiceName(s))
  val tag: Gen[Tag] = Gen.alphaNumStr.map(Tag(_))
  val containerName: Gen[ContainerName] =
    identifier.map(s => ContainerName(s))
  val dockerName: Gen[DockerName] =
    identifier.map(s => DockerName(s))
  val imageName: Gen[ImageName] =
    identifier.map(s => ImageName(s))
  private val portNumber: Gen[Int] = Gen.choose(0, 65535)
  val containerPort: Gen[ContainerPort] =
    portNumber.flatMap(validated(ContainerPort.from))
  val hostPort: Gen[HostPort] =
    portNumber.flatMap(validated(HostPort.from))
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
