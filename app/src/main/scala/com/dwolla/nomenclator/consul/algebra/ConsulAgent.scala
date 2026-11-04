package com.dwolla.nomenclator.consul.algebra

import com.dwolla.nomenclator.consul.model.{AgentState, ServiceAddress, ServiceId}
import com.dwolla.nomenclator.registration.Registration

/** The local Consul agent's HTTP API, as far as Nomenclator uses it.
  */
trait ConsulAgent[F[_]]:

  /** The address this agent advertises to the cluster.
    */
  def advertisedAddress: F[ServiceAddress]

  /** The services and checks registered with this agent.
    */
  def state: F[AgentState]

  def register(r: Registration): F[Unit]

  def deregister(id: ServiceId): F[Unit]
end ConsulAgent
