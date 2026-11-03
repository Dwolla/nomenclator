package com.dwolla.nomenclator.ecs

import java.util.Locale

enum TaskStatus:
  case Pending, Running, Stopped
  case Other(value: String)

object TaskStatus:
  def fromString(value: String): TaskStatus =
    value.toUpperCase(Locale.ROOT) match
      case "PENDING" => Pending
      case "RUNNING" => Running
      case "STOPPED" => Stopped
      case _         => Other(value)
