package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.ExposedPort
import munit.CatsEffectSuite

import java.net.{InetAddress, ServerSocket}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Pins how test containers publish their ports: on the host's loopback address
  * only, so that a port another process already listens on there fails the
  * container's start instead of being shadowed by that process. Only that
  * failure counts as a port collision, which a container on a random port
  * retries.
  */
class LoopbackPublishingSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  test(
    "a Consul container's HTTP API is published on the loopback address only"
  ):
    ConsulContainer
      .container(ConsulVersions.current, None, None)
      .use: c =>
        IO.blocking:
          c.containerInfo.getNetworkSettings.getPorts.getBindings.asScala
            .get(ExposedPort.tcp(ConsulContainer.httpPort))
            .toList
            .flatMap(_.toList)
            .map(_.getHostIp)
      .map(hostIps => assertEquals(hostIps.distinct, List("127.0.0.1")))

  test(
    "a container whose host port a loopback listener holds fails to start with a port collision"
  ):
    Resource
      .fromAutoCloseable(
        IO.blocking(
          new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        )
      )
      .use: listener =>
        ConsulContainer
          .container(ConsulVersions.current, None, listener.getLocalPort.some)
          .use_
          .attempt
      .map:
        case Left(e) =>
          assert(
            LoopbackPorts.portCollision(e),
            s"expected a port collision: $e"
          )
        case Right(()) =>
          fail("the container started beside a listener on its host port")

  test("a start that fails for another reason is not a port collision"):
    LoopbackPorts
      .started(containerPort = 80, hostPort = None)(
        GenericContainer(dockerImage = "nomenclator-test/no-such-image:absent")
      )
      .use_
      .attempt
      .map:
        case Left(e) =>
          assert(!LoopbackPorts.portCollision(e), s"not a port collision: $e")
        case Right(()) => fail("a container of an absent image started")
end LoopbackPublishingSuite
