package com.dwolla.nomenclator.model

/** A container image reference.
  */
opaque type ImageName = String

object ImageName:
  def from(value: String): Either[String, ImageName] = Right(value)

  extension (self: ImageName) def value: String = self
end ImageName
