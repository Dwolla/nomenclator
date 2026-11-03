package com.dwolla.nomenclator.reconcile

import scala.concurrent.duration.FiniteDuration

/** A reconcile pass did not finish within `timeout`, so it was abandoned.
  */
final case class PassTimedOut(timeout: FiniteDuration)
    extends RuntimeException(s"Reconcile pass did not finish within $timeout")
