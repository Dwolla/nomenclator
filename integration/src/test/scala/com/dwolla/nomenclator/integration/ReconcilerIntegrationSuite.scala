package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.consul.agent.*
import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.ecs.agent.*
import com.dwolla.nomenclator.ecs.api.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.reconcile.*
import com.dwolla.nomenclator.registration.*
import com.dwolla.nomenclator.settings.ContainerSettings
import munit.CatsEffectSuite
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.noop.NoOpLogger
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

/** Runs the reconcile loop in apply mode against a real Consul agent and a fake
  * ECS agent serving the introspection fixtures. Task definitions come from
  * memory. Each test gets its own throwaway agents.
  */
class ReconcilerIntegrationSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 3.minutes

  private val address = "10.0.0.1"
  private val host = HostIdentity(
    HostName.from("test-host").fold(fail(_), identity),
    address
  )

  private val fooRef = TaskDefinitionRef("foo-service", 3)
  private val barRef = TaskDefinitionRef("bar-worker", 12)
  private val barTask =
    "arn:aws:ecs:us-east-1:123456789012:task/example/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  private val fooWeb =
    ServiceId("test-host:ecs-foo-service-3-web-a1b2c3d4e5f6a7b8c9d0:8080")
  private val barTcp =
    ServiceId("test-host:ecs-bar-worker-12-worker-0a1b2c3d4e5f6a7b8c9d:9000")
  private val barUdp =
    ServiceId(
      "test-host:ecs-bar-worker-12-worker-0a1b2c3d4e5f6a7b8c9d:9001:udp"
    )

  private val fixtureServices = List(
    AgentService(
      fooWeb,
      ServiceName("foo-service"),
      List(Tag("web")),
      address,
      32768
    ),
    AgentService(barTcp, ServiceName("bar-worker-9000"), Nil, address, 32770),
    AgentService(
      barUdp,
      ServiceName("bar-worker-9001"),
      List(Tag("udp")),
      address,
      32771
    )
  )

  private val fooSettings = Map(
    ContainerName("web") -> ContainerSettings.fromRaw(
      Map("SERVICE_TAGS" -> "web", "SERVICE_CHECK_TCP" -> "true"),
      Map.empty
    )
  )
  private val definitions = Map(
    fooRef -> fooSettings,
    barRef -> Map.empty[ContainerName, ContainerSettings]
  )

  private final case class Running(ecs: FakeEcsAgent, introspection: EcsAgentIntrospection[IO], consul: ConsulAgent[IO])

  private def running(version: ConsulVersion): Resource[IO, Running] =
    for
      consulBase <- ConsulContainer.resource(version, None)
      ecs <- FakeEcsAgent.resource
      client <- EmberClientBuilder.default[IO].build
      consul = Http4sConsulAgent(client, consulBase, None)
      _ <- ConsulContainer.awaitReady(consul).toResource
    yield Running(ecs, Http4sEcsAgentIntrospection(client, ecs.base), consul)

  /** Runs `n` passes. `between(i)` runs after pass `i` and before the next.
    */
  private def passes(r: Running, known: Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]], n: Int)
                    (between: Long => IO[Unit] = _ => IO.unit): IO[List[PassReport]] =
    given StructuredLogger[IO] = NoOpLogger[IO]
    given Tracer[IO] = Tracer.noop[IO]
    InMemoryTaskDefinitions(known).flatMap: defs =>
      Reconciler
        .stream[IO](
          Mode.Apply,
          100.millis,
          30.seconds,
          5.seconds,
          host,
          Nil,
          r.introspection,
          defs,
          r.consul,
          FailurePolicy.default
        )
        .zipWithIndex
        .evalTap((_, i) => between(i))
        .map(_._1)
        .take(n.toLong)
        .compile
        .toList
  end passes

  private def assertEveryPassSucceeded(reports: List[PassReport]): Unit =
    assertEquals(
      reports.map(_.outcome.map(_.failures)),
      reports.map(_ => Right(Nil))
    )

  /** The agent's services other than Consul's own.
    */
  private def services(r: Running): IO[List[AgentService]] =
    r.consul.state.map(
      _.services.filterNot(_.id == ServiceId("consul")).sortBy(_.id.value)
    )

  private def seed(r: Running, id: String): IO[Unit] =
    r.consul.register(
      Registration(
        ServiceId(id),
        ServiceName("seeded"),
        Nil,
        address,
        HostPort.from(31000).fold(fail(_), identity),
        None
      )
    )

  ConsulVersions.all.foreach: version =>
    val fixture = ResourceFunFixture(running(version))

    fixture.test(s"${version.label}: apply registers the fixture's services"): r =>
      for
        reports <- passes(r, definitions, 1)()
        registered <- services(r)
        state <- r.consul.state
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(registered, fixtureServices.sortBy(_.id.value))
        assertEquals(
          state.checks.filter(_.serviceId.contains(fooWeb)).map(_.checkId),
          List(s"service:${fooWeb.value}")
        )

    fixture.test(
      s"${version.label}: removing a task from the fixture deregisters it on the next pass"
    ): r =>
      for
        reports <- passes(r, definitions, 2)(i => r.ecs.removeTask(barTask).whenA(i == 0))
        registered <- services(r)
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(registered.map(_.id), List(fooWeb))

    fixture.test(
      s"${version.label}: an owned orphan seeded before start is deregistered on the first pass"
    ): r =>
      for
        _ <- seed(r, "test-host:ecs-gone-1-web-0123456789abcdef:8080")
        reports <- passes(r, definitions, 1)()
        registered <- services(r)
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(registered, fixtureServices.sortBy(_.id.value))

    fixture.test(
      s"${version.label}: seeded services that aren't owned survive"
    ): r =>
      val notOwned = List(
        ServiceId("other-host:ecs-foo-service-3-web-0123456789abcdef:8080"),
        ServiceId("foo-service")
      )
      for
        _ <- notOwned.traverse_(id => seed(r, id.value))
        reports <- passes(r, definitions, 2)()
        registered <- services(r)
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(
          registered.map(_.id).toSet,
          fixtureServices.map(_.id).toSet ++ notOwned
        )

    fixture.test(
      s"${version.label}: after a restart, a task awaiting its definition keeps its registrations"
    ): r =>
      for
        _ <- passes(r, definitions, 1)()
        // The next run can't look up bar-worker's definition.
        reports <- passes(r, Map(fooRef -> fooSettings), 2)()
        registered <- services(r)
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(
          reports.map(_.outcome.map(_.desired.awaitingDefinitions)),
          List.fill(2)(Right(Set(barRef)))
        )
        assertEquals(registered, fixtureServices.sortBy(_.id.value))
end ReconcilerIntegrationSuite
