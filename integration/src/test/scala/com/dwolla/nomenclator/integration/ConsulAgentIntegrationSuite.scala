package com.dwolla.nomenclator.integration

import cats.effect.*
import com.comcast.ip4s.*
import com.dimafeng.testcontainers.GenericContainer
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.consul.agent.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.testcontainers.containers.wait.strategy.Wait

import scala.concurrent.duration.*

/** Checks the agent client against real Consul agents, and pins the agent
  * behavior the planner relies on. Each test gets its own throwaway agent.
  */
class ConsulAgentIntegrationSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  private val httpPort = 8500
  private val secondsToWaitForAgent = 120

  private final case class Running(client: Client[IO], base: Uri, agent: ConsulAgent[IO], token: Option[String])

  private def container(version: ConsulVersion, aclConfig: Option[String]): Resource[IO, GenericContainer] =
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
      c.start()
      c)(c => IO.blocking(c.stop()))

  private def running(version: ConsulVersion, aclConfig: Option[String], token: Option[String]): Resource[IO, Running] =
    for
      c <- container(version, aclConfig)
      client <- EmberClientBuilder.default[IO].build
      base = Uri(
        scheme = Some(Uri.Scheme.http),
        authority = Some(
          Uri.Authority(
            host = Uri.RegName(c.host),
            port = Some(c.mappedPort(httpPort))
          )
        )
      )
    yield Running(client, base, Http4sConsulAgent(client, base, token), token)

  /** Right after startup an ACL-enabled agent can still be creating its
    * management token, so wait until it answers an authenticated request.
    */
  private def awaitReady(r: Running): IO[Running] =
    def attempt(remaining: Int): IO[Unit] =
      r.agent.state.void.handleErrorWith:
        case _ if remaining > 0 =>
          IO.sleep(500.millis) >> attempt(remaining - 1)
        case e => IO.raiseError(e)
    attempt(secondsToWaitForAgent * 2).as(r)

  private def hostPort(n: Int): HostPort =
    HostPort.from(n).fold(fail(_), identity)

  private def registration(id: String, check: Option[Check] = None, tags: List[Tag] = Nil): Registration =
    Registration(
      ServiceId(id),
      ServiceName("foo-service"),
      tags,
      "10.0.0.1",
      hostPort(32768),
      check
    )

  private val httpCheck =
    Check.Http("http://10.0.0.1:32768/health", 5.seconds, Some(1.second))
  private val tcpCheck = Check.Tcp("10.0.0.1:32768", 7.seconds, None)

  private def checksOf(state: AgentState, id: String): List[AgentCheck] =
    state.checks.filter(_.serviceId.contains(ServiceId(id)))

  private def checkIds(state: AgentState): Set[String] =
    state.checks.map(_.checkId).toSet

  private def enableMaintenance(r: Running, id: String): IO[Unit] =
    r.client
      .status(
        Request[IO](
          Method.PUT,
          (r.base / "v1" / "agent" / "service" / "maintenance" / id)
            .withQueryParam("enable", "true")
        )
      )
      .flatMap(s => IO(assert(s.isSuccess, s"maintenance answered $s")))

  ConsulVersions.all.foreach: version =>
    val agentFixture =
      ResourceFunFixture(
        running(version, None, None).evalMap(awaitReady)
      )
    val aclFixture = ResourceFunFixture(
      running(
        version,
        Some(version.aclConfig),
        Some(ConsulVersion.managementToken)
      ).evalMap(awaitReady)
    )
    val aclWithoutTokenFixture = ResourceFunFixture(
      running(
        version,
        Some(version.aclConfig),
        Some(ConsulVersion.managementToken)
      ).evalMap(awaitReady)
        .map: r =>
          r.copy(agent = Http4sConsulAgent(r.client, r.base, None), token = None)
    )
    val aclWrongTokenFixture = ResourceFunFixture(
      running(
        version,
        Some(version.aclConfig),
        Some(ConsulVersion.managementToken)
      ).evalMap(awaitReady)
        .map: r =>
          r.copy(agent = Http4sConsulAgent(r.client, r.base, Some(wrongToken)))
    )

    agentFixture.test(s"${version.label}: the advertised address is an IP"): r =>
      r.agent.advertisedAddress.map: address =>
        assert(
          IpAddress.fromString(address).isDefined,
          s"not an IP: $address"
        )

    agentFixture.test(
      s"${version.label}: a registered HTTP check is listed as service:<id>"
    ): r =>
      val reg = registration(
        "h:web:80",
        Some(httpCheck),
        List(Tag("a"), Tag("b"))
      )
      for
        _ <- r.agent.register(reg)
        state <- r.agent.state
      yield
        assertEquals(
          state.services,
          List(
            AgentService(
              reg.id,
              reg.name,
              reg.tags,
              reg.address,
              32768
            )
          )
        )
        assertEquals(
          checksOf(state, "h:web:80").map(_.checkId),
          List("service:h:web:80")
        )
      end for

    agentFixture.test(
      s"${version.label}: a registered TCP check is listed as service:<id>"
    ): r =>
      for
        _ <- r.agent.register(registration("h:db:81", Some(tcpCheck)))
        state <- r.agent.state
      yield assertEquals(
        checksOf(state, "h:db:81").map(_.checkId),
        List("service:h:db:81")
      )

    agentFixture.test(
      s"${version.label}: check definitions are reported only if the version is known to"
    ): r =>
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        state <- r.agent.state
      yield assertEquals(
        checksOf(state, "h:web:80").flatMap(_.definition).nonEmpty,
        version.reportsCheckDefinition
      )

    agentFixture.test(s"${version.label}: no check registers no check"): r =>
      for
        _ <- r.agent.register(registration("h:web:80"))
        state <- r.agent.state
      yield
        assertEquals(state.services.map(_.id), List(ServiceId("h:web:80")))
        assertEquals(checksOf(state, "h:web:80"), Nil)

    agentFixture.test(
      s"${version.label}: deregister removes service and check"
    ): r =>
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        _ <- r.agent.deregister(ServiceId("h:web:80"))
        state <- r.agent.state
      yield
        assertEquals(state.services, Nil)
        assertEquals(state.checks, Nil)

    agentFixture.test(
      s"${version.label}: deregistering an unknown ID succeeds"
    ): r =>
      r.agent.deregister(ServiceId("h:nope:1"))

    agentFixture.test(
      s"${version.label}: re-registering with a different check keeps exactly one service:<id> check"
    ): r =>
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        _ <- r.agent.register(registration("h:web:80", Some(tcpCheck)))
        state <- r.agent.state
      yield assertEquals(
        checksOf(state, "h:web:80").map(_.checkId),
        List("service:h:web:80")
      )

    // The planner must deregister before registering when a service loses its
    // check: re-registering without one leaves the old check running.
    agentFixture.test(
      s"${version.label}: re-registering without a check does not remove the old check"
    ): r =>
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        _ <- r.agent.register(registration("h:web:80"))
        state <- r.agent.state
      yield assertEquals(
        checksOf(state, "h:web:80").map(_.checkId),
        List("service:h:web:80")
      )

    agentFixture.test(
      s"${version.label}: maintenance checks survive re-registration and go with deregistration"
    ): r =>
      val expected = Set("service:h:web:80", "_service_maintenance:h:web:80")
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        _ <- enableMaintenance(r, "h:web:80")
        afterMaintenance <- r.agent.state
        _ <- r.agent.register(registration("h:web:80", Some(tcpCheck)))
        afterReregistration <- r.agent.state
        _ <- r.agent.deregister(ServiceId("h:web:80"))
        afterDeregistration <- r.agent.state
      yield
        assertEquals(checkIds(afterMaintenance), expected)
        assertEquals(checkIds(afterReregistration), expected)
        assertEquals(checkIds(afterDeregistration), Set.empty[String])
        // The maintenance check belongs to the service but is not the
        // registration's own check.
        assertEquals(
          checksOf(afterReregistration, "h:web:80").map(_.checkId).toSet,
          expected
        )
      end for

    aclFixture.test(s"${version.label}: with ACLs, a token may register"): r =>
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        state <- r.agent.state
      yield assertEquals(
        state.services.map(_.id),
        List(ServiceId("h:web:80"))
      )

    aclWithoutTokenFixture.test(
      s"${version.label}: with ACLs, registering without a token is forbidden"
    ): r =>
      r.agent
        .register(registration("h:web:80"))
        .attempt
        .map:
          case Left(e: ConsulAgentError) =>
            assertEquals(e.status, 403)
            assertEquals(e.method, "PUT")
            assertEquals(e.path, "/v1/agent/service/register")
          case other => fail(s"expected a 403, got $other")

    aclWrongTokenFixture.test(
      s"${version.label}: a rejected token never appears in the error"
    ): r =>
      r.agent
        .register(registration("h:web:80"))
        .attempt
        .map:
          case Left(e: ConsulAgentError) =>
            assertEquals(e.status, 403)
            assert(!e.getMessage.contains(wrongToken), e.getMessage)
            assert(!e.bodyExcerpt.contains(wrongToken), e.bodyExcerpt)
          case other => fail(s"expected a 403, got $other")

  private val wrongToken = "a-rejected-token-value"
end ConsulAgentIntegrationSuite
