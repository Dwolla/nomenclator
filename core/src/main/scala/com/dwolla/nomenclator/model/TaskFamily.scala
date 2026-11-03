package com.dwolla.nomenclator.model

/** The family of an ECS task definition, which its revisions share.
  */
opaque type TaskFamily = String

object TaskFamily:
  def apply(value: String): TaskFamily = value

  extension (self: TaskFamily) def value: String = self
end TaskFamily
