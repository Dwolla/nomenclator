package com.dwolla.nomenclator.reconcile

/** Whether the reconcile loop writes its plan to the Consul agent (`Apply`) or
  * only reports how the agent differs from it (`Shadow`). `value` is how
  * configuration, logs, and metrics spell it.
  */
enum Mode(val value: String):
  case Apply extends Mode("apply")
  case Shadow extends Mode("shadow")

object Mode:
  def fromString(value: String): Option[Mode] = values.find(_.value == value)
