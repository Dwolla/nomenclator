package com.dwolla.nomenclator.ecs.api

import cats.effect.*
import cats.syntax.all.*
import com.amazonaws.ecs.ECS
import com.dwolla.nomenclator.ecs.TaskDefinitionRef
import com.dwolla.nomenclator.model.ContainerName
import com.dwolla.nomenclator.settings.ContainerSettings

object CachedTaskDefinitions:

  /** Task definition revisions are immutable, so successes are cached forever.
    * Failures are never cached and propagate to the caller.
    *
    * Concurrent misses for the same ref may each call ECS; the answers are
    * identical, so single-flight machinery isn't worth it.
    */
  def apply[F[_]: Concurrent](ecs: ECS[F]): F[TaskDefinitions[F]] =
    Ref
      .of[F, Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]]](
        Map.empty
      )
      .map: cache =>
        new TaskDefinitions[F]:
          override def settings(ref: TaskDefinitionRef): F[Map[ContainerName, ContainerSettings]] =
            cache.get.flatMap(
              _.get(ref).fold(describe(ref))(_.pure[F])
            )

          private def describe(ref: TaskDefinitionRef): F[Map[ContainerName, ContainerSettings]] =
            for
              response <- ecs.describeTaskDefinition(
                s"${ref.family}:${ref.revision}"
              )
              _ <- TaskDefinitionMissing(ref)
                .raiseError[F, Unit]
                .whenA(response.taskDefinition.isEmpty)
              extracted = ServiceSettingsExtraction(response)
              _ <- cache.update(_.updated(ref, extracted))
            yield extracted
end CachedTaskDefinitions
