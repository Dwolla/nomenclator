package com.dwolla.nomenclator.ecs.api

import cats.effect.*
import cats.effect.syntax.all.*
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
    *
    * @param onLookup
    *   runs once for every lookup, with how it was answered; a lookup cancelled
    *   while calling ECS counts as failed
    */
  def apply[F[_]: Concurrent](ecs: ECS[F], onLookup: LookupResult => F[Unit]): F[TaskDefinitions[F]] =
    Ref
      .of[F, Map[TaskDefinitionRef, Map[ContainerName, ContainerSettings]]](
        Map.empty
      )
      .map: cache =>
        new TaskDefinitions[F]:
          override def settings(ref: TaskDefinitionRef): F[Map[ContainerName, ContainerSettings]] =
            cache.get.flatMap(
              _.get(ref).fold(
                describe(ref).guaranteeCase:
                  case Outcome.Succeeded(_)                    => onLookup(LookupResult.Fetched)
                  case Outcome.Errored(_) | Outcome.Canceled() =>
                    onLookup(LookupResult.Failed)
              )(cached => onLookup(LookupResult.Cached).as(cached))
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
