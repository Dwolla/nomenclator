package com.dwolla.nomenclator.plan

import cats.data.NonEmptyList
import com.dwolla.nomenclator.consul.model.{AgentService, ServiceId}
import com.dwolla.nomenclator.registration.Registration

/** What to change on the Consul agent. `register` holds both missing and
  * changed services, in desired order. `deregister` holds owned services that
  * are no longer desired, plus services whose check was removed, sorted by ID.
  * Consul keeps a service's old check when it is re-registered without one, so
  * dropping a check means deregistering the ID and registering it again.
  *
  * Appliers must run all deregistrations before any registration.
  */
final case class Plan(register: List[Registration], deregister: List[ServiceId])

/** A way the agent differs from the desired registrations.
  */
enum Difference:

  /** Desired, but nothing on the agent has its name, address, and port.
    */
  case Missing(r: Registration)

  /** On the agent, but nothing desired has its name, address, and port.
    */
  case Extra(s: AgentService)

  /** Matched by name, address, and port; `fields` names what else differs, from
    * `"tags"` and `"check"`.
    */
  case Different(r: Registration, s: AgentService, fields: NonEmptyList[String])
end Difference
