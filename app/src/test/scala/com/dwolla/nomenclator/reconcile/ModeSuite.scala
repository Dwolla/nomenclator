package com.dwolla.nomenclator.reconcile

import munit.FunSuite

class ModeSuite extends FunSuite:
  test("each mode is spelled in lowercase"):
    assertEquals(Mode.values.toList.map(_.value), List("apply", "shadow"))

  test("each mode's spelling reads back as that mode"):
    Mode.values.foreach(mode => assertEquals(Mode.fromString(mode.value), Some(mode)))

  test("any other spelling is no mode"):
    List("Apply", "SHADOW", "", "dry-run").foreach(s => assertEquals(Mode.fromString(s), None, s))
end ModeSuite
