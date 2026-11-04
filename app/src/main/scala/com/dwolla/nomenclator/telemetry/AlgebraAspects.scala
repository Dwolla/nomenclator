package com.dwolla.nomenclator.telemetry

import cats.tagless.Trivial
import cats.tagless.aop.Aspect
import cats.tagless.aop.Aspect.{Advice, Weave}
import cats.{Eval, ~>}
import com.dwolla.nomenclator.consul.AgentState
import com.dwolla.nomenclator.consul.agent.{ConsulAgent, ConsulCodecs}
import com.dwolla.nomenclator.ecs.agent.EcsAgentIntrospection
import com.dwolla.nomenclator.ecs.{EcsTask, InstanceMetadata}
import com.dwolla.nomenclator.model.{ServiceAddress, ServiceId}
import com.dwolla.nomenclator.registration.Registration
import com.dwolla.tracing.otel4s.ToAnyValue
import org.typelevel.otel4s.AnyValue

/** How each algebra's calls are traced: under `Algebra.method`, with the call's
  * inputs and never what it returns.
  *
  * Written by hand: cats-tagless can derive these, but its derivation is
  * experimental, which would make every place that uses them experimental too.
  */
object AlgebraAspects:
  private type Woven[F[_]] = [A] =>> Weave[F, ToAnyValue, Trivial, A]

  /** Inputs are a service ID, or the registration exactly as it is sent to the
    * agent.
    */
  given consulAgent: Aspect[ConsulAgent, ToAnyValue, Trivial] =
    new Aspect[ConsulAgent, ToAnyValue, Trivial]:
      override def weave[F[_]](af: ConsulAgent[F]): ConsulAgent[Woven[F]] = new ConsulAgent[Woven[F]]:
        private def call[A](method: String, result: F[A])(inputs: Advice[Eval, ToAnyValue]*) =
          woven("ConsulAgent", method, result, inputs*)

        override def advertisedAddress: Weave[F, ToAnyValue, Trivial, ServiceAddress] =
          call("advertisedAddress", af.advertisedAddress)()

        override def state: Weave[F, ToAnyValue, Trivial, AgentState] =
          call("state", af.state)()

        override def register(r: Registration): Weave[F, ToAnyValue, Trivial, Unit] =
          call("register", af.register(r))(Advice.byValue("r", r))

        override def deregister(id: ServiceId): Weave[F, ToAnyValue, Trivial, Unit] =
          call("deregister", af.deregister(id))(Advice.byValue("id", id))

      override def mapK[F[_], G[_]](af: ConsulAgent[F])(fk: F ~> G): ConsulAgent[G] = new ConsulAgent[G]:
        override def advertisedAddress: G[ServiceAddress] =
          fk(af.advertisedAddress)
        override def state: G[AgentState] = fk(af.state)
        override def register(r: Registration): G[Unit] = fk(af.register(r))
        override def deregister(id: ServiceId): G[Unit] =
          fk(af.deregister(id))

  /** No method takes an input.
    */
  given ecsAgentIntrospection: Aspect[EcsAgentIntrospection, ToAnyValue, Trivial] =
    new Aspect[EcsAgentIntrospection, ToAnyValue, Trivial]:
      override def weave[F[_]](af: EcsAgentIntrospection[F]): EcsAgentIntrospection[Woven[F]] =
        new EcsAgentIntrospection[Woven[F]]:
          override def metadata: Weave[F, ToAnyValue, Trivial, InstanceMetadata] =
            woven("EcsAgentIntrospection", "metadata", af.metadata)

          override def tasks: Weave[F, ToAnyValue, Trivial, List[EcsTask]] =
            woven("EcsAgentIntrospection", "tasks", af.tasks)

      override def mapK[F[_], G[_]](af: EcsAgentIntrospection[F])(fk: F ~> G): EcsAgentIntrospection[G] =
        new EcsAgentIntrospection[G]:
          override def metadata: G[InstanceMetadata] = fk(af.metadata)
          override def tasks: G[List[EcsTask]] = fk(af.tasks)

  private given ToAnyValue[ServiceId] =
    ToAnyValue.instance(id => AnyValue.string(id.value))

  private given ToAnyValue[Registration] =
    ToAnyValue.fromEncoder(using ConsulCodecs.registerBody)

  /** One call: its inputs form a single parameter list, as cats-tagless's
    * derivation would record them.
    */
  private def woven[F[_], A](
                              algebra: String,
                              method: String,
                              result: F[A],
                              inputs: Advice[Eval, ToAnyValue]*
  ): Weave[F, ToAnyValue, Trivial, A] =
    Weave(
      algebra,
      if (inputs.isEmpty) Nil else List(inputs.toList),
      Advice[F, Trivial, A](method, result)
    )
end AlgebraAspects
