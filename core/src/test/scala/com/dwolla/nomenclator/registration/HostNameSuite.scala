package com.dwolla.nomenclator.registration

import com.dwolla.nomenclator.gen.ModelGen.given
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class HostNameSuite extends ScalaCheckSuite:
  test("accepts a typical EC2 host name"):
    assertEquals(
      HostName.from("ip-10-0-0-1").map(_.value),
      Right("ip-10-0-0-1")
    )

  test("rejects an empty host name"):
    assert(HostName.from("").isLeft)

  test("rejects a host name containing a colon"):
    assert(HostName.from("a:b").isLeft)

  property("accepts exactly the non-empty strings without a colon"):
    forAll((s: String) => HostName.from(s).isRight == (s.nonEmpty && !s.contains(':')))

  property("round-trips generated host names"):
    forAll((h: HostName) => HostName.from(h.value).map(_.value) == Right(h.value))
end HostNameSuite
