package com.dwolla.nomenclator.aws

/** Fetching task-role credentials failed. The message says only what went wrong
  * (a status, a timeout, an undecodable body), never the request path or
  * anything from the response body, because either could hold credentials.
  * Carries no cause for the same reason.
  */
final case class ContainerCredentialsError(reason: String)
    extends RuntimeException(s"Task-role credentials fetch failed: $reason")
