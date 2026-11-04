package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.algebra.InMemoryConsulAgent.Attempt
import com.dwolla.nomenclator.consul.algebra.*
import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.algebra.*
import com.dwolla.nomenclator.ecs.model.*
import com.dwolla.nomenclator.plan.Difference
import com.dwolla.nomenclator.registration.*
import com.dwolla.nomenclator.registration.settings.ContainerSettings
import fs2.Stream
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.*
import org.typelevel.otel4s.trace.Tracer
import smithy4s.http.UnknownErrorResponse

import java.util.Base64
import scala.concurrent.duration.*

class ReconcilerSuite extends CatsEffectSuite:
  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val host =
    HostIdentity(ok(HostName.from("test-host")), ServiceAddress("10.0.0.1"))
  private val interval = 5.seconds
  private val passTimeout = 30.seconds
  private val lookupTimeout = 5.seconds
  private val address = ServiceAddress("10.0.0.1")

  private val fooRef = TaskDefinitionRef(TaskFamily("foo-service"), 1)
  private val barRef = TaskDefinitionRef(TaskFamily("bar-worker"), 2)
  private val bazRef = TaskDefinitionRef(TaskFamily("baz-api"), 3)

  private def container(name: String, dockerName: String, containerPort: Int, hostPort: Int): EcsContainer =
    EcsContainer(
      ContainerName(name),
      DockerName(dockerName),
      ImageName(s"registry.example.com/$name:1"),
      List(
        PortBinding(
          ok(ContainerPort.from(containerPort)),
          ok(HostPort.from(hostPort)),
          Protocol.Tcp
        )
      )
    )

  private def taskOf(
                      name: String,
                      ref: TaskDefinitionRef,
                      containers: List[EcsContainer],
                      desiredStatus: TaskStatus = TaskStatus.Running
  ): EcsTask =
    EcsTask(
      TaskArn(s"arn:aws:ecs:us-east-1:123456789012:task/example/$name"),
      ref,
      TaskStatus.Running,
      desiredStatus,
      containers
    )

  /** A task with one container, `web`, whose image is named after `ref`.
    */
  private def task(
                    name: String,
                    ref: TaskDefinitionRef,
                    dockerName: String,
                    containerPort: Int,
                    hostPort: Int,
                    desiredStatus: TaskStatus = TaskStatus.Running
  ): EcsTask =
    taskOf(
      name,
      ref,
      List(
        container("web", dockerName, containerPort, hostPort)
          .copy(image = ImageName(s"registry.example.com/${ref.family}:1"))
      ),
      desiredStatus
    )

  /** `n` tasks, `hung-1` to `hung-n`, each of its own definition.
    */
  private def hungTasks(n: Int): List[EcsTask] =
    (1 to n).toList.map(i =>
      task(
        s"hung-$i",
        TaskDefinitionRef(TaskFamily(s"hung-$i"), 1),
        s"ecs-hung-$i-1-web-ffff",
        8000 + i,
        33000 + i
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
      lookupTimeout,
      host,
      Nil,
      w.ecs,
      w.defs,
      w.consul,
      policy
    )
  end reconcile

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

  /** A TestControl seed: the base64 of a decimal Long, as TestContext expects.
    * Fibers that wake at the same instant run in an order the seed picks.
    */
  private def seed(n: Long): String =
    Base64.getEncoder.encodeToString(n.toString.getBytes)

  /** Runs `program` under one fixed TestControl seed, so fibers that wake at
    * the same instant run in the same order every run.
    */
  private def underFixedSeed[A](program: IO[A]): IO[A] =
    TestControl.executeEmbed(program, seed = seed(1).some)

  /** Runs `program` under 100 fixed TestControl seeds, so a timing property is
    * checked against many orderings, the same ones every run. Each result comes
    * with its seed, for a failing assertion's clue to name.
    */
  private def underManySeeds[A](program: IO[A]): IO[List[(String, A)]] =
    (1L to 100L).toList.traverse(n => TestControl.executeEmbed(program, seed = seed(n).some).tupleLeft(seed(n)))

  private def described(writes: Vector[Attempt]): Vector[String] =
    writes.map:
      case Attempt.Register(r)    => s"register ${r.id.value}"
      case Attempt.Deregister(id) => s"deregister ${id.value}"

  /** What each write of a pass did, and whether it succeeded.
    */
  private def kinds(report: PassReport): Either[Throwable, List[String]] =
    report.outcome.map(
      _.writes.map(w => s"${w.kind.value} ${w.id.value} ${w.result.fold(_ => "failed", _ => "ok")}")
    )

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

    underFixedSeed(program).map: (reports, writes, agent, logged) =>
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
        reports.map(_.outcome.map(_.failures.map(_.id))),
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

    underFixedSeed(program).map: (writes, logged) =>
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

    underFixedSeed(program).map: (reports, writes, logged) =>
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

    underFixedSeed(program).map: (reports, writes, logged) =>
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

    underFixedSeed(program).map: (emitted, elapsed) =>
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

    underFixedSeed(program).map: (result, elapsed) =>
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

    underFixedSeed(program).map: (reports, writes, agent, logged) =>
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

  test(
    "an unrecognized AWS error answer is logged with its HTTP status but not its body"
  ):
    val program = for
      w <- world(
        List(foo, bar),
        Map(fooRef -> plain),
        AgentState(List(agentService(barId, "bar-worker", 32769)), Nil)
      )
      _ <- w.defs.fail(
        barRef,
        UnknownErrorResponse(400, Map.empty, "DB_PASSWORD=hunter2")
      )
      _ <- passes(w, 1)()
      logged <- w.logger.logged
    yield logged

    underFixedSeed(program).map: logged =>
      assertEquals(
        warnings(logged).flatMap(_.ctx.get("status")),
        Vector("400"),
        logged.toString
      )
      assert(
        !logged.exists(m => (m.message + m.ctx).contains("hunter2")),
        logged.toString
      )

  test(
    "each pass keeps only the cached definitions of the tasks the agent reports"
  ):
    val stopping = bar.copy(desiredStatus = TaskStatus.Stopped)
    val program = for
      w <- world(List(foo, stopping), Map(fooRef -> plain))
      _ <- passes(w, 3)(i =>
        w.ecs.fail(introspectionDown).whenA(i == 0) >>
          w.ecs.setTasks(List(foo)).whenA(i == 1)
      )
      retained <- w.defs.retained
    yield retained

    // The failed read in the second pass forgets nothing.
    underFixedSeed(program)
      .map(assertEquals(_, Vector(Set(fooRef, barRef), Set(fooRef))))

  test(
    "definitions that failed are looked up last, so hung ones can't starve the rest"
  ):
    // Twelve hung lookups hold all four slots past the lookup phase's
    // deadline, so a definition listed after them never starts.
    val hung = hungTasks(12)
    val program = for
      w <- world(hung :+ foo, Map(fooRef -> plain))
      _ <- hung.traverse_(t => w.defs.hang(t.definition))
      reports <- passes(w, 2)()
    yield reports

    underFixedSeed(program).map: reports =>
      assertEquals(
        reports.map(_.outcome.map(_.plan.register.map(_.id))),
        List(Right(Nil), Right(List(fooId)))
      )

  test(
    "a new definition is looked up even when more hang than a pass can start"
  ):
    // With every slot hung, a pass gives exactly 8 lookups a full turn and
    // starts 12: four slots, each held for lookupTimeout, until the phase's
    // deadline at two and a half times that cuts off the third round.
    val hung = hungTasks(24)
    val program = for
      w <- world(hung :+ foo, Map(fooRef -> plain))
      _ <- hung.traverse_(t => w.defs.hang(t.definition))
      _ <- passes(w, 4)()
      agent <- w.consul.current
    yield agent

    underManySeeds(program).map(
      _.foreach((seed, agent) => assertEquals(agent.services.map(_.id), List(fooId), s"seed $seed"))
    )

  test(
    "hung definitions take turns, so one that recovers is looked up within a few passes"
  ):
    // By the third pass every definition has failed at least once.
    val hung = hungTasks(24)
    val recovering = hung(19)
    val recoveringId = ServiceId("test-host:ecs-hung-20-1-web-ffff:8020")
    val program = for
      w <- world(hung, Map.empty)
      _ <- hung.traverse_(t => w.defs.hang(t.definition))
      _ <- passes(w, 7)(i => w.defs.answer(recovering.definition, plain).whenA(i == 2))
      agent <- w.consul.current
    yield agent

    underManySeeds(program).map(
      _.foreach((seed, agent) =>
        assertEquals(
          agent.services.map(_.id),
          List(recoveringId),
          s"seed $seed"
        )
      )
    )

  /** The phrases that say how a definition's lookup went.
    */
  private val lookupPhrases = List(
    "lookup failed",
    "waited behind other lookups",
    "cut off",
    "succeeded after failing"
  )

  /** Each line logged about `ref`'s lookup: its level, and which of
    * [[lookupPhrases]] it carries.
    */
  private def lookupLines(ref: TaskDefinitionRef)(logged: Vector[LogMessage]): Vector[String] =
    logged
      .filter(_.ctx.get("task_definition").contains(ref.value))
      .map: m =>
        val level = m match
          case _: WARN => "WARN"
          case _: INFO => "INFO"
          case _       => "other"
        s"$level ${lookupPhrases.filter(m.message.contains).mkString(", ")}"

  test(
    "a lookup that got no full turn is logged at INFO for what happened, not as a failure"
  ):
    // Two rounds time out on their own, the deadline cuts off the third,
    // and the last four never start.
    val hung = hungTasks(16).map(_.definition)
    val program = for
      w <- world(hungTasks(16), Map.empty)
      _ <- hung.traverse_(w.defs.hang)
      _ <- passes(w, 1)()
      logged <- w.logger.logged
    yield logged

    underFixedSeed(program).map: logged =>
      assertEquals(
        hung.map(ref => lookupLines(ref)(logged)),
        List.fill(8)(Vector("WARN lookup failed")) ++
          List.fill(4)(Vector("INFO cut off")) ++
          List.fill(4)(Vector("INFO waited behind other lookups"))
      )

  test(
    "a definition whose lookup only waited isn't logged as succeeding after failing"
  ):
    // foo, listed after twelve hung lookups, never starts in the first pass,
    // and answers in the second.
    val hung = hungTasks(12)
    val program = for
      w <- world(hung :+ foo, Map(fooRef -> plain))
      _ <- hung.traverse_(t => w.defs.hang(t.definition))
      _ <- passes(w, 2)()
      logged <- w.logger.logged
    yield logged

    underFixedSeed(program).map(logged =>
      assertEquals(
        lookupLines(fooRef)(logged),
        Vector("INFO waited behind other lookups")
      )
    )

  test(
    "a definition that failed, then got no full turn, is logged as succeeding once it answers"
  ):
    // foo fails in the first pass, hangs in the second until the deadline
    // cuts it off behind sixteen hung lookups, and answers in the third.
    val hung = hungTasks(16)
    val program = for
      w <- world(foo :: hung, Map.empty)
      _ <- hung.traverse_(t => w.defs.hang(t.definition))
      _ <- passes(w, 3)(i =>
        w.defs.hang(fooRef).whenA(i == 0) >>
          w.defs.answer(fooRef, plain).whenA(i == 1)
      )
      logged <- w.logger.logged
    yield logged

    underFixedSeed(program).map(logged =>
      assertEquals(
        lookupLines(fooRef)(logged),
        Vector("WARN lookup failed", "INFO succeeded after failing")
      )
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

    underFixedSeed(program).map: (reports, writes, logged) =>
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
          List(AgentCheck(CheckId(s"service:${fooId.value}"), fooId.some, None))
        )
      )
      seeded <- w.consul.current
      reports <- passes(w, 2)()
      writes <- w.consul.writes
      agent <- w.consul.current
      logged <- w.logger.logged
    yield (seeded, reports, writes, agent, logged)

    underFixedSeed(program).map: (seeded, reports, writes, agent, logged) =>
      assertEquals(
        reports.map(_.outcome.map(_.actual)),
        List(Right(seeded), Right(agent))
      )
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
      assertEquals(
        reports.headOption.map(kinds),
        Some(
          Right(
            List(
              s"deregister ${orphan.id.value} ok",
              s"replace ${fooId.value} ok",
              s"update ${barId.value} ok",
              s"register ${bazId.value} ok"
            )
          )
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

    underFixedSeed(program).map: (reports, writes, logged) =>
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

    underFixedSeed(program).map: (writes, logged) =>
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

    underFixedSeed(program).map: (writes, logged) =>
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

    underFixedSeed(program).map: (emitted, elapsed, writes, logged) =>
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

    underFixedSeed(program).map: (reports, writes, agent, logged) =>
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

  private val awaitingFailure = new RuntimeException("throttled")

  test(
    "a definition lookup that never returns times out, and the rest of the host keeps reconciling"
  ):
    val program = for
      w <- world(
        List(foo, bar),
        Map(fooRef -> plain),
        AgentState(List(agentService(barId, "bar-worker", 32769), orphan), Nil)
      )
      _ <- w.defs.hang(barRef)
      reports <- reconcile(w, policy = FailurePolicy.exitAfter(2.minutes))
        .interruptAfter(3.minutes)
        .compile
        .toList
      writes <- w.consul.writes
      agent <- w.consul.current
      logged <- w.logger.logged
    yield (reports, writes, agent, logged)

    underFixedSeed(program).map: (reports, writes, agent, logged) =>
      assert(reports.sizeIs > 30, reports.size.toString)
      assert(reports.forall(_.outcome.isRight), reports.toString)
      assert(reports.forall(_.duration == lookupTimeout), reports.toString)
      assertEquals(
        described(writes),
        Vector(s"deregister ${orphan.id.value}", s"register ${fooId.value}")
      )
      assertEquals(agent.services.map(_.id).toSet, Set(barId, fooId))
      assertEquals(warnings(logged).size, 1, logged.toString)

  test("shadow mode doesn't report an awaiting task's services as extra"):
    val program = for
      w <- world(
        List(foo, bar),
        Map(fooRef -> plain),
        AgentState(List(agentService(barId, "bar-worker", 32769)), Nil)
      )
      _ <- w.defs.fail(barRef, awaitingFailure)
      reports <- passes(w, 1, Mode.Shadow)()
    yield reports

    underFixedSeed(program).map: reports =>
      assertEquals(
        reports.map(
          _.outcome.map(_.differences.map:
            case Difference.Missing(r)         => s"missing ${r.id.value}"
            case Difference.Extra(s)           => s"extra ${s.id.value}"
            case Difference.Different(r, _, _) => s"different ${r.id.value}")
        ),
        List(Right(List(s"missing ${fooId.value}")))
      )

  test(
    "a replacement whose register fails is logged as a deregistration, with a warning"
  ):
    val program = for
      w <- world(
        List(foo),
        Map(fooRef -> plain),
        AgentState(
          List(agentService(fooId, "foo-service", 32768)),
          List(AgentCheck(CheckId(s"service:${fooId.value}"), fooId.some, None))
        )
      )
      _ <- w.consul.failRegistrationsOf(Set(fooId))
      _ <- passes(w, 1)()
      logged <- w.logger.logged
    yield logged

    underFixedSeed(program).map: logged =>
      assertEquals(
        infos(logged).map(_.ctx.get("kind")),
        Vector(Some("deregister")),
        logged.toString
      )
      assertEquals(
        warnings(logged).filter(about(fooId)).size,
        1,
        logged.toString
      )

  test(
    "a replacement whose register fails is reported as a deregistration and a failed registration"
  ):
    val program = for
      w <- world(
        List(foo),
        Map(fooRef -> plain),
        AgentState(
          List(agentService(fooId, "foo-service", 32768)),
          List(AgentCheck(CheckId(s"service:${fooId.value}"), fooId.some, None))
        )
      )
      _ <- w.consul.failRegistrationsOf(Set(fooId))
      reports <- passes(w, 1)()
    yield reports

    underFixedSeed(program).map: reports =>
      assertEquals(
        reports.map(kinds),
        List(
          Right(
            List(
              s"deregister ${fooId.value} ok",
              s"register ${fooId.value} failed"
            )
          )
        )
      )

  test(
    "a replacement whose deregister fails is logged, reported, and retried as an update"
  ):
    val program = for
      w <- world(
        List(foo),
        Map(fooRef -> plain),
        AgentState(
          List(agentService(fooId, "foo-service", 32768)),
          List(AgentCheck(CheckId(s"service:${fooId.value}"), fooId.some, None))
        )
      )
      _ <- w.consul.failDeregistrationsOf(Set(fooId))
      reports <- passes(w, 1)()
      writes <- w.consul.writes
      logged <- w.logger.logged
    yield (reports, writes, logged)

    underFixedSeed(program).map: (reports, writes, logged) =>
      assertEquals(
        described(writes),
        Vector(s"deregister ${fooId.value}", s"register ${fooId.value}")
      )
      assertEquals(
        reports.map(kinds),
        List(
          Right(
            List(
              s"deregister ${fooId.value} failed",
              s"update ${fooId.value} ok"
            )
          )
        )
      )
      assertEquals(
        infos(logged).map(_.ctx.get("kind")),
        Vector(Some("update")),
        logged.toString
      )
      assertEquals(
        warnings(logged).filter(about(fooId)).size,
        1,
        logged.toString
      )

  test("a failed deregistration doesn't stop the others"):
    val otherOrphan =
      orphan.copy(id = ServiceId("test-host:ecs-gone-2-web-eeee:8080"))
    val program = for
      w <- world(Nil, Map.empty, AgentState(List(orphan, otherOrphan), Nil))
      _ <- w.consul.failDeregistrationsOf(Set(orphan.id))
      reports <- passes(w, 1)()
      writes <- w.consul.writes
      agent <- w.consul.current
    yield (reports, writes, agent)

    underFixedSeed(program).map: (reports, writes, agent) =>
      assertEquals(
        described(writes),
        Vector(
          s"deregister ${orphan.id.value}",
          s"deregister ${otherOrphan.id.value}"
        )
      )
      assertEquals(agent.services, List(orphan))
      assertEquals(
        reports.map(_.outcome.map(_.failures.map(_.id))),
        List(Right(List(orphan.id)))
      )

  /** Runs one apply pass with `barRef`'s definition failing, and returns the
    * IDs left on the agent.
    */
  private def remainingWhileBarAwaits(tasks: List[EcsTask], seeded: List[ServiceId]): IO[Set[ServiceId]] =
    val program = for
      w <- world(
        tasks,
        Map.empty,
        AgentState(seeded.map(agentService(_, "seeded", 31000)), Nil)
      )
      _ <- w.defs.fail(barRef, awaitingFailure)
      _ <- passes(w, 1)()
      agent <- w.consul.current
    yield agent.services.map(_.id).toSet
    underFixedSeed(program)

  test(
    "an awaiting task's Docker name protects only IDs with exactly that name"
  ):
    val awaiting = task("bar", barRef, "ecs-foo", 8080, 32768)
    val kept = ServiceId("test-host:ecs-foo:8080")
    val longerName = ServiceId("test-host:ecs-foo-bar:8080")
    remainingWhileBarAwaits(List(awaiting), List(kept, longerName))
      .map(assertEquals(_, Set(kept)))

  test("every container of an awaiting task keeps its registrations"):
    val awaiting = taskOf(
      "bar",
      barRef,
      List(
        container("web", "ecs-bar-worker-2-web-bbbb", 9000, 32769),
        container("worker", "ecs-bar-worker-2-worker-bbbb", 9100, 32771)
      )
    )
    val ids = List(
      barId,
      ServiceId("test-host:ecs-bar-worker-2-worker-bbbb:9100")
    )
    remainingWhileBarAwaits(List(awaiting), ids)
      .map(assertEquals(_, ids.toSet))

  test(
    "a stopping task that shares an awaiting task's definition is still deregistered"
  ):
    val stopping = task(
      "bar-old",
      barRef,
      "ecs-bar-worker-2-web-ffff",
      9000,
      32772,
      desiredStatus = TaskStatus.Stopped
    )
    val stoppingId = ServiceId("test-host:ecs-bar-worker-2-web-ffff:9000")
    remainingWhileBarAwaits(List(bar, stopping), List(barId, stoppingId))
      .map(assertEquals(_, Set(barId)))

  test(
    "the lookup phase ends at its deadline when many lookups hang, keeping what finished"
  ):
    // 30 hung lookups, four at a time, would take 8 * lookupTimeout = 40 s,
    // more than passTimeout; the phase is cut off at its deadline.
    val refs =
      (0 until 30).toList.map(i => TaskDefinitionRef(TaskFamily(s"svc-$i"), 1))
    val tasks = refs.zipWithIndex.map: (ref, i) =>
      task(s"t$i", ref, s"ecs-svc-$i-1-web-abcd", 8000 + i, 32000 + i)
    val ids = (0 until 30).toList.map(i => ServiceId(s"test-host:ecs-svc-$i-1-web-abcd:${8000 + i}"))
    val program = for
      w <- world(
        tasks,
        Map(refs.head -> plain),
        AgentState(ids.tail.map(agentService(_, "seeded", 31000)), Nil)
      )
      _ <- refs.tail.traverse_(w.defs.hang)
      reports <- passes(w, 1)()
      writes <- w.consul.writes
      agent <- w.consul.current
    yield (reports, writes, agent)

    underFixedSeed(program).map: (reports, writes, agent) =>
      assertEquals(
        reports.map(_.duration),
        List(DefinitionLookups.deadline(lookupTimeout))
      )
      assertEquals(
        reports.map(_.outcome.map(_.desired.awaitingDefinitions)),
        List(Right(refs.tail.toSet))
      )
      assertEquals(described(writes), Vector(s"register ${ids.head.value}"))
      assertEquals(agent.services.map(_.id).toSet, ids.toSet)
end ReconcilerSuite
