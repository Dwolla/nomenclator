package com.dwolla.nomenclator.registration

import scala.concurrent.duration.FiniteDuration

/** A health check that Consul runs against a registered service.
  */
enum Check:
  case Http(url: String, interval: FiniteDuration, timeout: Option[FiniteDuration])
  case Tcp(target: String, interval: FiniteDuration, timeout: Option[FiniteDuration])
