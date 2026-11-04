package com.dwolla.nomenclator.consul.agent

/** The Consul agent left out results the ACL token may not read, so what it
  * answered is not everything it holds.
  */
final case class ConsulResultsFilteredError(path: String)
    extends RuntimeException(
      s"Consul agent filtered the results of GET $path by ACL; the token must be able to read every service and check on the agent"
    )
