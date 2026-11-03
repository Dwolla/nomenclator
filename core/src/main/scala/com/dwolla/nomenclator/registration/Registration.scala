package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.model.*

/** A service that should be registered with the Consul agent.
  */
final case class Registration(
                               id: ServiceId,
                               name: ServiceName,
                               tags: List[Tag],
                               address: String,
                               port: HostPort,
                               check: Option[Check]
)
