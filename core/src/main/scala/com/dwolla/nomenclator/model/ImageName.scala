package com.dwolla.nomenclator.model

/** A container image reference.
  */
opaque type ImageName = String

object ImageName:
  def apply(value: String): ImageName = value

  extension (self: ImageName) def value: String = self
end ImageName
