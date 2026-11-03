package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.dimafeng.testcontainers.GenericContainer
import com.dwolla.nomenclator.consul.agent.ConsulAgent
import org.http4s.Uri
import org.testcontainers.containers.wait.strategy.Wait

import scala.concurrent.duration.*

/** A throwaway dev-mode Consul agent in a container, on a random host port.
  */
object ConsulContainer:
  private val httpPort = 8500
  private val secondsToWaitForAgent = 120

  /** Starts an agent of `version`, with ACLs turned on by `aclConfig` if given,
    * and yields the base URI of its HTTP API.
    */
  def resource(version: ConsulVersion, aclConfig: Option[String]): Resource[IO, Uri] =
    Resource
      .make(IO.blocking:
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
        c.start()
        c)(c => IO.blocking(c.stop()))
      .map(c =>
        Uri(
          scheme = Uri.Scheme.http.some,
          authority = Uri
            .Authority(
              host = Uri.RegName(c.host),
              port = c.mappedPort(httpPort).some
            )
            .some
        )
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
