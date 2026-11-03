package com.dwolla.nomenclator.plan

import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.gen.PlanGen
import com.dwolla.nomenclator.model.ServiceId

/** A test-only model of a Consul agent, as observed on Consul 1.0.6 and 1.21:
  *   - registering replaces the service, and a registration with a check
  *     replaces the `service:<id>` check, but one without a check keeps the
  *     existing `service:<id>` check;
  *   - other checks, such as maintenance mode, survive re-registration;
  *   - deregistering removes the service and all of its checks.
  * Applying a [[Plan]] runs deregistrations before registrations. Whether the
  * agent reports a check's definition varies by service in the simulation, as
  * it may between Consul versions.
  */
object AgentSimulation:
  def apply(plan: Plan, state: AgentState, reportsDefinition: ServiceId => Boolean): AgentState =
    plan.register.foldLeft(deregister(plan.deregister, state)): (agent, r) =>
      val withoutService =
        agent.services.filterNot(_.id == r.id) :+ PlanGen.serviceOf(r)
      r.check.fold(agent.copy(services = withoutService)): c =>
        val check = PlanGen.agentCheckFor(r.id, c)
        AgentState(
          withoutService,
          agent.checks.filterNot(_.checkId == check.checkId) :+
            (if (reportsDefinition(r.id)) check
             else check.copy(definition = None))
        )

  private def deregister(ids: List[ServiceId], state: AgentState) =
    AgentState(
      state.services.filterNot(s => ids.contains(s.id)),
      state.checks.filterNot(_.serviceId.exists(ids.contains))
    )
end AgentSimulation
