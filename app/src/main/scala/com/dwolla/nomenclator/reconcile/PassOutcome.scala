package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.consul.AgentState
import com.dwolla.nomenclator.plan.{Difference, Plan}
import com.dwolla.nomenclator.registration.Desired

/** What a pass that could read its dependencies found and did.
  *
  * @param actual
  *   the Consul agent's services and checks, as this pass read them
  * @param plan
  *   what apply mode writes, or would write in shadow mode, with the
  *   registrations of tasks awaiting their definitions protected from
  *   deregistration
  * @param differences
  *   the shadow report; empty in apply mode
  * @param writes
  *   the changes written to the agent, in the order they were made; empty in
  *   shadow mode
  */
final case class PassOutcome(
                              desired: Desired,
                              actual: AgentState,
                              plan: Plan,
                              differences: List[Difference],
                              writes: List[Write]
):

  /** The writes that failed, which the next pass's plan retries.
    */
  def failures: List[Write] = writes.filter(_.result.isLeft)
end PassOutcome
