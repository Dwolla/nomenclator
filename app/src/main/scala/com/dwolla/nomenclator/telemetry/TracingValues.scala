package com.dwolla.nomenclator.telemetry

import com.dwolla.nomenclator.consul.circe.ConsulCodecs
import com.dwolla.nomenclator.consul.model.{AgentState, ServiceAddress, ServiceId}
import com.dwolla.nomenclator.ecs.model.{EcsTask, InstanceMetadata}
import com.dwolla.nomenclator.registration.Registration
import com.dwolla.tracing.otel4s.ToAnyValue
import org.typelevel.otel4s.AnyValue

/** How Nomenclator's values appear in spans: the `ToAnyValue` instances each
  * algebra's derived `AnyValueAspect` needs. Import its givens in the file that
  * declares the algebra, so the `derives` clause sees them.
  */
object TracingValues:

  /** An input: the service ID as Consul knows it.
    */
  given ToAnyValue[ServiceId] =
    ToAnyValue.instance(id => AnyValue.string(id.value))

  /** An input: the registration exactly as it is sent to the agent.
    */
  given ToAnyValue[Registration] =
    ToAnyValue.fromEncoder(using ConsulCodecs.registerBody)

  // Results. Instrumentation traces with traceWithInputs, which never records
  // them, so each is a summary rather than the whole value.

  given ToAnyValue[ServiceAddress] =
    ToAnyValue.instance(address => AnyValue.string(address.value))

  given ToAnyValue[AgentState] = ToAnyValue.instance(state =>
    AnyValue.string(
      s"${state.services.size} services, ${state.checks.size} checks"
    )
  )

  given ToAnyValue[InstanceMetadata] =
    ToAnyValue.instance(metadata => AnyValue.string(metadata.cluster))

  given ToAnyValue[EcsTask] =
    ToAnyValue.instance(task => AnyValue.string(task.arn.value))
end TracingValues
