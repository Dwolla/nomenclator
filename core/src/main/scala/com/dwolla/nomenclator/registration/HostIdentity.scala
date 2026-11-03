package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.model.{HostName, ServiceAddress}

/** How this container instance identifies itself: `name` goes into service IDs,
  * and `address` is registered as the service address and used as the check
  * target.
  */
final case class HostIdentity(name: HostName, address: ServiceAddress)
