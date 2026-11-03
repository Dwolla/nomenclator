package com.dwolla.nomenclator.model

import com.dwolla.nomenclator.gen.ModelGen.given
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class ProtocolSuite extends ScalaCheckSuite:
  test("parses UDP case-insensitively"):
    assertEquals(Protocol.fromString("UDP"), Right(Protocol.Udp))

  test("rejects an unknown protocol"):
    assert(Protocol.fromString("sctp").isLeft)

  property("value round-trips through fromString"):
    forAll((p: Protocol) => Protocol.fromString(p.value) == Right(p))
end ProtocolSuite
