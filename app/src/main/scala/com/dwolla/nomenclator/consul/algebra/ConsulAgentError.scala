package com.dwolla.nomenclator.consul.algebra

/** What went wrong asking a Consul agent for something.
  */
enum ConsulAgentError(message: String) extends RuntimeException(message):

  /** A Consul agent answered with a non-2xx status. Carries what is needed to
    * diagnose it, but never the ACL token.
    */
  case UnexpectedStatus(method: String, path: String, status: Int, bodyExcerpt: String) extends ConsulAgentError(
        s"Consul agent answered $method $path with status $status: $bodyExcerpt"
      )

  /** The Consul agent left out results the ACL token may not read, so what it
    * answered is not everything it holds.
    */
  case ResultsFiltered(path: String)
      extends ConsulAgentError(
        s"Consul agent filtered the results of GET $path by ACL; the token must be able to read every service and check on the agent"
      )
end ConsulAgentError
