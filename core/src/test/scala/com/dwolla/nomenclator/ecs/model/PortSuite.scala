package com.dwolla.nomenclator.ecs.model

import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll

class PortSuite extends ScalaCheckSuite:
  property("ContainerPort.from succeeds exactly for 0..65535"):
    forAll((n: Int) => ContainerPort.from(n).isRight == (n >= 0 && n <= 65535))

  property("HostPort.from succeeds exactly for 0..65535"):
    forAll((n: Int) => HostPort.from(n).isRight == (n >= 0 && n <= 65535))

  test("accepts the boundaries and rejects just beyond them"):
    assert(ContainerPort.from(0).isRight)
    assert(ContainerPort.from(65535).isRight)
    assert(ContainerPort.from(-1).isLeft)
    assert(ContainerPort.from(65536).isLeft)
end PortSuite
