package com.dwolla.nomenclator.telemetry

import cats.effect.*
import cats.effect.std.{Env, Random}
import cats.effect.syntax.all.*
import cats.effect.unsafe.metrics.IORuntimeMetrics as RuntimeMetrics
import com.dwolla.otel4s.oteljava.OtelJavaAtDwolla
import com.dwolla.otel4s.oteljava.awsecs.*
import com.dwolla.tracing.DwollaEnvironment
import org.http4s.client.Client
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.otel4s.instrumentation.ce.IORuntimeMetrics
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

/** Starts OpenTelemetry on the OpenTelemetry Java SDK, exporting traces and
  * metrics with Dwolla's defaults. Any `OTEL_*` environment variable overrides
  * a default.
  */
object Telemetry:
  private val serviceName = "nomenclator"

  /** The providers every span and metric comes from. Spans carry the ECS task's
    * resource attributes, detected through `client` at startup; outside ECS
    * there are none. Cats Effect's runtime metrics, CPU starvation in
    * particular, are registered from `runtimeMetrics`. Pending telemetry is
    * flushed when the resource is released.
    *
    * `Async` is required because dwolla-otel4s-oteljava's `OtelJavaAtDwolla`
    * demands it; `LiftIO` because the SDK keeps its context in an `IOLocal`;
    * `runtimeMetrics` because only the application holds its `IORuntime`.
    */
  def resource[F[_]: Async: LiftIO: LoggerFactory: Env](
                                                         env: DwollaEnvironment,
                                                         version: String,
                                                         client: Client[F],
                                                         runtimeMetrics: RuntimeMetrics
  ): Resource[F, (TracerProvider[F], MeterProvider[F])] =
    for
      given Random[F] <- Random.scalaUtilRandom[F].toResource
      (tracerProvider, meterProvider) <- OtelJavaAtDwolla[F](
        serviceName,
        version,
        env
      ).withTracing.withMetrics
        .withEcsTelemetryResourceOnTraces(client)
        .build
      _ <-
        given MeterProvider[F] = meterProvider
        IORuntimeMetrics.register[F](
          runtimeMetrics,
          IORuntimeMetrics.Config.default
        )
    yield (tracerProvider, meterProvider)
end Telemetry
