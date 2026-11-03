package com.dwolla.nomenclator.reconcile

import scala.concurrent.duration.FiniteDuration

/** One pass of the reconcile loop. `outcome` is `Left` when the pass could not
  * read the ECS agent or the Consul agent, in which case it wrote nothing.
  */
final case class PassReport(duration: FiniteDuration, outcome: Either[Throwable, PassOutcome])
