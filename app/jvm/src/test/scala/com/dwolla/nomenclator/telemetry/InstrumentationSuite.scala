package com.dwolla.nomenclator.telemetry

import cats.data.Kleisli
import cats.effect.*
import cats.syntax.all.*
import com.amazonaws.ecs.*
import com.dwolla.nomenclator.consul.AgentState
import com.dwolla.nomenclator.consul.agent.{Http4sConsulAgent, InMemoryConsulAgent}
import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.ecs.agent.InMemoryEcsAgent
import com.dwolla.nomenclator.ecs.api.CachedTaskDefinitions
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.model.Protocol
import com.dwolla.nomenclator.reconcile.*
import com.dwolla.nomenclator.registration.{HostIdentity, Registration}
import com.dwolla.nomenclator.telemetry.AlgebraAspects.given
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.syntax.all.*
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import org.typelevel.otel4s.trace.{Tracer, TracerProvider}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class InstrumentationSuite extends CatsEffectSuite:
  import CollectedMetrics.*

  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val consulToken = "s3cret-consul-token"
  private val secretValue = "hunter2-test"
  private val host = HostIdentity(ok(HostName.from("test-host")), "10.0.0.1")
  private val ref = TaskDefinitionRef("foo-service", 7)

  private val fooTask = EcsTask(
    "arn:aws:ecs:us-east-1:123456789012:task/example/foo",
    ref,
    TaskStatus.Running,
    TaskStatus.Running,
    List(
      EcsContainer(
        ContainerName("web"),
        DockerName("ecs-foo-service-7-web"),
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
  private val fooId = "test-host:ecs-foo-service-7-web:8080"

  /** A task definition whose environment holds a secret beside its settings.
    */
  private val ecs: ECS[IO] = new ECS[IO]:
    override def describeTaskDefinition
      (taskDefinition: String, include: Option[List[TaskDefinitionField]]): IO[DescribeTaskDefinitionResponse] =
      DescribeTaskDefinitionResponse(
        taskDefinition = TaskDefinition(containerDefinitions =
          List(
            ContainerDefinition(
              name = "web".some,
              environment = List(
                KeyValuePair("SERVICE_NAME".some, "foo-service".some),
                KeyValuePair("DB_PASSWORD".some, secretValue.some)
              ).some
            )
          ).some
        ).some
      ).pure[IO]

  /** A Consul agent with nothing registered, which refuses registrations with
    * an answer that repeats the token.
    */
  private val consulApp: HttpApp[IO] = Kleisli: req =>
    (req.method, req.uri.path.renderString) match
      case (Method.GET, "/v1/agent/services" | "/v1/agent/checks") =>
        Response[IO](Status.Ok).withEntity("{}").pure[IO]
      case (Method.PUT, "/v1/agent/service/register") =>
        Response[IO](Status.Forbidden)
          .withEntity(s"ACL not found for token $consulToken")
          .pure[IO]
      case _ => Response[IO](Status.NotFound).pure[IO]

  /** Everything recorded on `span` as text: its name, status description,
    * attributes, and events' attributes.
    */
  private def recordedText(span: SpanData): List[String] =
    def values(attributes: Attributes) =
      attributes.asMap.asScala.values.map(_.toString).toList
    span.getName :: span.getStatus.getDescription :: values(
      span.getAttributes
    ) ++ span.getEvents.asScala.toList.flatMap(e => values(e.getAttributes))

  /** Runs one apply-mode pass with every algebra instrumented, then returns the
    * finished spans and the collected metrics.
    */
  private val instrumentedPass = OtelJavaTestkit
    .inMemory[IO]()
    .use: testkit =>
      given TracerProvider[IO] = testkit.tracerProvider
      given MeterProvider[IO] = testkit.meterProvider
      for
        intro <- InMemoryEcsAgent(List(fooTask))
          .flatMap(Instrumentation.instrumentAlgebra(_))
        consul <- Instrumentation.instrumentAlgebra(
          Http4sConsulAgent[IO](
            Client.fromHttpApp(consulApp),
            uri"http://consul.invalid:8500",
            consulToken.some
          )
        )
        defs <- Instrumentation
          .instrumentEcsClient(ecs)
          .flatMap(CachedTaskDefinitions(_, _ => IO.unit))
        tracer <- testkit.tracerProvider.get("test")
        reports <-
          given StructuredLogger[IO] = StructuredTestingLogger.impl[IO]()
          given Tracer[IO] = tracer
          Reconciler
            .stream[IO](
              Mode.Apply,
              5.seconds,
              30.seconds,
              5.seconds,
              host,
              Nil,
              intro,
              defs,
              consul,
              FailurePolicy.default
            )
            .take(1)
            .compile
            .toList
        spans <- testkit.finishedSpans
        metrics <- testkit.collectMetrics
      yield (reports, spans, metrics)
      end for

  test("no span records the Consul token or a task definition's environment"):
    instrumentedPass.map: (reports, spans, _) =>
      assertEquals(
        reports.flatMap(_.outcome.toOption).flatMap(_.failures.map(_.id)),
        List(ServiceId(fooId)),
        "the refused registration should have been attempted and failed"
      )
      val recorded = spans.flatMap(recordedText)
      assert(recorded.exists(_.contains(fooId)), recorded)
      List(consulToken, "DB_PASSWORD", secretValue).foreach(secret =>
        assert(
          !recorded.exists(_.contains(secret)),
          s"a span recorded $secret: $recorded"
        )
      )

  test("every algebra call gets a span, and the ECS client's has its input"):
    instrumentedPass.map: (_, spans, _) =>
      val names = spans.map(_.getName).toSet
      assert(
        Set(
          "EcsAgentIntrospection.tasks",
          "ConsulAgent.state",
          "ConsulAgent.register"
        ).subsetOf(names),
        names
      )
      val reconcile = spans.filter(_.getName == "reconcile")
      assertEquals(reconcile.size, 1, names)
      assert(
        spans
          .filterNot(_ == reconcile.head)
          .forall(_.getParentSpanId == reconcile.head.getSpanId),
        "every algebra call should be a child of the pass's span"
      )
      val describe =
        spans.filter(_.getName.endsWith(".DescribeTaskDefinition"))
      assertEquals(describe.size, 1, names)
      assert(
        recordedText(describe.head).exists(_.contains("foo-service:7")),
        recordedText(describe.head)
      )
      val register = spans.filter(_.getName == "ConsulAgent.register")
      assert(
        register.flatMap(recordedText).exists(_.contains(fooId)),
        register.flatMap(recordedText)
      )
      assertEquals(
        register.map(_.getStatus.getStatusCode),
        List(StatusCode.ERROR)
      )

  test("algebra calls and ECS calls record their durations"):
    instrumentedPass.map: (_, _, metrics) =>
      val calls = points(metrics, "com.dwolla.code.function.duration").keySet
        .flatMap(_.get("code.function.name"))
      assert(
        Set(
          "EcsAgentIntrospection.tasks",
          "ConsulAgent.state",
          "ConsulAgent.register"
        ).subsetOf(calls),
        calls
      )
      val rpc = points(metrics, "rpc.client.call.duration").keySet
        .flatMap(_.get("rpc.method"))
      assert(rpc.exists(_.endsWith("/DescribeTaskDefinition")), rpc)

  test("each method's span is named for it and records its inputs"):
    val id = ServiceId(fooId)
    val registration = Registration(
      id,
      ServiceName("foo-service"),
      Nil,
      "10.0.0.1",
      ok(HostPort.from(32768)),
      None
    )
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        given TracerProvider[IO] = testkit.tracerProvider
        given MeterProvider[IO] = testkit.meterProvider
        for
          intro <- InMemoryEcsAgent(Nil)
            .flatMap(Instrumentation.instrumentAlgebra(_))
          consul <- InMemoryConsulAgent(AgentState(Nil, Nil))
            .flatMap(Instrumentation.instrumentAlgebra(_))
          _ <- intro.metadata >> intro.tasks
          _ <- consul.advertisedAddress >> consul.state
          _ <- consul.register(registration) >> consul.deregister(id)
          spans <- testkit.finishedSpans
        yield
          val byName = spans.map(s => s.getName -> recordedText(s)).toMap
          assertEquals(
            byName.keySet,
            Set(
              "EcsAgentIntrospection.metadata",
              "EcsAgentIntrospection.tasks",
              "ConsulAgent.advertisedAddress",
              "ConsulAgent.state",
              "ConsulAgent.register",
              "ConsulAgent.deregister"
            )
          )
          List("ConsulAgent.register", "ConsulAgent.deregister")
            .foreach(name => assert(byName(name).exists(_.contains(fooId)), byName(name)))
          assert(
            byName("ConsulAgent.register").exists(_.contains("foo-service")),
            byName("ConsulAgent.register")
          )
        end for
end InstrumentationSuite
