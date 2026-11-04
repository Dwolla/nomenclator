package com.dwolla.nomenclator.reconcile

import scala.concurrent.duration.*

/** How long the reconcile loop keeps going without a successful pass before it
  * gives up, so that ECS restarts the task.
  */
final class FailurePolicy private (val limit: FiniteDuration):

  /** True once no pass has succeeded for `limit`.
    */
  def exceededBy(failingFor: FiniteDuration): Boolean = failingFor >= limit
end FailurePolicy

object FailurePolicy:
  def exitAfter(consecutiveFailure: FiniteDuration): FailurePolicy =
    new FailurePolicy(consecutiveFailure)

  val default: FailurePolicy = exitAfter(2.minutes)
end FailurePolicy
