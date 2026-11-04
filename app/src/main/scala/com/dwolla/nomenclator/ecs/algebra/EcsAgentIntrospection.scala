package com.dwolla.nomenclator.ecs.algebra

import com.dwolla.nomenclator.ecs.model.{EcsTask, InstanceMetadata}
import com.dwolla.nomenclator.telemetry.TracingValues.given
import com.dwolla.tracing.otel4s.AnyValueAspect

/** The ECS container agent's introspection API, as far as Nomenclator uses it.
  */
trait EcsAgentIntrospection[F[_]] derives AnyValueAspect:

  /** The cluster and container instance this agent serves.
    */
  def metadata: F[InstanceMetadata]

  /** Every task the agent knows about, including stopped tasks it hasn't
    * cleaned up yet. Fails rather than returning a partial list.
    */
  def tasks: F[List[EcsTask]]
end EcsAgentIntrospection
