package com.dwolla.nomenclator.plan

import com.dwolla.nomenclator.model.ServiceId
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
