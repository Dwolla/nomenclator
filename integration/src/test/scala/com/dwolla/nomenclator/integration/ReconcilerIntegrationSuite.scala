package com.dwolla.nomenclator.integration

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.algebra.*
import com.dwolla.nomenclator.consul.http4s.*
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.algebra.*
import com.dwolla.nomenclator.ecs.http4s.*
import com.dwolla.nomenclator.ecs.model.*
import com.dwolla.nomenclator.plan.Plan
import com.dwolla.nomenclator.reconcile.*
import com.dwolla.nomenclator.registration.*
import com.dwolla.nomenclator.registration.settings.ContainerSettings
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

  import FixtureServices.{address, barTcp, barUdp, fooWeb}

  private val host = HostIdentity(FixtureServices.hostName, address)

  private val fooRef = TaskDefinitionRef(TaskFamily("foo-service"), 3)
  private val barRef = TaskDefinitionRef(TaskFamily("bar-worker"), 12)
  private val bazRef = TaskDefinitionRef(TaskFamily("baz-api"), 7)
  private val barTask =
    TaskArn(
      "arn:aws:ecs:us-east-1:123456789012:task/example/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    )

  // An empty and a duplicate tag: registrator sends them as they are.
  private val fooTags = List(Tag("a"), Tag(""), Tag("a"))

  private val fixtureServices = List(
    AgentService(
      fooWeb,
      ServiceName("foo-service"),
      fooTags,
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

  private val fooEnvironment = Map(
    "SERVICE_TAGS" -> "a,,a",
    "SERVICE_CHECK_HTTP" -> "/health",
    "SERVICE_CHECK_INTERVAL" -> "1m30s",
    "SERVICE_CHECK_TIMEOUT" -> "2s"
  )
  private val fooSettings = Map(
    ContainerName("web") -> ContainerSettings.fromRaw(fooEnvironment, Map.empty)
  )
  // The TCP port is checked; the UDP port isn't.
  private val barSettings = Map(
    ContainerName("worker") -> ContainerSettings.fromRaw(
      Map(
        "SERVICE_9000_CHECK_TCP" -> "true",
        "SERVICE_9000_CHECK_INTERVAL" -> "7s"
      ),
      Map.empty
    )
  )
  // baz-api runs in awsvpc mode, so it registers nothing whatever it asks.
  private val bazSettings = Map(
    ContainerName("api") -> ContainerSettings.fromRaw(
      Map("SERVICE_CHECK_HTTP" -> "/health"),
      Map.empty
    )
  )
  private val definitions = Map(
    fooRef -> fooSettings,
    barRef -> barSettings,
    bazRef -> bazSettings
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
          state.checks
            .filter(_.serviceId.exists(Set(fooWeb, barTcp, barUdp)))
            .map(_.checkId.value)
            .sorted,
          List(s"service:${barTcp.value}", s"service:${fooWeb.value}")
        )

    fixture.test(
      s"${version.label}: after the first pass, passes plan nothing"
    ): r =>
      passes(r, definitions, 3)().map: reports =>
        assertEveryPassSucceeded(reports)
        assertEquals(
          reports.map(_.outcome.map(_.plan.register.map(_.id).toSet)),
          List(
            Right(fixtureServices.map(_.id).toSet),
            Right(Set.empty),
            Right(Set.empty)
          )
        )
        assertEquals(
          reports.drop(1).map(_.outcome.map(_.plan)),
          List.fill(2)(Right(Plan(Nil, Nil)))
        )

    // A changed definition can't keep its revision on a real host, but it is
    // the way to make the agent's service lose its check under the same ID.
    fixture.test(
      s"${version.label}: a check removed from the definition is replaced, then nothing is planned"
    ): r =>
      val withoutCheck = definitions.updated(
        fooRef,
        Map(
          ContainerName("web") -> ContainerSettings.fromRaw(
            Map("SERVICE_TAGS" -> "a,,a"),
            Map.empty
          )
        )
      )
      for
        _ <- passes(r, definitions, 1)()
        reports <- passes(r, withoutCheck, 2)()
        state <- r.consul.state
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(
          reports.map(_.outcome.map(_.writes.map(w => (w.kind, w.id)))),
          List(Right(List((ChangeKind.Replace, fooWeb))), Right(Nil))
        )
        assertEquals(
          state.checks.filter(_.serviceId.contains(fooWeb)),
          Nil
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
        reports <- passes(r, definitions - barRef, 2)()
        registered <- services(r)
      yield
        assertEveryPassSucceeded(reports)
        assertEquals(
          reports.map(_.outcome.map(_.desired.awaitingDefinitions)),
          List.fill(2)(Right(Set(barRef)))
        )
        assertEquals(registered, fixtureServices.sortBy(_.id.value))
end ReconcilerIntegrationSuite
