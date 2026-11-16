package com.dwolla.nomenclator.consul.algebra

import com.dwolla.nomenclator.consul.model.{AgentState, ServiceAddress, ServiceId}
import com.dwolla.nomenclator.registration.Registration
import com.dwolla.nomenclator.telemetry.TracingValues.given
import com.dwolla.tracing.otel4s.AnyValueAspect

/** The local Consul agent's HTTP API, as far as Nomenclator uses it.
  */
trait ConsulAgent[F[_]] derives AnyValueAspect:

  /** The address this agent advertises to the cluster.
    */
  def advertisedAddress: F[ServiceAddress]

  /** The services and checks registered with this agent.
    */
  def state: F[AgentState]

  def register(r: Registration): F[Unit]

  def deregister(id: ServiceId): F[Unit]
end ConsulAgent
