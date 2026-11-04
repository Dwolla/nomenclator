package com.dwolla.nomenclator.reconcile

import cats.effect.*
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.dwolla.nomenclator.ecs.model.{TaskDefinitionRef, TaskFamily}
import com.dwolla.nomenclator.reconcile.DefinitionLookups.*
import munit.CatsEffectSuite

import java.util.Base64
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

class DefinitionLookupsSuite extends CatsEffectSuite:
  private def ref(family: String) = TaskDefinitionRef(TaskFamily(family), 1)

  private val a = ref("a")
  private val b = ref("b")
  private val c = ref("c")
  private val deadline = DefinitionLookups.deadline(5.seconds)

  /** How a lookup ended, as `within` reports it.
    */
  private def ending(result: Either[Throwable, ?]): String = result match
    case Right(_)                     => "answered"
    case Left(_: TimeoutException)    => "timed out"
    case Left(CutOff(`deadline`))     => "cut off"
    case Left(NotStarted(`deadline`)) => "not started"
    case Left(e)                      => s"failed: $e"

  test(
    "the deadline gives two rounds of lookups their own timeout, and half a round more"
  ):
    assertEquals(deadline, 12500.millis)

  test(
    "with every slot hung, two rounds time out, the third is cut off, and the rest never start"
  ):
    val refs = (1 to 16).toList.map(i => ref(s"hung-$i"))
    val program =
      within(refs, 5.seconds, failedBefore = Nil)(_ => IO.never[Unit])

    TestControl
      .executeEmbed(
        program,
        seed = Base64.getEncoder.encodeToString("1".getBytes).some
      )
      .map: results =>
        assertEquals(results.map(_._1), refs)
        assertEquals(
          results.map((_, result) => ending(result)),
          List.fill(8)("timed out") ++ List.fill(4)("cut off") ++
            List.fill(4)("not started")
        )

  test(
    "a lookup that didn't start, or was cut off by the deadline, keeps its turn"
  ):
    val results = List(
      a -> Left(NotStarted(deadline)),
      b -> Left(CutOff(deadline)),
      c -> Left(CutOff(deadline))
    )
    assertEquals(failed(List(a, b), results), List(a, b))

  test(
    "a lookup that failed or timed out on its own goes behind those still waiting"
  ):
    val results = List(
      a -> Left(new TimeoutException("5 seconds")),
      b -> Left(NotStarted(deadline)),
      c -> Left(new RuntimeException("boom"))
    )
    assertEquals(failed(List(a, b), results), List(b, a, c))

  test("a lookup that succeeds leaves the queue"):
    val results = List(a -> Right(()), b -> Left(CutOff(deadline)))
    assertEquals(failed(List(a, b), results), List(b))
end DefinitionLookupsSuite
