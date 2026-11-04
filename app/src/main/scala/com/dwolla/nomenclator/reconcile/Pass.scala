package com.dwolla.nomenclator.reconcile

import com.dwolla.nomenclator.consul.model.{AgentState, ServiceId}
import com.dwolla.nomenclator.plan.{Difference, Plan}
import com.dwolla.nomenclator.registration.Desired

import scala.concurrent.duration.FiniteDuration

/** One pass of the reconcile loop. `outcome` is `Left` when the pass failed. A
  * pass that could not read the ECS agent or the Consul agent wrote nothing;
  * one that timed out ([[PassTimedOut]]) may have completed some of its writes,
  * and the next pass's plan retries the rest.
  */
final case class PassReport(duration: FiniteDuration, outcome: Either[Throwable, PassOutcome])

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

/** One change a pass made, or tried to make, to the Consul agent. A replacement
  * whose halves did not both succeed is reported as what actually happened: a
  * deregistration followed by a registration, or a failed deregistration
  * followed by an update.
  */
final case class Write(id: ServiceId, kind: ChangeKind, result: Either[Throwable, Unit])

/** What a write to the Consul agent did to a service, as logs and metrics name
  * it.
  */
enum ChangeKind(val value: String):

  /** Registered an ID the agent did not hold.
    */
  case Register extends ChangeKind("register")

  /** Registered an ID the agent held, overwriting it.
    */
  case Update extends ChangeKind("update")

  /** Deregistered an ID and registered it again, both successfully: how a
    * service loses its check.
    */
  case Replace extends ChangeKind("replace")

  case Deregister extends ChangeKind("deregister")
end ChangeKind
