package com.dwolla.nomenclator.ecs.agent

import com.dwolla.nomenclator.ecs.{EcsTask, InstanceMetadata}

/** The ECS container agent's introspection API, as far as Nomenclator uses it.
  */
trait EcsAgentIntrospection[F[_]]:

  /** The cluster and container instance this agent serves.
    */
  def metadata: F[InstanceMetadata]

  /** Every task the agent knows about, including stopped tasks it hasn't
    * cleaned up yet. Fails rather than returning a partial list.
    */
  def tasks: F[List[EcsTask]]
end EcsAgentIntrospection
