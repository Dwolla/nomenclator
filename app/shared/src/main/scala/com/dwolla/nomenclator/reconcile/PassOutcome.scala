package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.model.ServiceId
import com.dwolla.nomenclator.plan.{Difference, Plan}
import com.dwolla.nomenclator.registration.Desired

/** What a pass that could read its dependencies found and did.
  *
  * @param plan
  *   what apply mode writes, or would write in shadow mode, with the
  *   registrations of tasks awaiting their definitions protected from
  *   deregistration
  * @param differences
  *   the shadow report; empty in apply mode
  * @param failures
  *   the writes that failed, which the next pass retries; empty in shadow mode
  */
final case class PassOutcome
  (desired: Desired, plan: Plan, differences: List[Difference], failures: List[(ServiceId, Throwable)])
