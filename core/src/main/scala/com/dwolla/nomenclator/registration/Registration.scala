package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.consul.model.*
import com.dwolla.nomenclator.ecs.model.*

import scala.concurrent.duration.FiniteDuration

/** A service that should be registered with the Consul agent.
  */
final case class Registration(
                               id: ServiceId,
                               name: ServiceName,
                               tags: List[Tag],
                               address: ServiceAddress,
                               port: HostPort,
                               check: Option[Check]
)

/** A health check that Consul runs against a registered service.
  */
enum Check:
  case Http(url: String, interval: FiniteDuration, timeout: Option[FiniteDuration])
  case Tcp(target: String, interval: FiniteDuration, timeout: Option[FiniteDuration])
