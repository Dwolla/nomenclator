package com.dwolla.nomenclator.model

/** The ARN of an ECS task.
  */
opaque type TaskArn = String

object TaskArn:
  def apply(value: String): TaskArn = value

  extension (self: TaskArn) def value: String = self
end TaskArn
