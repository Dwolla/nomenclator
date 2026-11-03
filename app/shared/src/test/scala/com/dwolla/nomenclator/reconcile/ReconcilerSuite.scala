package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.consul.agent.*
import com.dwolla.nomenclator.consul.agent.InMemoryConsulAgent.Write
import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.ecs.agent.*
import com.dwolla.nomenclator.ecs.api.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.HostIdentity
import com.dwolla.nomenclator.settings.ContainerSettings
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.*
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*

class ReconcilerSuite extends CatsEffectSuite:
  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val host = HostIdentity(ok(HostName.from("test-host")), "10.0.0.1")
  private val interval = 5.seconds
  private val passTimeout = 30.seconds
  private val address = "10.0.0.1"

  private val fooRef = TaskDefinitionRef("foo-service", 1)
  private val barRef = TaskDefinitionRef("bar-worker", 2)
  private val bazRef = TaskDefinitionRef("baz-api", 3)

  private def task(
                    name: String,
                    ref: TaskDefinitionRef,
                    dockerName: String,
                    containerPort: Int,
                    hostPort: Int,
                    desiredStatus: TaskStatus = TaskStatus.Running
  ): EcsTask =
    EcsTask(
      s"arn:aws:ecs:us-east-1:123456789012:task/example/$name",
      ref,
      TaskStatus.Running,
      desiredStatus,
      List(
        EcsContainer(
          ContainerName("web"),
          DockerName(dockerName),
          ImageName(s"registry.example.com/${ref.family}:1"),
          List(
            PortBinding(
              ok(ContainerPort.from(containerPort)),
              ok(HostPort.from(hostPort)),
              Protocol.Tcp
            )
          )
        )
      )
    )

  private val foo =
    task("foo", fooRef, "ecs-foo-service-1-web-aaaa", 8080, 32768)
  private val bar =
    task("bar", barRef, "ecs-bar-worker-2-web-bbbb", 9000, 32769)
  private val baz = task("baz", bazRef, "ecs-baz-api-3-web-cccc", 7000, 32770)

  private val fooId = ServiceId("test-host:ecs-foo-service-1-web-aaaa:8080")
  private val barId = ServiceId("test-host:ecs-bar-worker-2-web-bbbb:9000")
  private val bazId = ServiceId("test-host:ecs-baz-api-3-web-cccc:7000")

  /** Owned by this host, but no task asks for it.
    */
  private val orphan = AgentService(
    ServiceId("test-host:ecs-gone-1-web-dddd:8080"),
    ServiceName("gone"),
    Nil,
    address,
    32767
  )

  private def settings(env: (String, String)*) =
    Map(ContainerName("web") -> ContainerSettings.fromRaw(env.toMap, Map.empty))

  private val plain = settings()

  private def agentService(id: ServiceId, name: String, port: Int) =
    AgentService(id, ServiceName(name), Nil, address, port)

  private final case class World(
                                  ecs: InMemoryEcsAgent,
                                  defs: InMemoryTaskDefinitions,
                                  consul: InMemoryConsulAgent,
                                  logger: StructuredTestingLogger[IO]
  )

  private def world(
                     tasks: List[EcsTask],
                     known: Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]],
                     agent: AgentState = AgentState(Nil, Nil)
  ): IO[World] =
    (
      InMemoryEcsAgent(tasks),
      InMemoryTaskDefinitions(known),
      InMemoryConsulAgent(agent)
    ).mapN(World(_, _, _, StructuredTestingLogger.impl[IO]()))

  private def reconcile
    (w: World, mode: Mode = Mode.Apply, policy: FailurePolicy = FailurePolicy.default): Stream[IO, PassReport] =
    given StructuredLogger[IO] = w.logger
    given Tracer[IO] = Tracer.noop[IO]
    Reconciler.stream[IO](
      mode,
      interval,
      passTimeout,
      host,
      Nil,
      w.ecs,
      w.defs,
      w.consul,
      policy
    )

  /** Runs `n` passes. `between(i)` runs after pass `i` and before the next.
    */
  private def passes(w: World, n: Int, mode: Mode = Mode.Apply)
                    (between: Long => IO[Unit] = _ => IO.unit): IO[List[PassReport]] =
    reconcile(w, mode).zipWithIndex
      .evalTap((_, i) => between(i))
      .map(_._1)
      .take(n.toLong)
      .compile
      .toList

  private def described(writes: Vector[Write]): Vector[String] =
    writes.map:
      case Write.Register(r)    => s"register ${r.id.value}"
      case Write.Deregister(id) => s"deregister ${id.value}"

  private def warnings(logged: Vector[LogMessage]): Vector[LogMessage] =
    logged.collect:
      case m: WARN => m

  private def infos(logged: Vector[LogMessage]): Vector[LogMessage] =
    logged.collect:
      case m: INFO => m

  private def about(id: ServiceId)(m: LogMessage): Boolean =
    m.ctx.get("service_id").contains(id.value)

  private val introspectionDown = EcsAgentError("GET", "/v1/tasks", 500)

  test("a failed register doesn't stop the others, and is retried next pass"):
    val program = for
      w <- world(List(foo, bar), Map(fooRef -> plain, barRef -> plain))
      _ <- w.consul.failRegistrationsOf(Set(fooId))
      reports <- passes(w, 3)(i => w.consul.failRegistrationsOf(Set.empty).whenA(i == 0))
      writes <- w.consul.writes
      agent <- w.consul.current
      logged <- w.logger.logged
    yield (reports, writes, agent, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, agent, logged) =>
        assertEquals(
          described(writes),
          Vector(
            s"register ${fooId.value}",
            s"register ${barId.value}",
            s"register ${fooId.value}"
          )
        )
        assertEquals(agent.services.map(_.id).toSet, Set(fooId, barId))
        assertEquals(
          reports.map(_.outcome.map(_.failures.map(_._1))),
          List(Right(List(fooId)), Right(Nil), Right(Nil))
        )
        assertEquals(
          warnings(logged).filter(about(fooId)).size,
          1,
          logged.toString
        )
        assertEquals(
          infos(logged)
            .filter(about(fooId))
            .count(_.message.contains("succeeded after failing")),
          1,
          logged.toString
        )

  test("a skip is logged once across 10 passes"):
    val program = for
      w <- world(
        List(foo, bar, baz),
        Map(
          fooRef -> settings("SERVICE_CHECK_SCRIPT" -> "/bin/true"),
          barRef -> settings("SERVICE_IGNORE" -> "true"),
          bazRef -> settings(
            "SERVICE_CHECK_TCP" -> "true",
            "SERVICE_CHECK_INTERVAL" -> "soon"
          )
        )
      )
      _ <- passes(w, 10)()
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (writes, logged) =>
        assertEquals(writes, Vector.empty)
        assertEquals(
          warnings(logged).flatMap(_.ctx.get("reason")).toSet,
          Set("script_check", "invalid_settings"),
          logged.toString
        )
        assertEquals(
          infos(logged).flatMap(_.ctx.get("reason")),
          Vector("ignored"),
          logged.toString
        )
        assertEquals(logged.size, 3, logged.toString)

  test(
    "shadow mode writes nothing, and logs each difference when it appears and when it clears"
  ):
    val registrators = agentService(
      ServiceId("registrator-host:abc123:8080"),
      "foo-service",
      31000
    )
    val program = for
      w <- world(
        List(foo),
        Map(fooRef -> plain),
        AgentState(List(registrators, orphan), Nil)
      )
      reports <- passes(w, 10, Mode.Shadow)(i =>
        (w.ecs.setTasks(Nil) >> w.consul.seed(AgentState(Nil, Nil)))
          .whenA(i == 4)
      )
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (reports, writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, logged) =>
        assertEquals(writes, Vector.empty)
        assertEquals(
          reports.map(_.outcome.map(_.differences.size)),
          List.fill(5)(Right(3)) ++ List.fill(5)(Right(0))
        )
        assertEquals(
          infos(logged).count(_.message.contains("appeared")),
          3,
          logged.toString
        )
        assertEquals(
          infos(logged).count(_.message.contains("cleared")),
          3,
          logged.toString
        )
        assertEquals(logged.size, 6, logged.toString)

  test(
    "1 minute 59 seconds of failed introspection keeps going, writes nothing, and warns once"
  ):
    val program = for
      w <- world(Nil, Map.empty, AgentState(List(orphan), Nil))
      _ <- w.ecs.fail(introspectionDown)
      reports <- reconcile(w, policy = FailurePolicy.exitAfter(2.minutes))
        .interruptAfter(1.minute + 59.seconds)
        .compile
        .toList
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (reports, writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, logged) =>
        assertEquals(reports.size, 24)
        assert(reports.forall(_.outcome.isLeft), reports.toString)
        assertEquals(writes, Vector.empty)
        assertEquals(warnings(logged).size, 1, logged.toString)
        assertEquals(logged.size, 1, logged.toString)

  test(
    "2 minutes of failed introspection reports every pass, then ends the stream with an error"
  ):
    val program = for
      w <- world(Nil, Map.empty)
      _ <- w.ecs.fail(introspectionDown)
      start <- IO.monotonic
      emitted <- reconcile(
        w,
        policy = FailurePolicy.exitAfter(2.minutes)
      ).attempt.compile.toList
      end <- IO.monotonic
    yield (emitted, end - start)

    TestControl
      .executeEmbed(program)
      .map: (emitted, elapsed) =>
        assertEquals(elapsed, 2.minutes)
        assertEquals(emitted.count(_.isRight), 25)
        assertEquals(
          emitted.lastOption,
          Some(Left(ReconcileFailedTooLong(2.minutes)))
        )

  test("a successful pass restarts the failure clock"):
    // Passes run every 5 s: pass 18 (at 1m30s) succeeds and the rest fail, so
    // the limit is reached two minutes later, at 3m30s.
    val program = for
      w <- world(Nil, Map.empty)
      _ <- w.ecs.fail(introspectionDown)
      start <- IO.monotonic
      result <- reconcile(
        w,
        policy = FailurePolicy.exitAfter(2.minutes)
      ).zipWithIndex
        .evalTap: (_, i) =>
          if (i == 17) w.ecs.setTasks(Nil)
          else if (i == 18) w.ecs.fail(introspectionDown)
          else IO.unit
        .compile
        .drain
        .attempt
      end <- IO.monotonic
    yield (result, end - start)

    TestControl
      .executeEmbed(program)
      .map: (result, elapsed) =>
        assertEquals(result, Left(ReconcileFailedTooLong(2.minutes)))
        assertEquals(elapsed, 3.minutes + 30.seconds)

  test(
    "a failed definition lookup keeps that task's registrations and leaves other tasks' intact"
  ):
    val program = for
      w <- world(
        List(foo, bar),
        Map(fooRef -> plain),
        AgentState(List(agentService(barId, "bar-worker", 32769), orphan), Nil)
      )
      _ <- w.defs.fail(barRef, new RuntimeException("DB_PASSWORD=hunter2"))
      reports <- passes(w, 3)()
      writes <- w.consul.writes
      agent <- w.consul.current
      logged <- w.logger.logged
    yield (reports, writes, agent, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, agent, logged) =>
        assertEquals(
          described(writes),
          Vector(s"deregister ${orphan.id.value}", s"register ${fooId.value}")
        )
        assertEquals(agent.services.map(_.id).toSet, Set(barId, fooId))
        assertEquals(
          reports.map(_.outcome.map(_.desired.awaitingDefinitions)),
          List.fill(3)(Right(Set(barRef)))
        )
        assertEquals(warnings(logged).size, 1, logged.toString)
        assert(
          logged.forall(m => m.throwOpt.isEmpty && !(m.message + m.ctx).contains("hunter2")),
          logged.toString
        )

  test("definitions are looked up only for running tasks"):
    val stopping = bar.copy(desiredStatus = TaskStatus.Stopped)
    val program = for
      w <- world(
        List(foo, stopping),
        Map(fooRef -> plain),
        AgentState(List(agentService(barId, "bar-worker", 32769)), Nil)
      )
      reports <- passes(w, 1)()
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (reports, writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, logged) =>
        assertEquals(
          described(writes),
          Vector(s"deregister ${barId.value}", s"register ${fooId.value}")
        )
        assertEquals(
          reports.map(_.outcome.map(_.desired.awaitingDefinitions)),
          List(Right(Set.empty[TaskDefinitionRef]))
        )
        assertEquals(warnings(logged), Vector.empty)

  test(
    "apply deregisters before it registers, and logs each change with its kind"
  ):
    val program = for
      w <- world(
        List(foo, bar, baz),
        Map(fooRef -> plain, barRef -> plain, bazRef -> plain),
        AgentState(
          List(
            agentService(fooId, "foo-service", 32768),
            agentService(barId, "bar-worker", 1),
            orphan
          ),
          // foo no longer asks for a check, so it must be replaced
          List(AgentCheck(s"service:${fooId.value}", fooId.some, None))
        )
      )
      _ <- passes(w, 2)()
      writes <- w.consul.writes
      agent <- w.consul.current
      logged <- w.logger.logged
    yield (writes, agent, logged)

    TestControl
      .executeEmbed(program)
      .map: (writes, agent, logged) =>
        assertEquals(
          described(writes),
          Vector(
            s"deregister ${fooId.value}",
            s"deregister ${orphan.id.value}",
            s"register ${fooId.value}",
            s"register ${barId.value}",
            s"register ${bazId.value}"
          )
        )
        assertEquals(agent.checks, Nil)
        assertEquals(
          infos(logged).map(_.ctx),
          Vector(
            Map(
              "kind" -> "deregister",
              "service_id" -> orphan.id.value,
              "service_name" -> "gone",
              "address" -> address,
              "port" -> "32767"
            ),
            Map(
              "kind" -> "replace",
              "service_id" -> fooId.value,
              "service_name" -> "foo-service",
              "address" -> address,
              "port" -> "32768"
            ),
            Map(
              "kind" -> "update",
              "service_id" -> barId.value,
              "service_name" -> "bar-worker",
              "address" -> address,
              "port" -> "32769"
            ),
            Map(
              "kind" -> "register",
              "service_id" -> bazId.value,
              "service_name" -> "baz-api",
              "address" -> address,
              "port" -> "32770"
            )
          )
        )
        assertEquals(warnings(logged), Vector.empty)

  test(
    "a failed Consul read writes nothing, warns once, and logs its recovery"
  ):
    val program = for
      w <- world(List(foo), Map(fooRef -> plain))
      _ <- w.consul.failState(
        ConsulAgentError("GET", "/v1/agent/services", 500, "down").some
      )
      reports <- passes(w, 4)(i => w.consul.failState(None).whenA(i == 2))
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (reports, writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, logged) =>
        assertEquals(
          reports.map(_.outcome.isRight),
          List(false, false, false, true)
        )
        assertEquals(described(writes), Vector(s"register ${fooId.value}"))
        assertEquals(warnings(logged).size, 1, logged.toString)
        assertEquals(
          infos(logged).count(_.message.contains("recovered")),
          1,
          logged.toString
        )

  test("a desired registration whose ID isn't owned is logged once"):
    val unrecognizable = task("odd", fooRef, "odd~name", 8080, 32768)
    val program = for
      w <- world(List(unrecognizable), Map(fooRef -> plain))
      _ <- passes(w, 3)()
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (writes, logged) =>
        assertEquals(writes, Vector.empty)
        assertEquals(
          warnings(logged).flatMap(_.ctx.get("service_id")),
          Vector("test-host:odd~name:8080"),
          logged.toString
        )
        assertEquals(logged.size, 1, logged.toString)

  test("a requested custom service ID is logged once and not honored"):
    val program = for
      w <- world(List(foo), Map(fooRef -> settings("SERVICE_ID" -> "custom")))
      _ <- passes(w, 3)()
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (writes, logged) =>
        assertEquals(described(writes), Vector(s"register ${fooId.value}"))
        assertEquals(
          warnings(logged).map(_.ctx.get("container")),
          Vector(Some("web")),
          logged.toString
        )

  test(
    "a pass that never finishes times out, the next runs on schedule, and the failure limit still applies"
  ):
    val program = for
      w <- world(List(foo), Map(fooRef -> plain))
      _ <- w.ecs.hang
      start <- IO.monotonic
      emitted <- reconcile(
        w,
        policy = FailurePolicy.exitAfter(2.minutes)
      ).attempt.compile.toList
      end <- IO.monotonic
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (emitted, end - start, writes, logged)

    TestControl
      .executeEmbed(program)
      .map: (emitted, elapsed, writes, logged) =>
        // Passes start at 0, 30 s, 1m, and 1m30s, each as soon as the one
        // before it timed out, and the fourth reaches the limit at 2m.
        assertEquals(
          emitted.collect:
            case Right(r) => (r.duration, r.outcome)
          ,
          List.fill(4)((passTimeout, Left(PassTimedOut(passTimeout))))
        )
        assertEquals(
          emitted.lastOption,
          Some(Left(ReconcileFailedTooLong(2.minutes)))
        )
        assertEquals(elapsed, 2.minutes)
        assertEquals(writes, Vector.empty)
        assertEquals(warnings(logged).size, 1, logged.toString)
        assertEquals(logged.size, 1, logged.toString)

  test(
    "writes made before a pass times out stay done, and the rest are made next pass"
  ):
    val program = for
      w <- world(List(foo, bar), Map(fooRef -> plain, barRef -> plain))
      _ <- w.consul.hangRegistrationsOf(Set(barId))
      reports <- passes(w, 2)(i => w.consul.hangRegistrationsOf(Set.empty).whenA(i == 0))
      writes <- w.consul.writes
      agent <- w.consul.current
      logged <- w.logger.logged
    yield (reports, writes, agent, logged)

    TestControl
      .executeEmbed(program)
      .map: (reports, writes, agent, logged) =>
        assertEquals(
          reports.map(_.outcome.map(_.plan.register.map(_.id))),
          List(Left(PassTimedOut(passTimeout)), Right(List(barId)))
        )
        assertEquals(
          described(writes),
          Vector(
            s"register ${fooId.value}",
            s"register ${barId.value}",
            s"register ${barId.value}"
          )
        )
        assertEquals(agent.services.map(_.id).toSet, Set(fooId, barId))
        assertEquals(warnings(logged).size, 1, logged.toString)
        assertEquals(
          infos(logged).count(_.message.contains("in time again")),
          1,
          logged.toString
        )
end ReconcilerSuite
