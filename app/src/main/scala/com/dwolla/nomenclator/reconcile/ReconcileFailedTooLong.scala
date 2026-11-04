package com.dwolla.nomenclator.reconcile

import scala.concurrent.duration.FiniteDuration

/** No reconcile pass succeeded for `limit`, so the loop gave up.
  */
final case class ReconcileFailedTooLong(limit: FiniteDuration)
    extends RuntimeException(
      s"No reconcile pass has succeeded for $limit; giving up so the task is restarted"
    )
