package com.dwolla.nomenclator.telemetry

import cats.data.NonEmptyList
import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.ecs.api.LookupResult
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.plan.*
import com.dwolla.nomenclator.reconcile.*
import com.dwolla.nomenclator.registration.*
import io.opentelemetry.sdk.metrics.data.MetricData
import munit.CatsEffectSuite
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.oteljava.testkit.OtelJavaTestkit

import scala.concurrent.duration.*

class NomenclatorMetricsSuite extends CatsEffectSuite:
  import CollectedMetrics.*

  private def ok[A](either: Either[String, A]): A =
    either.fold(fail(_), identity)

  private val host = ok(HostName.from("test-host"))

  private def owned(name: String) = ServiceId(s"test-host:ecs-$name-1-web:8080")

  private def registration(id: ServiceId) =
    Registration(
      id,
      ServiceName("foo-service"),
      Nil,
      "10.0.0.1",
      ok(HostPort.from(32768)),
      None
    )

  private def agentService(id: ServiceId) =
    AgentService(id, ServiceName("foo-service"), Nil, "10.0.0.1", 32768)

  private def skipped(reason: SkipReason) =
    Skipped(
      "arn:aws:ecs:us-east-1:123456789012:task/example/foo",
      ContainerName("web"),
      ok(ContainerPort.from(8080)),
      reason
    )

  private def outcome(
                       registrations: List[Registration] = Nil,
                       skips: List[Skipped] = Nil,
                       agent: List[AgentService] = Nil,
                       plan: Plan = Plan(Nil, Nil),
                       differences: List[Difference] = Nil,
                       writes: List[Write] = Nil
  ) =
    PassOutcome(
      Desired(registrations, skips, Set.empty, Nil),
      AgentState(agent, Nil),
      plan,
      differences,
      writes
    )

  private val succeeded = ().asRight[Throwable]
  private val failed = new RuntimeException("boom").asLeft[Unit]

  private val passed = 1.second

  /** Records `reports` in order, then collects what was recorded.
    */
  private def recorded(reports: (Mode, PassReport)*): IO[List[MetricData]] =
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        testkit.meterProvider
          .get("test")
          .flatMap: meter =>
            given Meter[IO] = meter
            NomenclatorMetrics[IO](host).use(metrics =>
              reports.toList.traverse_(metrics.record.tupled) >>
                testkit.collectMetrics
            )

  test("a pass that registers one service and skips a script check"):
    val foo = owned("foo")
    recorded(
      Mode.Apply -> PassReport(
        passed,
        outcome(
          registrations = List(registration(foo)),
          skips = List(skipped(SkipReason.ScriptCheck)),
          plan = Plan(List(registration(foo)), Nil),
          writes = List(Write(foo, ChangeKind.Register, succeeded))
        ).asRight
      )
    ).map: metrics =>
      assertEquals(
        points(metrics, "nomenclator.changes"),
        Map(
          Map(
            "nomenclator.change.kind" -> "register",
            "nomenclator.change.outcome" -> "ok"
          ) -> 1.0
        )
      )
      assertEquals(
        points(metrics, "nomenclator.services.skipped"),
        Map(
          Map("nomenclator.skip.reason" -> "ignored") -> 0.0,
          Map("nomenclator.skip.reason" -> "script_check") -> 1.0,
          Map("nomenclator.skip.reason" -> "ttl_check") -> 0.0,
          Map("nomenclator.skip.reason" -> "no_host_port") -> 0.0,
          Map("nomenclator.skip.reason" -> "invalid_settings") -> 0.0
        )
      )
      assertEquals(
        points(metrics, "nomenclator.services.desired"),
        Map(Map.empty[String, String] -> 1.0)
      )
      assertEquals(unit(metrics, "nomenclator.changes"), Some("{change}"))
      assertEquals(
        unit(metrics, "nomenclator.services.skipped"),
        Some("{service}")
      )
      assertEquals(
        unit(metrics, "nomenclator.services.desired"),
        Some("{service}")
      )

  test("changes are counted by the kind and result of each write"):
    val updated = owned("updated")
    val replaced = owned("replaced")
    val halfReplaced = owned("half")
    val removed = owned("removed")
    val failedToRemove = owned("stuck")
    recorded(
      Mode.Apply -> PassReport(
        passed,
        outcome(
          writes =
            List(
              Write(failedToRemove, ChangeKind.Deregister, failed),
              Write(removed, ChangeKind.Deregister, succeeded),
              Write(updated, ChangeKind.Update, succeeded),
              Write(replaced, ChangeKind.Replace, succeeded),
              // A replacement whose registration failed did only its first half.
              Write(halfReplaced, ChangeKind.Deregister, succeeded),
              Write(halfReplaced, ChangeKind.Register, failed)
            )
        ).asRight
      )
    ).map: metrics =>
      def change(kind: String, outcome: String) = Map(
        "nomenclator.change.kind" -> kind,
        "nomenclator.change.outcome" -> outcome
      )
      assertEquals(
        points(metrics, "nomenclator.changes"),
        Map(
          change("update", "ok") -> 1.0,
          change("replace", "ok") -> 1.0,
          change("deregister", "ok") -> 2.0,
          change("deregister", "error") -> 1.0,
          change("register", "error") -> 1.0
        )
      )

  test("a failed pass records an error outcome, and the gauges go absent"):
    val foo = owned("foo")
    recorded(
      Mode.Shadow -> PassReport(
        passed,
        outcome(registrations = List(registration(foo))).asRight
      ),
      Mode.Shadow -> PassReport(
        passed,
        new RuntimeException("consul unavailable").asLeft
      )
    ).map: metrics =>
      // Shadow mode, where a stale zero differences would look ready for apply.
      assertEquals(
        points(metrics, "nomenclator.reconcile.duration"),
        Map(
          Map(
            "nomenclator.mode" -> "shadow",
            "nomenclator.reconcile.outcome" -> "ok"
          ) -> 1.0,
          Map(
            "nomenclator.mode" -> "shadow",
            "nomenclator.reconcile.outcome" -> "error"
          ) -> 1.0
        )
      )
      assertEquals(unit(metrics, "nomenclator.reconcile.duration"), Some("s"))
      List(
        "nomenclator.services.desired",
        "nomenclator.services.registered",
        "nomenclator.services.skipped",
        "nomenclator.shadow.differences"
      ).foreach(gauge => assertEquals(points(metrics, gauge), Map.empty, gauge))

  test("before any pass succeeds, no gauge is observed"):
    recorded(
      Mode.Apply -> PassReport(passed, new RuntimeException("boom").asLeft)
    ).map: metrics =>
      assertEquals(points(metrics, "nomenclator.services.desired"), Map.empty)
      assertEquals(
        points(metrics, "nomenclator.services.registered"),
        Map.empty
      )

  test("registered services are split by whether this host owns them"):
    val foo = owned("foo")
    val registrators = ServiceId("other-host:registrator-entry:80")
    val consul = ServiceId("consul")
    recorded(
      Mode.Apply -> PassReport(
        passed,
        outcome(agent = List(foo, registrators, consul).map(agentService)).asRight
      )
    ).map: metrics =>
      assertEquals(
        points(metrics, "nomenclator.services.registered"),
        Map(
          Map("nomenclator.ownership" -> "owned") -> 1.0,
          Map("nomenclator.ownership" -> "other") -> 2.0
        )
      )
      assertEquals(
        unit(metrics, "nomenclator.services.registered"),
        Some("{service}")
      )

  test("shadow mode records differences and writes no changes"):
    val foo = owned("foo")
    val bar = owned("bar")
    val r = registration(foo)
    recorded(
      Mode.Shadow -> PassReport(
        passed,
        outcome(
          registrations = List(r),
          plan = Plan(List(r), List(bar)),
          differences = List(
            Difference.Missing(r),
            Difference.Extra(agentService(bar)),
            Difference.Extra(agentService(owned("baz")))
          )
        ).asRight
      )
    ).map: metrics =>
      assertEquals(points(metrics, "nomenclator.changes"), Map.empty)
      assertEquals(
        points(metrics, "nomenclator.shadow.differences"),
        Map(
          Map("nomenclator.difference.kind" -> "missing") -> 1.0,
          Map("nomenclator.difference.kind" -> "extra") -> 2.0,
          Map("nomenclator.difference.kind" -> "different") -> 0.0
        )
      )
      assertEquals(
        unit(metrics, "nomenclator.shadow.differences"),
        Some("{service}")
      )
      assertEquals(
        points(metrics, "nomenclator.reconcile.duration").keySet,
        Set(
          Map(
            "nomenclator.mode" -> "shadow",
            "nomenclator.reconcile.outcome" -> "ok"
          )
        )
      )

  test("apply mode records no shadow differences"):
    val foo = owned("foo")
    recorded(
      Mode.Apply -> PassReport(
        passed,
        outcome(differences =
          List(
            Difference.Different(
              registration(foo),
              agentService(foo),
              NonEmptyList.one("tags")
            )
          )
        ).asRight
      )
    ).map(metrics => assertEquals(points(metrics, "nomenclator.shadow.differences"), Map.empty))

  test("task definition lookups are counted by result"):
    OtelJavaTestkit
      .inMemory[IO]()
      .use: testkit =>
        testkit.meterProvider
          .get("test")
          .flatMap: meter =>
            given Meter[IO] = meter
            NomenclatorMetrics[IO](host).use(metrics =>
              List(
                LookupResult.Fetched,
                LookupResult.Cached,
                LookupResult.Cached,
                LookupResult.Failed
              ).traverse_(metrics.taskDefinitionLookup) >>
                testkit.collectMetrics.map: metrics =>
                  assertEquals(
                    points(metrics, "nomenclator.task_definition.lookups"),
                    Map(
                      Map("nomenclator.lookup.result" -> "fetched") -> 1.0,
                      Map("nomenclator.lookup.result" -> "cached") -> 2.0,
                      Map("nomenclator.lookup.result" -> "failed") -> 1.0
                    )
                  )
                  assertEquals(
                    unit(metrics, "nomenclator.task_definition.lookups"),
                    Some("{lookup}")
                  )
            )
end NomenclatorMetricsSuite
