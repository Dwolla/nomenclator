package com.dwolla.nomenclator.model

/** A Consul service tag. Empty tags are legal because registrator keeps them.
  */
opaque type Tag = String

object Tag:
  def apply(value: String): Tag = value

  extension (self: Tag) def value: String = self
end Tag
