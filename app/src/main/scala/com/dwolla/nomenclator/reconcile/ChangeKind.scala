package com.dwolla.nomenclator.reconcile

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
