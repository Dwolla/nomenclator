package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.model.ServiceId

/** One change a pass made, or tried to make, to the Consul agent. A replacement
  * whose halves did not both succeed is reported as what actually happened: a
  * deregistration followed by a registration, or a failed deregistration
  * followed by an update.
  */
final case class Write(id: ServiceId, kind: ChangeKind, result: Either[Throwable, Unit])
