package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.consul.agent.InMemoryConsulAgent
import com.dwolla.nomenclator.ecs.*
import com.dwolla.nomenclator.ecs.agent.InMemoryEcsAgent
import com.dwolla.nomenclator.ecs.api.InMemoryTaskDefinitions
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.HostIdentity
import com.dwolla.nomenclator.settings.ContainerSettings
import io.opentelemetry.api.common.AttributeKey
import munit.CatsEffectSuite
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.testing.StructuredTestingLogger
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit
import org.typelevel.otel4s.trace.Tracer

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class ReconcileSpanSuite extends CatsEffectSuite:
  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val host =
    HostIdentity(ok(HostName.from("test-host")), ServiceAddress("10.0.0.1"))

  private def task(name: String, ref: TaskDefinitionRef, port: Int) =
    EcsTask(
      TaskArn(s"arn:aws:ecs:us-east-1:123456789012:task/example/$name"),
      ref,
      TaskStatus.Running,
      TaskStatus.Running,
      List(
        EcsContainer(
          ContainerName("web"),
          DockerName(s"ecs-$name-web"),
          ImageName(s"registry.example.com/$name:1"),
          List(
            PortBinding(
              ok(ContainerPort.from(8080)),
              ok(HostPort.from(port)),
              Protocol.Tcp
            )
          )
        )
      )
    )

  private def settings(env: (String, String)*) =
    Map(ContainerName("web") -> ContainerSettings.fromRaw(env.toMap, Map.empty))

  private val fooRef = TaskDefinitionRef(TaskFamily("foo-service"), 1)
  private val scriptedRef = TaskDefinitionRef(TaskFamily("scripted"), 1)

  private def agentService(id: String) =
    AgentService(
      ServiceId(id),
      ServiceName("old"),
      Nil,
      ServiceAddress("10.0.0.1"),
      32767
    )

  test("the reconcile span carries the pass's counts"):
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        for
          intro <- InMemoryEcsAgent(
            List(
              task("foo-service", fooRef, 32768),
              task("scripted", scriptedRef, 32769)
            )
          )
          defs <- InMemoryTaskDefinitions(
            Map(
              fooRef -> settings(),
              scriptedRef -> settings("SERVICE_CHECK_SCRIPT" -> "curl localhost")
            )
          )
          consul <- InMemoryConsulAgent(
            AgentState(
              List(
                agentService("test-host:ecs-gone-web:8080"),
                agentService("consul")
              ),
              Nil
            )
          )
          tracer <- testkit.tracerProvider.get("test")
          _ <-
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
              .drain
          spans <- testkit.finishedSpans
        yield
          val reconcile = spans.filter(_.getName == "reconcile")
          assertEquals(reconcile.size, 1)
          val attributes = reconcile.head.getAttributes
          def count(key: String): Option[Long] =
            Option(attributes.get(AttributeKey.longKey(key))).map(_.longValue)
          assertEquals(count("nomenclator.services.desired"), 1L.some)
          assertEquals(count("nomenclator.services.registered"), 2L.some)
          assertEquals(count("nomenclator.changes.planned"), 2L.some)
          assertEquals(count("nomenclator.services.skipped"), 1L.some)
          assertEquals(
            attributes.asMap.asScala.keySet.map(_.getKey),
            Set(
              "nomenclator.services.desired",
              "nomenclator.services.registered",
              "nomenclator.changes.planned",
              "nomenclator.services.skipped"
            )
          )
end ReconcileSpanSuite
