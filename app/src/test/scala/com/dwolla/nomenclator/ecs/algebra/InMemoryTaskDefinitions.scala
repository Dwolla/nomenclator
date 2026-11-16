package com.dwolla.nomenclator.ecs.algebra

import cats.effect.*
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.algebra.InMemoryTaskDefinitions.Answers
import com.dwolla.nomenclator.ecs.model.{ContainerName, TaskDefinitionRef}
import com.dwolla.nomenclator.registration.settings.ContainerSettings

/** Task definitions whose answers a test sets. An unknown ref fails like a
  * missing task definition.
  */
final class InMemoryTaskDefinitions private (
    answers: Ref[IO, Answers],
    retainedSets: Ref[IO, Vector[Set[TaskDefinitionRef]]]
) extends TaskDefinitions[IO]:
  override def settings(ref: TaskDefinitionRef): IO[Map[ContainerName, ContainerSettings]] =
    answers.get.flatMap(
      _.getOrElse(ref, TaskDefinitionMissingError(ref).raiseError)
    )

  /** Answers are what the test set, so nothing is forgotten; the call is only
    * recorded.
    */
  override def retainOnly(refs: Set[TaskDefinitionRef]): IO[Unit] =
    retainedSets.update(_ :+ refs)

  /** The refs of each `retainOnly` call, in order.
    */
  val retained: IO[Vector[Set[TaskDefinitionRef]]] = retainedSets.get

  def fail(ref: TaskDefinitionRef, e: Throwable): IO[Unit] =
    answers.update(_.updated(ref, e.raiseError))

  def answer(ref: TaskDefinitionRef, settings: Map[ContainerName, ContainerSettings]): IO[Unit] =
    answers.update(_.updated(ref, settings.pure[IO]))

  /** Looking up `ref` never gets an answer.
    */
  def hang(ref: TaskDefinitionRef): IO[Unit] =
    answers.update(_.updated(ref, IO.never))
end InMemoryTaskDefinitions

object InMemoryTaskDefinitions:
  private type Answers =
    Map[TaskDefinitionRef, IO[Map[ContainerName, ContainerSettings]]]

  def apply(known: Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]]): IO[InMemoryTaskDefinitions] =
    (
      Ref.of[IO, Answers](known.view.mapValues(_.pure[IO]).toMap),
      Ref.of[IO, Vector[Set[TaskDefinitionRef]]](Vector.empty)
    ).mapN(new InMemoryTaskDefinitions(_, _))
end InMemoryTaskDefinitions
