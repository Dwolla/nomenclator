package com.dwolla.nomenclator.plan

import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.model.ServiceId
import com.dwolla.nomenclator.registration.Check
import com.dwolla.nomenclator.settings.GoDuration

import scala.concurrent.duration.*

/** Whether a service's check on the agent is what we want. Consul may not
  * report a check's definition, so the comparison has two modes: compare the
  * definition when it is reported; otherwise trust the check's presence. That
  * is safe because a service ID contains the container's unique Docker name, so
  * a check can't change under an existing ID without the service fields
  * changing too.
  */
object CheckComparison:

  /** The check Consul assigned to each registration: the one with check ID
    * `service:<serviceId>`. Other checks with the same service ID, such as the
    * one `consul maint` adds, are not ours and are ignored, so maintenance mode
    * never causes a re-registration.
    */
  def registrationChecks(state: AgentState): Map[ServiceId, AgentCheck] =
    state.checks
      .flatMap: c =>
        c.serviceId.filter(id => c.checkId == ServiceIds.checkId(id)).map(_ -> c)
      .toMap

  /** True iff the registration's check on the agent is what the desired check
    * calls for: present exactly when desired, and, when the agent reports a
    * definition, a matching one.
    */
  def unchanged(desired: Option[Check], agent: Option[AgentCheck]): Boolean =
    (desired, agent) match
      case (None, None)                  => true
      case (Some(check), Some(reported)) =>
        reported.definition.forall(matches(check, _))
      case _ => false

  /** A reported type must be the check's. The HTTP URL or TCP target must be
    * equal as strings, and the other kind's target absent or empty (agents
    * report unset fields as empty), unless the agent reports a type but neither
    * target, as Consul 1.21 does: then the targets can't be compared, and the
    * type and timing decide. Interval and timeout are compared as parsed Go
    * durations; an absent desired timeout matches an absent or zero one.
    */
  def matches(check: Check, definition: CheckDefinition): Boolean =
    check match
      case Check.Http(url, interval, timeout) =>
        definition.kind.forall(_ == "http") &&
        target(definition, definition.http, url, definition.tcp) &&
        timing(interval, timeout, definition)
      case Check.Tcp(address, interval, timeout) =>
        definition.kind.forall(_ == "tcp") &&
        target(definition, definition.tcp, address, definition.http) &&
        timing(interval, timeout, definition)

  private def target
    (definition: CheckDefinition, reported: Option[String], expected: String, otherKind: Option[String]): Boolean =
    val targetsUnreported =
      definition.kind.isDefined && absent(definition.http) &&
        absent(definition.tcp)
    targetsUnreported || (reported.contains(expected) && absent(otherKind))

  private def absent(target: Option[String]): Boolean = target.forall(_.isEmpty)

  private def timing(interval: FiniteDuration, timeout: Option[FiniteDuration], definition: CheckDefinition): Boolean =
    definition.interval.flatMap(parse).contains(interval) &&
      (timeout match
        case Some(expected) =>
          definition.timeout.flatMap(parse).contains(expected)
        case None => definition.timeout.forall(parse(_).contains(Duration.Zero)))

  private def parse(s: String): Option[FiniteDuration] =
    GoDuration.parse(s).toOption
end CheckComparison
