package com.dwolla.nomenclator.plan

import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.model.*
import com.dwolla.nomenclator.registration.Registration

/** Decides what to write to the agent so that it matches the desired
  * registrations, touching only services this host owns: a desired registration
  * whose ID isn't host-shaped is dropped, because it could never be recognized
  * and deregistered later.
  */
object Planner:
  def plan(host: HostName, desired: List[Registration], actual: AgentState): Plan =
    val owned = ServiceIds.owns(host)
    val ours = desired.filter(r => owned(r.id))
    val servicesById = actual.services.map(s => s.id -> s).toMap
    val checks = CheckComparison.registrationChecks(actual)
    val wanted = ours.map(_.id).toSet

    val register = ours.filterNot: r =>
      servicesById
        .get(r.id)
        .exists: s =>
          sameService(r, s) && CheckComparison.unchanged(
            r.check,
            checks.get(r.id)
          )
    // Consul keeps the old check when a service is re-registered without one,
    // so a registration that drops its check replaces the service instead.
    val dropsCheck =
      register.filter(r => r.check.isEmpty && checks.contains(r.id))
    val leftovers = actual.services
      .map(_.id)
      .filter(id => owned(id) && !wanted.contains(id))
    val deregister = (leftovers ++ dropsCheck.map(_.id)).sortBy(_.value)

    Plan(register, deregister)
  end plan

  private def sameService(r: Registration, s: AgentService): Boolean =
    s.name == r.name && s.tags == r.tags && s.address == r.address &&
      s.port == r.port.value.value
end Planner
