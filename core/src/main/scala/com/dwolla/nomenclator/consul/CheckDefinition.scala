package com.dwolla.nomenclator.consul

/** What the Consul agent reports about a check's definition. Older agents
  * report none of it; Consul 1.21 reports the type and timing but not the HTTP
  * URL or TCP target.
  *
  * @param kind
  *   the check's type as Consul names it, such as `http` or `tcp`
  */
final case class CheckDefinition(
                                  kind: Option[String],
                                  http: Option[String],
                                  tcp: Option[String],
                                  interval: Option[String],
                                  timeout: Option[String]
)
