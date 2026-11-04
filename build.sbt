import com.typesafe.sbt.packager.Keys.validatePackageValidators
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy}
import com.typesafe.sbt.packager.validation.*

val catsEffectVersion = "3.7.1"
val fs2Version = "3.14.0"
val munitVersion = "1.3.6"
val munitScalaCheckVersion = "1.3.1"
val munitCatsEffectVersion = "2.2.1"
val ip4sVersion = "3.8.0"
val smithy4sVersion = "0.18.59"
val http4sVersion = "0.23.38"
val circeVersion = "0.14.17"
val log4catsVersion = "2.8.0"
val otel4sVersion = "1.1.0"
val dwollaOtel4sVersion = "0.2.11"
val otel4sTaglessVersion = "0.2.7"
val otel4sSmithy4sVersion = "0.1.4"
// The OpenTelemetry Java SDK's version, as dwolla-otel4s-oteljava resolves it.
// Mixing versions on one classpath fails at runtime, so the testkit's SDK
// testing module is pinned to it.
val openTelemetryJavaVersion = "1.67.0"
val testcontainersScalaVersion = "0.44.1"
val cirisVersion = "3.15.2"
val logbackVersion = "1.5.23"
// The slf4j-api version Logback resolves; the JUL bridge must match it.
val slf4jVersion = "2.0.17"

ThisBuild / organization := "com.dwolla"
ThisBuild / organizationName := "Dwolla"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.MIT)
ThisBuild / tlBaseVersion := "0.1"
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / crossScalaVersions := Seq("3.9.0")
ThisBuild / tlFatalWarnings := true
ThisBuild / githubWorkflowOSes := Seq("ubuntu-24.04")
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("25"))
// Nothing goes to Maven Central; .github/workflows/image.yml publishes the image.
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty
ThisBuild / tlCiScalafmtCheck := true
ThisBuild / tlCiDocCheck := true
// A Dependabot branch push would run CI a second time beside its pull request.
ThisBuild / githubWorkflowTargetBranches ++= Seq("!dependabot/**")
// Version tags run ci.yml so a release is built and tested.
ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
// ci.yml uploads no artifacts, so clean.yml would only delete image.yml's, including those of runs still in flight.
ThisBuild / githubWorkflowIncludeClean := false

// The checks a pull request must pass: ci.yml's test job and image.yml's build jobs.
val imageChecks =
  Seq("Image (linux/amd64)", "Image (linux/arm64)")
ThisBuild / mergifyStewardConfig := Some(
  MergifyStewardConfig()
    .withName("automatic merge for Scala Steward patch updates")
    .withAuthor("dwolla-oss-scala-steward[bot]")
    .withAction(MergifyAction.Merge(method = Some("squash")))
)
ThisBuild / mergifySuccessConditions ++= imageChecks.map(check => MergifyCondition.Custom(s"status-success=$check"))
// Major bumps wait for a person: image.yml publishes only on tags, so a pull
// request's CI never exercises a major bump to the login, metadata, or push actions.
ThisBuild / mergifyPrRules += MergifyPrRule(
  "automatic merge for Dependabot pull requests",
  List(
    MergifyCondition.Custom("author~=^dependabot(|-preview)\\[bot\\]$"),
    MergifyCondition.Custom(
      "dependabot-update-type!=version-update:semver-major"
    )
  ) ++ (ThisBuild / mergifySuccessConditions).value,
  List(MergifyAction.Merge(method = Some("squash")))
)
ThisBuild / mergifyLabelPaths := Map.empty

val toolchainImage = taskKey[String](
  "Builds docker/toolchain, the native-image toolchain, and returns its tag"
)

val buildToolchainImage = Def.task {
  val tag = "nomenclator-toolchain:local"
  val directory = (ThisBuild / baseDirectory).value / "docker" / "toolchain"
  // buildx writes its progress to stderr, so both streams log at info; a failure shows as the exit code.
  val log = streams.value.log
  val exit = scala.sys.process.Process(
    Seq("docker", "buildx", "build", "--load", "--tag", tag, "."),
    directory
  ) ! scala.sys.process.ProcessLogger(log.info(_), log.info(_))
  if (exit != 0) sys.error(s"docker buildx build of $tag exited with $exit")
  tag
}

val testDependencies = Seq(
  "org.scalameta" %% "munit" % munitVersion,
  "org.scalameta" %% "munit-scalacheck" % munitScalaCheckVersion,
  "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion,
  "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion
).map(_ % Test)

val integrationTestDependencies = Seq(
  "org.scalameta" %% "munit" % munitVersion,
  "org.scalameta" %% "munit-scalacheck" % munitScalaCheckVersion,
  "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion,
  "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion,
  "com.dimafeng" %% "testcontainers-scala-munit" % testcontainersScalaVersion,
  "org.http4s" %% "http4s-ember-server" % http4sVersion
).map(_ % Test)

lazy val root = project
  .in(file("."))
  .aggregate(core, app, integration)
  .enablePlugins(NoPublishPlugin)

lazy val core = project
  .in(file("core"))
  .enablePlugins(NoPublishPlugin)
  .settings(
    name := "nomenclator-core",
    // native-packager takes dependency jars from packagedArtifacts, which NoPublishPlugin empties; without this the
    // binary is built without nomenclator-core and fails at startup with NoClassDefFoundError.
    Compile / packageBin / publishArtifact := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "com.comcast" %% "ip4s-core" % ip4sVersion
    ) ++ testDependencies
  )

