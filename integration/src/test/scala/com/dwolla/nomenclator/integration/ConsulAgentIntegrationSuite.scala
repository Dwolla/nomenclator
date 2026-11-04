package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import com.dwolla.nomenclator.consul.algebra.*
import com.dwolla.nomenclator.consul.http4s.*
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.model.*
import com.dwolla.nomenclator.plan.CheckComparison
import com.dwolla.nomenclator.registration.*
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.*

import scala.concurrent.duration.*

/** Checks the agent client against real Consul agents, and pins the agent
  * behavior the planner relies on. Each test gets its own throwaway agent.
  */
class ConsulAgentIntegrationSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  private final case class Running(client: Client[IO], base: Uri, agent: ConsulAgent[IO], token: Option[String])

  private def running(version: ConsulVersion, aclConfig: Option[String], token: Option[String]): Resource[IO, Running] =
    for
      base <- ConsulContainer.resource(version, aclConfig)
      client <- EmberClientBuilder.default[IO].build
    yield Running(client, base, Http4sConsulAgent(client, base, token), token)

  private def awaitReady(r: Running): IO[Running] =
    ConsulContainer.awaitReady(r.agent).as(r)

  private def hostPort(n: Int): HostPort =
    HostPort.from(n).fold(fail(_), identity)

  private def registration(id: String, check: Option[Check] = None, tags: List[Tag] = Nil): Registration =
    Registration(
      ServiceId(id),
      ServiceName("foo-service"),
      tags,
      ServiceAddress("10.0.0.1"),
      hostPort(32768),
      check
    )

  private val httpCheck =
    Check.Http("http://10.0.0.1:32768/health", 5.seconds, Some(1.second))
  private val tcpCheck = Check.Tcp("10.0.0.1:32768", 7.seconds, None)

  private def checksOf(state: AgentState, id: String): List[AgentCheck] =
    state.checks.filter(_.serviceId.contains(ServiceId(id)))

  private def checkIds(state: AgentState): Set[String] =
    state.checks.map(_.checkId.value).toSet

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
          IpAddress.fromString(address.value).isDefined,
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
          checksOf(state, "h:web:80").map(_.checkId.value),
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
        checksOf(state, "h:db:81").map(_.checkId.value),
        List("service:h:db:81")
      )

    agentFixture.test(
      s"${version.label}: a check's type and timing are reported only if the version is known to"
    ): r =>
      for
        _ <- r.agent.register(registration("h:web:80", Some(httpCheck)))
        _ <- r.agent.register(registration("h:db:81", Some(tcpCheck)))
        state <- r.agent.state
      yield
        assertEquals(
          checksOf(state, "h:web:80").map(_.definition),
          List(
            Option.when(version.reportsCheckTiming)(
              CheckDefinition(Some("http"), None, None, Some("5s"), Some("1s"))
            )
          )
        )
        assertEquals(
          checksOf(state, "h:db:81").map(_.definition),
          List(
            Option.when(version.reportsCheckTiming)(
              CheckDefinition(Some("tcp"), None, None, Some("7s"), None)
            )
          )
        )

    agentFixture.test(
      s"${version.label}: the planner sees a changed interval only if the version reports timing"
    ): r =>
      val id = ServiceId("h:web:80")
      for
        _ <- r.agent.register(registration(id.value, Some(httpCheck)))
        state <- r.agent.state
      yield
        val reported = CheckComparison.registrationChecks(state).get(id)
        assert(CheckComparison.unchanged(Some(httpCheck), reported))
        assertEquals(
          CheckComparison.unchanged(
            Some(
              Check
                .Http("http://10.0.0.1:32768/health", 6.seconds, Some(1.second))
            ),
            reported
          ),
          !version.reportsCheckTiming
        )
      end for

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
        checksOf(state, "h:web:80").map(_.checkId.value),
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
        checksOf(state, "h:web:80").map(_.checkId.value),
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
          checksOf(afterReregistration, "h:web:80").map(_.checkId.value).toSet,
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

    aclFixture.test(
      s"${version.label}: a token that can't read every service fails the read only if the version says so"
    ): r =>
      for
        _ <- r.agent.register(registration("h:web:80"))
        _ <- r.agent.register(
          registration("h:db:81").copy(name = ServiceName("bar-worker"))
        )
        limited <- readOnlyFooToken(version, r)
        read <- Http4sConsulAgent(r.client, r.base, Some(limited)).state.attempt
      yield read match
        case Left(e: ConsulResultsFilteredError) =>
          assert(version.reportsAclFiltering, e.getMessage)
          assertEquals(e.path, "/v1/agent/services")
        case Right(state) =>
          assert(!version.reportsAclFiltering, s"read succeeded: $state")
          assertEquals(state.services.map(_.id), List(ServiceId("h:web:80")))
        case Left(other) => fail(s"unexpected failure: $other")

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

  /** A token that may read the `foo-service` service and nothing else.
    */
  private def readOnlyFooToken(version: ConsulVersion, r: Running): IO[String] =
    val rules = """service "foo-service" { policy = "read" }"""
    def put(path: Uri, body: Json): IO[Json] =
      r.client.expect[Json](
        Request[IO](Method.PUT, path)
          .putHeaders(
            Header.Raw(ci"X-Consul-Token", ConsulVersion.managementToken)
          )
          .withEntity(body)
      )
    if (version.legacyAcls)
      put(
        r.base / "v1" / "acl" / "create",
        Json.obj(
          "Name" -> "foo-only".asJson,
          "Type" -> "client".asJson,
          "Rules" -> rules.asJson
        )
      ).flatMap(_.hcursor.get[String]("ID").liftTo[IO])
    else
      put(
        r.base / "v1" / "acl" / "policy",
        Json.obj("Name" -> "foo-only".asJson, "Rules" -> rules.asJson)
      ) >> put(
        r.base / "v1" / "acl" / "token",
        Json.obj("Policies" -> Json.arr(Json.obj("Name" -> "foo-only".asJson)))
      ).flatMap(_.hcursor.get[String]("SecretID").liftTo[IO])
  end readOnlyFooToken
end ConsulAgentIntegrationSuite
