$version: "2"

namespace nomenclator.ecs

use smithy4s.meta#only

// Generate only the ECS operation Nomenclator calls.
apply com.amazonaws.ecs#DescribeTaskDefinition @only
