package com.dwolla.nomenclator.telemetry

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.tracing.DwollaEnvironment
import munit.CatsEffectSuite
import org.http4s.Response
import org.http4s.client.Client
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

class TelemetrySuite extends CatsEffectSuite:
  private given LoggerFactory[IO] = NoOpFactory[IO]

  /** Outside ECS, detecting ECS resource attributes makes no request.
    */
  private val unreachable = Client[IO](_ =>
    Resource.raiseError[IO, Response[IO], Throwable](
      new IllegalStateException("no request is expected")
    )
  )

  /** Sets system properties while the resource is in use. OpenTelemetry's
    * autoconfigure reads them, and these keep it from exporting anywhere.
    */
  private def systemProperties(props: (String, String)*): Resource[IO, Unit] =
    Resource
      .make(IO:
        val previous = props.map((k, _) => k -> sys.props.get(k))
        props.foreach(sys.props.update.tupled)
        previous)(previous =>
        IO(previous.foreach:
          case (k, Some(v)) => sys.props.update(k, v)
          case (k, None)    => sys.props.remove(k): Unit)
      )
      .void

  // With no exporter the SDK's tracer reports itself disabled, so this can't
  // tell the SDK from a no-op; it shows the wiring starts, serves, and stops.
  test("starts outside ECS, with runtime metrics registered, and stops"):
    val exporters = List("otel.traces.exporter", "otel.metrics.exporter")
    val before = exporters.map(sys.props.get)
    val noExport = systemProperties(exporters.map(_ -> "none")*)
    noExport
      .flatMap(_ =>
        Telemetry.resource[IO](
          DwollaEnvironment.Local,
          "0.0.0-test",
          unreachable,
          munitIORuntime.metrics
        )
      )
      .use: (tracerProvider, meterProvider) =>
        for
          tracer <- tracerProvider.get("test")
          meter <- meterProvider.get("test")
          counter <- meter.counter[Long]("test.counter").create
          _ <- tracer.span("test").surround(counter.inc())
        yield ()
      .flatMap(_ =>
        IO(exporters.map(sys.props.get))
          .map(assertEquals(_, before, "system properties should be restored"))
      )
end TelemetrySuite
