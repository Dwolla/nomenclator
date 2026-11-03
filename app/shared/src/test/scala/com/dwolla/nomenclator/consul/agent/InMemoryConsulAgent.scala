package com.dwolla.nomenclator.consul.agent

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.consul.*
import com.dwolla.nomenclator.model.ServiceId
import com.dwolla.nomenclator.registration.Registration

/** A Consul agent held in memory. It records every write attempted, and models
  * what the real agent does with checks: re-registering without a check keeps
  * the old `service:<id>` check, and no check definition is reported.
  */
final class InMemoryConsulAgent private (
    agent: Ref[IO, AgentState],
    stateFailure: Ref[IO, Option[Throwable]],
    failingRegistrations: Ref[IO, Set[ServiceId]],
    hangingRegistrations: Ref[IO, Set[ServiceId]],
    attempted: Ref[IO, Vector[InMemoryConsulAgent.Write]]
) extends ConsulAgent[IO]:
  import InMemoryConsulAgent.Write

  override val advertisedAddress: IO[String] = "10.0.0.1".pure[IO]

  override val state: IO[AgentState] =
    stateFailure.get.flatMap(_.fold(agent.get)(_.raiseError[IO, AgentState]))

  override def register(r: Registration): IO[Unit] =
    attempted.update(_ :+ Write.Register(r)) >>
      hangingRegistrations.get.flatMap(hanging => IO.never.whenA(hanging.contains(r.id))) >>
      failingRegistrations.get.flatMap: failing =>
        if (failing.contains(r.id))
          ConsulAgentError("PUT", "/v1/agent/service/register", 500, "boom")
            .raiseError[IO, Unit]
        else agent.update(registered(_, r))

  override def deregister(id: ServiceId): IO[Unit] =
    attempted.update(_ :+ Write.Deregister(id)) >>
      agent.update(s =>
        AgentState(
          s.services.filterNot(_.id == id),
          s.checks.filterNot(_.serviceId.contains(id))
        )
      )

  private def registered(s: AgentState, r: Registration): AgentState =
    val service =
      AgentService(r.id, r.name, r.tags, r.address, r.port.value.value)
    val checkId = s"service:${r.id.value}"
    val checks = r.check.fold(s.checks)(_ =>
      s.checks.filterNot(_.checkId == checkId) :+
        AgentCheck(checkId, r.id.some, None)
    )
    AgentState(s.services.filterNot(_.id == r.id) :+ service, checks)

  /** What the agent holds, as `state` would return it without a failure.
    */
  val current: IO[AgentState] = agent.get

  def seed(state: AgentState): IO[Unit] = agent.set(state)

  def failState(e: Option[Throwable]): IO[Unit] = stateFailure.set(e)

  def failRegistrationsOf(ids: Set[ServiceId]): IO[Unit] =
    failingRegistrations.set(ids)

  /** Registering these IDs never gets an answer.
    */
  def hangRegistrationsOf(ids: Set[ServiceId]): IO[Unit] =
    hangingRegistrations.set(ids)

  val writes: IO[Vector[Write]] = attempted.get
end InMemoryConsulAgent

object InMemoryConsulAgent:
  enum Write:
    case Register(r: Registration)
    case Deregister(id: ServiceId)

  def apply(initial: AgentState): IO[InMemoryConsulAgent] =
    (
      Ref.of[IO, AgentState](initial),
      Ref.of[IO, Option[Throwable]](None),
      Ref.of[IO, Set[ServiceId]](Set.empty),
      Ref.of[IO, Set[ServiceId]](Set.empty),
      Ref.of[IO, Vector[Write]](Vector.empty)
    ).mapN(new InMemoryConsulAgent(_, _, _, _, _))
end InMemoryConsulAgent
