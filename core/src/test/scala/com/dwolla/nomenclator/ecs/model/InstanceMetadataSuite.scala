package com.dwolla.nomenclator.ecs.model

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class InstanceMetadataSuite extends ScalaCheckSuite:
  private def metadata(arn: String) = InstanceMetadata("example", arn)

  test("the region is the fourth field of the container instance ARN"):
    assertEquals(
      metadata(
        "arn:aws:ecs:us-east-1:123456789012:container-instance/example/0123456789abcdef"
      ).region,
      Some("us-east-1")
    )

  test("an ARN in another partition still names its region"):
    assertEquals(
      metadata(
        "arn:aws-us-gov:ecs:us-gov-west-1:123456789012:container-instance/abc"
      ).region,
      Some("us-gov-west-1")
    )

  test("an old-format container instance ARN names its region too"):
    assertEquals(
      metadata(
        "arn:aws:ecs:eu-west-1:123456789012:container-instance/0123456789abcdef"
      ).region,
      Some("eu-west-1")
    )

  test("an ARN without a region names none"):
    assertEquals(
      metadata("arn:aws:ecs::123456789012:container-instance/abc").region,
      None
    )

  test("an ARN of another service names no region"):
    assertEquals(
      metadata("arn:aws:ec2:us-east-1:123456789012:instance/i-0123").region,
      None
    )

  private val field: Gen[String] =
    Gen.oneOf(
      Gen.const("arn"),
      Gen.const("ecs"),
      Gen.const(""),
      Gen.alphaNumStr
    )

  private def fields(max: Int): Gen[List[String]] =
    Gen.choose(0, max).flatMap(Gen.listOfN(_, field))

  /** Colon-separated fields: half of them anything, half shaped like an ECS ARN
    * up to the region, which may be empty.
    */
  private val arnLike: Gen[List[String]] =
    Gen.oneOf(
      fields(8),
      for
        partition <- field
        region <- field
        rest <- fields(3)
      yield "arn" :: partition :: "ecs" :: region :: rest
    )

  property(
    "names the fourth field exactly when the first is arn and the third ecs"
  ):
    forAll(arnLike): fields =>
      val expected = fields match
        case "arn" :: _ :: "ecs" :: region :: _ if region.nonEmpty =>
          Some(region)
        case _ => None
      metadata(fields.mkString(":")).region == expected

  property("a string without colons names no region"):
    forAll(Gen.alphaNumStr)(s => metadata(s).region == None)
end InstanceMetadataSuite
