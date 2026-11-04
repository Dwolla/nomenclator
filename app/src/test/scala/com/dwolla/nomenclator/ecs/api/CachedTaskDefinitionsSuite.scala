package com.dwolla.nomenclator.ecs.api

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.amazonaws.ecs.*
import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.model.{ContainerName, TaskFamily}
import com.dwolla.nomenclator.settings.ContainerSettings
import munit.CatsEffectSuite

import scala.concurrent.duration.*

class CachedTaskDefinitionsSuite extends CatsEffectSuite:
  private val ref = TaskDefinitionRef(TaskFamily("foo-service"), 7)
  private val ignoreLookups: LookupResult => IO[Unit] = _ => IO.unit

  private def described(family: String) = DescribeTaskDefinitionResponse(
    taskDefinition = Some(
      TaskDefinition(containerDefinitions =
        Some(
          List(
            ContainerDefinition(
              name = Some(family),
              environment = Some(
                List(
                  KeyValuePair(Some("SERVICE_NAME"), Some(family)),
                  KeyValuePair(Some("DB_PASSWORD"), Some("hunter2-test"))
                )
              )
            )
          )
        )
      )
    )
  )

  /** An ECS that answers from `answers` in order (the last answer repeats) and
    * records each requested task definition.
    */
  private def countingEcs(answers: List[IO[DescribeTaskDefinitionResponse]]): IO[(Ref[IO, List[String]], ECS[IO])] =
    (
      Ref.of[IO, List[String]](Nil),
      Ref.of[IO, List[IO[DescribeTaskDefinitionResponse]]](answers)
    )
      .mapN: (requests, remaining) =>
        requests -> new ECS[IO]:
          override def describeTaskDefinition
            (taskDefinition: String, include: Option[List[TaskDefinitionField]]): IO[DescribeTaskDefinitionResponse] =
            requests.update(taskDefinition :: _) >>
              remaining
                .modify:
                  case last :: Nil  => (List(last), last)
                  case next :: rest => (rest, next)
                  case Nil          => (Nil, IO.never)
                .flatten

  test("two lookups of one ref make one ECS call with family:revision"):
    for
      (requests, ecs) <- countingEcs(List(described("foo-service").pure[IO]))
      defs <- CachedTaskDefinitions(ecs, ignoreLookups)
      first <- defs.settings(ref)
      second <- defs.settings(ref)
      seen <- requests.get
    yield
      assertEquals(seen, List("foo-service:7"))
      assertEquals(first, second)
      assertEquals(
        first,
        Map(
          ContainerName("foo-service") ->
            ContainerSettings.fromRaw(
              Map("SERVICE_NAME" -> "foo-service"),
              Map.empty
            )
        )
      )

  test("different refs are cached separately"):
    for
      (requests, ecs) <- countingEcs(List(described("foo-service").pure[IO]))
      defs <- CachedTaskDefinitions(ecs, ignoreLookups)
      _ <- defs.settings(ref)
      _ <- defs.settings(ref.copy(revision = 8))
      seen <- requests.get
    yield assertEquals(seen.reverse, List("foo-service:7", "foo-service:8"))

  test("retainOnly forgets every other ref, which is fetched again"):
    val other = ref.copy(revision = 8)
    for
      (requests, ecs) <- countingEcs(List(described("foo-service").pure[IO]))
      defs <- CachedTaskDefinitions(ecs, ignoreLookups)
      _ <- defs.settings(ref)
      _ <- defs.settings(other)
      _ <- defs.retainOnly(Set(ref))
      _ <- defs.settings(ref)
      _ <- defs.settings(other)
      seen <- requests.get
    yield assertEquals(
      seen.reverse,
      List("foo-service:7", "foo-service:8", "foo-service:8")
    )

  test("a failed call is not cached and its error reaches the caller"):
    val boom = new RuntimeException("ecs unavailable")
    for
      (requests, ecs) <- countingEcs(
        List(IO.raiseError(boom), described("foo-service").pure[IO])
      )
      defs <- CachedTaskDefinitions(ecs, ignoreLookups)
      failed <- defs.settings(ref).attempt
      recovered <- defs.settings(ref)
      again <- defs.settings(ref)
      seen <- requests.get
    yield
      assertEquals(failed, Left(boom))
      assertEquals(recovered, again)
      assertEquals(seen.size, 2)

  test("a response without a task definition fails and is not cached"):
    for
      (requests, ecs) <- countingEcs(
        List(DescribeTaskDefinitionResponse().pure[IO])
      )
      defs <- CachedTaskDefinitions(ecs, ignoreLookups)
      failed <- defs.settings(ref).attempt
      _ <- defs.settings(ref).attempt
      seen <- requests.get
    yield
      assertEquals(failed, Left(TaskDefinitionMissingError(ref)))
      assertEquals(seen.size, 2)

  test("each lookup reports whether it was cached, fetched, or failed"):
    for
      (_, ecs) <- countingEcs(
        List(
          IO.raiseError(new RuntimeException("ecs unavailable")),
          described("foo-service").pure[IO]
        )
      )
      reported <- Ref.of[IO, Vector[LookupResult]](Vector.empty)
      defs <- CachedTaskDefinitions(ecs, r => reported.update(_ :+ r))
      _ <- defs.settings(ref).attempt
      _ <- defs.settings(ref)
      _ <- defs.settings(ref)
      results <- reported.get
    yield assertEquals(
      results,
      Vector(LookupResult.Failed, LookupResult.Fetched, LookupResult.Cached)
    )

  test("a cancelled lookup is reported as failed"):
    val program = for
      (_, ecs) <- countingEcs(List(IO.never))
      reported <- Ref.of[IO, Vector[LookupResult]](Vector.empty)
      defs <- CachedTaskDefinitions(ecs, r => reported.update(_ :+ r))
      _ <- defs.settings(ref).timeout(5.seconds).attempt
      results <- reported.get
    yield results
    TestControl
      .executeEmbed(program)
      .map(assertEquals(_, Vector(LookupResult.Failed)))
end CachedTaskDefinitionsSuite
