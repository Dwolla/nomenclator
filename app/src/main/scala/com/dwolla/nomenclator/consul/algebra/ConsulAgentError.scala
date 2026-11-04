package com.dwolla.nomenclator.consul.algebra

/** A Consul agent answered with a non-2xx status. Carries what is needed to
  * diagnose it, but never the ACL token.
  */
final case class ConsulAgentError
  (method: String, path: String, status: Int, bodyExcerpt: String) extends RuntimeException(
      s"Consul agent answered $method $path with status $status: $bodyExcerpt"
    )
