package com.dwolla.nomenclator.reconcile

/** Whether the reconcile loop writes its plan to the Consul agent (`Apply`) or
  * only reports how the agent differs from it (`Shadow`).
  */
enum Mode:
  case Apply, Shadow
