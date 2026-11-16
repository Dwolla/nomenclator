package com.dwolla.nomenclator.telemetry

import cats.effect.MonadCancelThrow
import cats.syntax.all.*
import com.amazonaws.ecs.ECS
import com.dwolla.metrics.otel4s.syntax.*
import com.dwolla.metrics.smithy.RpcRole
import com.dwolla.metrics.smithy.syntax.*
import com.dwolla.tracing.otel4s.{AnyValueAspect, ToAnyValue}
import com.dwolla.tracing.otel4s.syntax.*
import com.dwolla.tracing.smithy.otel4s.syntax.*
import org.typelevel.otel4s.metrics.MeterProvider
import org.typelevel.otel4s.trace.TracerProvider

/** Traces and measures the calls Nomenclator makes to the outside world.
  */
object Instrumentation:

  /** Runs each call to `alg` in a span of its own, beneath the current span,
    * that records the call's inputs but never what it returns; and records the
    * call's duration to `com.dwolla.code.function.duration`. The duration is
    * measured inside the span, so a slow call can be linked to its trace. The
    * algebras instrumented this way, `ConsulAgent` and `EcsAgentIntrospection`,
    * derive their aspects in their companions, from the `ToAnyValue` instances
    * in [[TracingValues]].
    */
  def instrumentAlgebra[Alg[_[_]], F[_]: MonadCancelThrow: TracerProvider: MeterProvider]
    (alg: Alg[F])(using AnyValueAspect[Alg]): F[Alg[F]] =
    alg.withMetrics().flatMap(_.traceWithInputs[ToAnyValue])

  /** As for an algebra, recording each call's duration to
    * `rpc.client.call.duration`. Only the request is recorded: a
    * `DescribeTaskDefinition` response holds the container's whole environment,
    * secrets included.
    */
  def instrumentEcsClient[F[_]: MonadCancelThrow: TracerProvider: MeterProvider](ecs: ECS[F]): F[ECS[F]] =
    ecs
      .withMetrics(RpcRole.Client)
      .withTracedInputs()
      .withSimpleInstrumentation()
end Instrumentation