lazy val app = project
  .in(file("app"))
  .enablePlugins(
    Smithy4sCodegenPlugin,
    BuildInfoPlugin,
    NoPublishPlugin,
    GraalVMNativeImagePlugin,
    DockerPlugin
  )
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "nomenclator-app",
    buildInfoKeys := Seq[BuildInfoKey](version),
    buildInfoPackage := "com.dwolla.nomenclator",
    smithy4sAwsSpecs ++= Seq(AWS.ecs),
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %% "smithy4s-aws-http4s" % smithy4sVersion,
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "org.typelevel" %% "log4cats-core" % log4catsVersion,
      "is.cir" %% "ciris" % cirisVersion,
      "org.typelevel" %% "otel4s-core-trace" % otel4sVersion,
      "org.typelevel" %% "otel4s-core-metrics" % otel4sVersion,
      "com.dwolla" %% "otel4s-tagless" % otel4sTaglessVersion,
      "com.dwolla" %% "otel4s-tagless-metrics" % otel4sTaglessVersion,
      "com.dwolla" %% "otel4s-smithy4s" % otel4sSmithy4sVersion,
      "com.dwolla" %% "otel4s-smithy4s-metrics" % otel4sSmithy4sVersion,
      "io.circe" %% "circe-literal" % circeVersion % Test,
      "org.typelevel" %% "log4cats-testing" % log4catsVersion % Test
    ) ++ testDependencies,
    // smithy4s generates identifiers containing `$` (e.g. `$Unknown`), which Scala 3 warns about.
    scalacOptions += "-Wconf:src=.*/src_managed/.*:silent",
    libraryDependencies ++= Seq(
      "com.dwolla" %% "dwolla-otel4s-oteljava" % dwollaOtel4sVersion,
      "com.dwolla" %% "dwolla-otel4s-oteljava-aws-ecs" % dwollaOtel4sVersion,
      "org.typelevel" %% "otel4s-instrumentation-metrics" % otel4sVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      // The OpenTelemetry Java SDK logs its own problems through JUL.
      "org.slf4j" % "jul-to-slf4j" % slf4jVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Runtime,
      "org.typelevel" %% "otel4s-oteljava-testkit" % otel4sVersion % Test,
      "io.opentelemetry" % "opentelemetry-sdk-testing" % openTelemetryJavaVersion % Test
    ),
    // A forked JVM gets its own system properties, logging, and signals.
    run / fork := true,
    toolchainImage := buildToolchainImage.value,
    GraalVMNativeImage / containerBuildImage := Some(toolchainImage.value),
    // native-packager names the binary with -H:Name, which must match native-image.properties' -o. Because it
    // hardcodes -H:Name, native-image warns that the option is experimental (three lines per build).
    GraalVMNativeImage / name := "nomenclator",
    dockerBaseImage := RuntimeBaseImage
      .read(
        (ThisBuild / baseDirectory).value / "docker" / "runtime" / "Dockerfile"
      )
      .fold(sys.error, identity),
    // Root owns /app and everything in it, so the runtime user can't rewrite or replace its own binary. The distroless
    // base has no shell, so the MultiStage and Run strategies' RUN chmod fails. None copies the stage without --chown,
    // and what COPY takes from the build context belongs to root. Two generated commands change. WORKDIR would create
    // /app as the base image's user, 65532, so it goes: COPY creates /app instead, and the base's working directory
    // stays. Staging leaves the binary executable by its owner alone, so COPY sets the mode with --chmod, which needs
    // BuildKit; docker buildx always uses it.
    dockerPermissionStrategy := DockerPermissionStrategy.None,
    dockerCommands := dockerCommands.value.flatMap {
      case Cmd("WORKDIR", _*)     => Seq.empty
      case Cmd("COPY", args @ _*) =>
        Seq(Cmd("COPY", ("--chmod=755" +: args): _*))
      case command => Seq(command)
    },
    // A uid makes the plugin emit `RUN useradd`, and distroless has no shell to run it. The gid is None for the same
    // reason; the base image's passwd already gives group 65532.
    Docker / daemonUserUid := None,
    Docker / daemonUser := "65532",
    Docker / daemonGroupGid := None,
    Docker / daemonGroup := "65532",
    Docker / defaultLinuxInstallLocation := "/app",
    dockerEntrypoint := Seq("/app/nomenclator"),
    dockerLabels := Map("org.opencontainers.image.licenses" -> "MIT"),
    // The image holds the binary and any JDK shared library native-image emitted beside it (libmanagement_ext.so),
    // which the binary loads from there; the default mappings would ship the jars and launcher script instead.
    Docker / mappings := {
      val binary = (GraalVMNativeImage / packageBin).value
      val sharedLibraries = (binary.getParentFile * "*.so").get()
      (binary +: sharedLibraries).map(file => file -> s"/app/${file.getName}")
    },
    // The default validators also warn that the image exposes no ports (Nomenclator listens on nothing) and check the
    // Docker version, which is private to the plugin.
    Docker / validatePackageValidators := {
      val imageFiles = (Docker / mappings).value
      Seq(nonEmptyMappings(imageFiles), filesExist(imageFiles))
    }
  )

lazy val integration = project
  .in(file("integration"))
  .enablePlugins(NoPublishPlugin)
  .dependsOn(app % "test->test")
  .settings(
    name := "nomenclator-integration",
    libraryDependencies ++= integrationTestDependencies
  )

addCommandAlias(
  "check",
  "scalafmtSbtCheck; scalafmtCheckAll; githubWorkflowCheck; Compile/doc; test"
)
