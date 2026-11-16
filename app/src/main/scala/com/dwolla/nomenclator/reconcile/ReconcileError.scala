package com.dwolla.nomenclator.reconcile

import scala.concurrent.duration.FiniteDuration

/** Why the reconcile loop, or one of its passes, failed.
  */
enum ReconcileError(message: String) extends RuntimeException(message):

  /** A reconcile pass did not finish within `timeout`, so it was abandoned.
    */
  case PassTimedOut(timeout: FiniteDuration)
      extends ReconcileError(s"Reconcile pass did not finish within $timeout")

  /** No reconcile pass succeeded for `limit`, so the loop gave up.
    */
  case FailedTooLong(limit: FiniteDuration)
      extends ReconcileError(
        s"No reconcile pass has succeeded for $limit; giving up so the task is restarted"
      )
end ReconcileError
