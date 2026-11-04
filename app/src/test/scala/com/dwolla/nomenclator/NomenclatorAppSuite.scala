package com.dwolla.nomenclator

import cats.effect.*
import cats.effect.metrics.CpuStarvationWarningMetrics
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.dwolla.nomenclator.config.{NomenclatorConfig, TestEnvironment}
import com.dwolla.nomenclator.consul.AgentState
import com.dwolla.nomenclator.consul.agent.InMemoryConsulAgent
import com.dwolla.nomenclator.consul.agent.InMemoryConsulAgent.Attempt
import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.ecs.agent.InMemoryEcsAgent
import com.dwolla.nomenclator.ecs.api.{InMemoryTaskDefinitions, LookupResult}
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.reconcile.{Mode, PassReport}
import com.dwolla.nomenclator.registration.HostIdentity
import com.dwolla.nomenclator.settings.ContainerSettings
import com.dwolla.nomenclator.telemetry.NomenclatorMetrics
import com.dwolla.tracing.DwollaEnvironment
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger.*
import org.typelevel.otel4s.trace.Tracer
import smithy4s.aws.AwsRegion

import scala.concurrent.duration.*

class NomenclatorAppSuite extends CatsEffectSuite:
  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val token = "s3cret-token-value"
  private val host =
    HostIdentity(ok(HostName.from("test-host")), ServiceAddress("10.0.0.1"))
  private val fooRef = TaskDefinitionRef(TaskFamily("foo-service"), 1)

  private val foo = EcsTask(
    TaskArn("arn:aws:ecs:us-east-1:123456789012:task/example/foo"),
    fooRef,
    TaskStatus.Running,
    TaskStatus.Running,
    List(
      EcsContainer(
        ContainerName("web"),
        DockerName("ecs-foo-service-1-web-aaaa"),
        ImageName("registry.example.com/foo-service:1"),
        List(
          PortBinding(
            ok(ContainerPort.from(8080)),
            ok(HostPort.from(32768)),
            Protocol.Tcp
          )
        )
      )
    )
  )
  private val fooSettings =
    Map(ContainerName("web") -> ContainerSettings.fromRaw(Map.empty, Map.empty))

  private def configFrom(variables: (String, String)*): IO[NomenclatorConfig] =
    NomenclatorConfig.fromEnvironment(TestEnvironment(variables*)).load[IO]

  /** Metrics that remember every pass report they're given.
    */
  private def recordingMetrics: IO[(Ref[IO, Vector[PassReport]], NomenclatorMetrics[IO])] =
    Ref
      .of[IO, Vector[PassReport]](Vector.empty)
      .map: reports =>
        reports -> new NomenclatorMetrics[IO]:
          override def record(mode: Mode, report: PassReport): IO[Unit] =
            reports.update(_ :+ report)
          override def taskDefinitionLookup(result: LookupResult): IO[Unit] =
            IO.unit

  test("stopping the loop writes nothing to Consul: registrations stay"):
    val logger = StructuredTestingLogger.impl[IO]()
    given StructuredLogger[IO] = logger
    given Tracer[IO] = Tracer.noop[IO]
    TestControl.executeEmbed(
      for
        config <- configFrom("NOMENCLATOR_MODE" -> "apply")
        consul <- InMemoryConsulAgent(AgentState(Nil, Nil))
        intro <- InMemoryEcsAgent(List(foo))
        defs <- InMemoryTaskDefinitions(Map(fooRef -> fooSettings))
        (reports, metrics) <- recordingMetrics
        loop <- NomenclatorApp
          .reconcile[IO](config, host, intro, defs, consul, metrics)
          .start
        _ <- IO.sleep(1.minute)
        writesBefore <- consul.writes
        stateBefore <- consul.current
        _ <- loop.cancel
        _ <- IO.sleep(1.minute)
        writesAfter <- consul.writes
        stateAfter <- consul.current
        recorded <- reports.get
        logged <- logger.logged
      yield
        assertEquals(stateBefore.services.map(_.port), List(32768))
        assertEquals(writesAfter, writesBefore)
        assertEquals(stateAfter, stateBefore)
        assert(
          !writesAfter.exists:
            case Attempt.Deregister(_) => true
            case _                     => false
          ,
          writesAfter
        )
        assert(recorded.size >= 12, recorded.size)
        assert(
          logged.exists:
            case INFO(message, _, _) => message.contains("Stopping")
            case _                   => false
          ,
          logged
        )
    )

  test("after the failure limit, the loop logs why at ERROR and exits 1"):
    val logger = StructuredTestingLogger.impl[IO]()
    given StructuredLogger[IO] = logger
    given Tracer[IO] = Tracer.noop[IO]
    TestControl.executeEmbed(
      for
        config <- configFrom()
        consul <- InMemoryConsulAgent(AgentState(Nil, Nil))
        _ <- consul.failState(new RuntimeException("connection refused").some)
        intro <- InMemoryEcsAgent(List(foo))
        defs <- InMemoryTaskDefinitions(Map(fooRef -> fooSettings))
        (_, metrics) <- recordingMetrics
        exit <- NomenclatorApp.reconcile[IO](
          config,
          host,
          intro,
          defs,
          consul,
          metrics
        )
        logged <- logger.logged
      yield
        assertEquals(exit, ExitCode(1))
        assert(
          logged.exists:
            case ERROR(message, _, _) =>
              message.contains("No reconcile pass has succeeded for 2 minutes")
            case _ => false
          ,
          logged
        )
    )

  test(
    "the startup line describes the effective configuration, never the token"
  ):
    val logger = StructuredTestingLogger.impl[IO]()
    given StructuredLogger[IO] = logger
    for
      config <- configFrom("CONSUL_HTTP_TOKEN" -> token)
      _ <- NomenclatorApp.logStartup[IO](config, DwollaEnvironment.Prod)
      logged <- logger.logged
    yield
      assertEquals(logged.size, 1, logged)
      val line = logged.head
      assert(!line.message.contains(token), line.message)
      assert(!line.ctx.mkString.contains(token), line.ctx.toString)
      assertEquals(line.ctx.get("consul_token"), Some("<redacted>"))
      assertEquals(line.ctx.get("environment"), Some("Prod"))
      assertEquals(line.ctx.get("mode"), Some("shadow"))

  test("the discovered line names the host, its address, and its region"):
    val logger = StructuredTestingLogger.impl[IO]()
    given StructuredLogger[IO] = logger
    for
      _ <- NomenclatorApp.logDiscovered[IO](host, AwsRegion("us-east-1"))
      logged <- logger.logged
    yield
      assertEquals(logged.size, 1, logged)
      val ctx = logged.head.ctx
      assertEquals(ctx.get("host_name"), Some("test-host"))
      assertEquals(ctx.get("address"), Some("10.0.0.1"))
      assertEquals(ctx.get("aws_region"), Some("us-east-1"))

  test("task-role credentials come from the relative URI first"):
    configFrom(
      "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" -> "/v2/credentials/abc",
      "AWS_CONTAINER_CREDENTIALS_FULL_URI" -> "http://127.0.0.1:9911/creds"
    ).map(config =>
      assertEquals(
        NomenclatorApp.credentialsEndpoint(config).map(_.renderString),
        Right("http://169.254.170.2/v2/credentials/abc")
      )
    )

  test("task-role credentials come from the full URI otherwise"):
    configFrom(
      "AWS_CONTAINER_CREDENTIALS_FULL_URI" -> "http://127.0.0.1:9911/creds"
    ).map(config =>
      assertEquals(
        NomenclatorApp.credentialsEndpoint(config).map(_.renderString),
        Right("http://127.0.0.1:9911/creds")
      )
    )

  test("without either credentials variable, the error names both"):
    configFrom().map(config =>
      NomenclatorApp.credentialsEndpoint(config) match
        case Left(e) =>
          assert(
            e.getMessage.contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"),
            e.getMessage
          )
          assert(
            e.getMessage.contains("AWS_CONTAINER_CREDENTIALS_FULL_URI"),
            e.getMessage
          )
        case Right(uri) => fail(s"expected an error, got $uri")
    )

  test("CPU starvation is logged as a structured warning"):
    val logger = StructuredTestingLogger.impl[IO]()
    NomenclatorApp
      .logCpuStarvation[IO](
        CpuStarvationWarningMetrics(
          occurrenceTime = 1.minute,
          clockDrift = 150.millis,
          starvationThreshold = 0.1,
          starvationInterval = 1.second
        )
      )(using logger) >>
      logger.logged.map:
        case Vector(WARN(message, None, ctx)) =>
          assertEquals(ctx.get("clock_drift"), Some("150ms"))
          assertEquals(ctx.get("starvation_interval"), Some("1s"))
          assert(message.contains("CPU"), message)
        case other => fail(s"expected one warning, got $other")

  test("the environment is the first argument"):
    given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
    NomenclatorApp
      .environment[IO](List("Prod", "ignored"))
      .map(assertEquals(_, DwollaEnvironment.Prod))

  List(Nil, List("Bogus")).foreach: args =>
    test(
      s"arguments ${args.mkString("[", ",", "]")} fall back to Local, with a warning"
    ):
      val logger = StructuredTestingLogger.impl[IO]()
      given StructuredLogger[IO] = logger
      for
        environment <- NomenclatorApp.environment[IO](args)
        logged <- logger.logged
      yield
        assertEquals(environment, DwollaEnvironment.Local)
        assertEquals(
          logged
            .collect:
              case w: WARN => w
            .size,
          1,
          logged
        )
end NomenclatorAppSuite
