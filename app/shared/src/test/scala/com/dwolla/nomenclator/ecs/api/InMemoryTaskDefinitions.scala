package com.dwolla.nomenclator.ecs.api

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.ecs.api.InMemoryTaskDefinitions.Answers
import com.dwolla.nomenclator.model.ContainerName
import com.dwolla.nomenclator.settings.ContainerSettings

/** Task definitions whose answers a test sets. An unknown ref fails like a
  * missing task definition.
  */
final class InMemoryTaskDefinitions private (answers: Ref[IO, Answers])
    extends TaskDefinitions[IO]:
  override def settings(ref: TaskDefinitionRef): IO[Map[ContainerName, ContainerSettings]] =
    answers.get.flatMap(
      _.getOrElse(ref, TaskDefinitionMissing(ref).asLeft).liftTo[IO]
    )

  def fail(ref: TaskDefinitionRef, e: Throwable): IO[Unit] =
    answers.update(_.updated(ref, e.asLeft))
end InMemoryTaskDefinitions

object InMemoryTaskDefinitions:
  private type Answers =
    Map[
      TaskDefinitionRef,
      Either[Throwable, Map[ContainerName, ContainerSettings]]
    ]

  def apply(known: Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]]): IO[InMemoryTaskDefinitions] =
    Ref
      .of[IO, Answers](known.view.mapValues(_.asRight).toMap)
      .map(new InMemoryTaskDefinitions(_))
end InMemoryTaskDefinitions
