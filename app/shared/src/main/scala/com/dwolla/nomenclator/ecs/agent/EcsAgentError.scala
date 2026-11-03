package com.dwolla.nomenclator.ecs.agent

/** The ECS agent answered an introspection request with a non-2xx status.
  */
final case class EcsAgentError(method: String, path: String, status: Int) extends RuntimeException(
      s"ECS agent answered $method $path with status $status"
    )
