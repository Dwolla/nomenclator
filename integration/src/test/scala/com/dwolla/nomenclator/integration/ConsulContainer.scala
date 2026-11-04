package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dwolla.nomenclator.consul.agent.ConsulAgent
import org.http4s.Uri
import org.testcontainers.containers.wait.strategy.Wait

import scala.concurrent.duration.*

/** A throwaway dev-mode Consul agent in a container, on a random host port or a
  * chosen one.
  */
object ConsulContainer:
  private val httpPort = 8500
  private val secondsToWaitForAgent = 120

  /** Starts an agent of `version`, with ACLs turned on by `aclConfig` if given,
    * and yields the base URI of its HTTP API.
    */
  def resource(version: ConsulVersion, aclConfig: Option[String]): Resource[IO, Uri] =
    container(version, aclConfig, hostPort = None).map(baseUri)

  /** Starts an agent of `version`, with ACLs turned on by `aclConfig` if given.
    * Its HTTP API is published on `hostPort` if given, and on a random host
    * port otherwise. Docker picks a random port afresh each time a container
    * starts, so an agent that a test stops and starts again needs a chosen one
    * to stay where its clients look for it.
    */
  def container
    (version: ConsulVersion, aclConfig: Option[String], hostPort: Option[Int]): Resource[IO, GenericContainer] =
    Resource.make(IO.blocking:
      val c = GenericContainer(
        dockerImage = version.image,
        exposedPorts = Seq(httpPort),
        env = aclConfig.map("CONSUL_LOCAL_CONFIG" -> _).toMap,
        command = Seq("agent", "-dev", "-client=0.0.0.0"),
        waitStrategy = Wait
          .forHttp("/v1/status/leader")
          .forResponsePredicate(_.contains(":"))
          .withStartupTimeout(
            java.time.Duration.ofSeconds(secondsToWaitForAgent.toLong)
          )
      )
      version.platform.foreach: p =>
        c.configure: container =>
          container.withCreateContainerCmdModifier: cmd =>
            cmd.withPlatform(p)
            ()
          ()
      hostPort.foreach: port =>
        c.configure(_.setPortBindings(java.util.List.of(s"$port:$httpPort")))
      c.start()
      c)(c => IO.blocking(c.stop()))

  /** The base URI of the HTTP API of an agent that [[container]] started.
    */
  def baseUri(c: GenericContainer): Uri =
    Uri(
      scheme = Uri.Scheme.http.some,
      authority = Uri
        .Authority(
          host = Uri.RegName(c.host),
          port = c.mappedPort(httpPort).some
        )
        .some
    )

  /** Right after startup an ACL-enabled agent can still be creating its
    * management token, so wait until it answers an authenticated request.
    */
  def awaitReady(agent: ConsulAgent[IO]): IO[Unit] =
    def attempt(remaining: Int): IO[Unit] =
      agent.state.void.handleErrorWith:
        case _ if remaining > 0 =>
          IO.sleep(500.millis) >> attempt(remaining - 1)
        case e => e.raiseError[IO, Unit]
    attempt(secondsToWaitForAgent * 2)
end ConsulContainer
