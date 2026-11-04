package com.dwolla.nomenclator.reconcile

import scala.concurrent.duration.FiniteDuration

/** One pass of the reconcile loop. `outcome` is `Left` when the pass failed. A
  * pass that could not read the ECS agent or the Consul agent wrote nothing;
  * one that timed out ([[PassTimedOut]]) may have completed some of its writes,
  * and the next pass's plan retries the rest.
  */
final case class PassReport(duration: FiniteDuration, outcome: Either[Throwable, PassOutcome])
