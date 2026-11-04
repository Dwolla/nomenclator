package com.dwolla.nomenclator.ecs.agent

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.{EcsTask, InstanceMetadata}

/** An ECS agent whose task list, failure, or silence a test sets.
  */
final class InMemoryEcsAgent private (answer: Ref[IO, IO[List[EcsTask]]])
    extends EcsAgentIntrospection[IO]:
  override val metadata: IO[InstanceMetadata] =
    InstanceMetadata("example", "container-instance-arn").pure[IO]

  override val tasks: IO[List[EcsTask]] = answer.get.flatten

  def setTasks(tasks: List[EcsTask]): IO[Unit] = answer.set(tasks.pure[IO])

  def fail(e: Throwable): IO[Unit] = answer.set(e.raiseError[IO, List[EcsTask]])

  /** Requests for tasks never get an answer.
    */
  val hang: IO[Unit] = answer.set(IO.never)
end InMemoryEcsAgent

object InMemoryEcsAgent:
  def apply(tasks: List[EcsTask]): IO[InMemoryEcsAgent] =
    Ref.of[IO, IO[List[EcsTask]]](tasks.pure[IO]).map(new InMemoryEcsAgent(_))
