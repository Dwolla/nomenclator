package com.dwolla.nomenclator.consul

/** What the Consul agent reports about a check's definition. Older agents may
  * not report it.
  */
final case class CheckDefinition
  (http: Option[String], tcp: Option[String], interval: Option[String], timeout: Option[String])
