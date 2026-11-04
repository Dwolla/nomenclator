package com.dwolla.nomenclator.reconcile

import munit.FunSuite

import scala.concurrent.duration.*

class ReconcileErrorSuite extends FunSuite:
  test("PassTimedOut says how long the pass was allowed"):
    assertEquals(
      ReconcileError.PassTimedOut(30.seconds).getMessage,
      "Reconcile pass did not finish within 30 seconds"
    )

  test("FailedTooLong says the task is restarted"):
    assertEquals(
      ReconcileError.FailedTooLong(5.minutes).getMessage,
      "No reconcile pass has succeeded for 5 minutes; giving up so the task is restarted"
    )
end ReconcileErrorSuite
