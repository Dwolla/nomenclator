package com.dwolla.nomenclator.plan

import cats.data.NonEmptyList
import com.dwolla.nomenclator.consul.AgentService
import com.dwolla.nomenclator.registration.Registration

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
