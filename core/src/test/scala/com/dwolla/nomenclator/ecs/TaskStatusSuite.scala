package com.dwolla.nomenclator.ecs

import munit.FunSuite

class TaskStatusSuite extends FunSuite:
  test("maps known statuses case-insensitively"):
    assertEquals(TaskStatus.fromString("RUNNING"), TaskStatus.Running)
    assertEquals(TaskStatus.fromString("pending"), TaskStatus.Pending)
    assertEquals(TaskStatus.fromString("Stopped"), TaskStatus.Stopped)

  test("keeps unknown statuses"):
    assertEquals(
      TaskStatus.fromString("DEACTIVATING"),
      TaskStatus.Other("DEACTIVATING")
    )
end TaskStatusSuite
