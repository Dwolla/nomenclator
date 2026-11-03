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
val testcontainersScalaVersion = "0.44.1"

ThisBuild / organization := "com.dwolla"
ThisBuild / tlBaseVersion := "0.1"
ThisBuild / scalaVersion := "3.9.0"
ThisBuild / crossScalaVersions := Seq("3.9.0")
ThisBuild / tlFatalWarnings := true
// The project has no license, so there are no license headers to check.
ThisBuild / headerCreate / skip := true
ThisBuild / headerCheck / skip := true

val testDependencies = Def.setting(
  Seq(
    "org.scalameta" %%% "munit" % munitVersion,
    "org.scalameta" %%% "munit-scalacheck" % munitScalaCheckVersion,
    "org.typelevel" %%% "munit-cats-effect" % munitCatsEffectVersion,
    "org.typelevel" %%% "cats-effect-testkit" % catsEffectVersion
  ).map(_ % Test)
)

val integrationTestDependencies = Seq(
  "org.scalameta" %% "munit" % munitVersion,
  "org.scalameta" %% "munit-scalacheck" % munitScalaCheckVersion,
  "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion,
  "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion,
  "com.dimafeng" %% "testcontainers-scala-munit" % testcontainersScalaVersion,
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  // testcontainers logs through slf4j 1.x, which warns on stderr without a binding.
  "org.slf4j" % "slf4j-nop" % "1.7.36"
).map(_ % Test)

lazy val root = tlCrossRootProject
  .aggregate(core, app, integration)
  .enablePlugins(NoPublishPlugin)

lazy val core = crossProject(JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "nomenclator-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %%% "cats-effect" % catsEffectVersion,
      "co.fs2" %%% "fs2-core" % fs2Version,
      "com.comcast" %%% "ip4s-core" % ip4sVersion
    ) ++ testDependencies.value
  )

lazy val app = crossProject(JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("app"))
  .enablePlugins(Smithy4sCodegenPlugin, NoPublishPlugin)
  .dependsOn(core % "compile->compile;test->test")
  .settings(
    name := "nomenclator-app",
    // Smithy sources are shared across platforms, so they live under app/shared.
    Compile / smithy4sInputDirs := Seq(
      (ThisBuild / baseDirectory).value / "app" / "shared" / "src" / "main" / "smithy"
    ),
    smithy4sAwsSpecs ++= Seq(AWS.ecs),
    libraryDependencies ++= Seq(
      "com.disneystreaming.smithy4s" %%% "smithy4s-aws-http4s" % smithy4sVersion,
      "org.http4s" %%% "http4s-ember-client" % http4sVersion,
      "org.http4s" %%% "http4s-circe" % http4sVersion,
      "org.typelevel" %%% "log4cats-core" % log4catsVersion,
      "org.typelevel" %%% "otel4s-core-trace" % otel4sVersion,
      "io.circe" %%% "circe-literal" % circeVersion % Test,
      "org.typelevel" %%% "log4cats-testing" % log4catsVersion % Test
    ) ++ testDependencies.value,
    // smithy4s generates identifiers containing `$` (e.g. `$Unknown`), which Scala 3 warns about.
    scalacOptions += "-Wconf:src=.*/src_managed/.*:silent"
  )

lazy val integration = project
  .in(file("integration"))
  .enablePlugins(NoPublishPlugin)
  .dependsOn(app.jvm % "test->test")
  .settings(
    name := "nomenclator-integration",
    libraryDependencies ++= integrationTestDependencies
  )

addCommandAlias(
  "check",
  "scalafmtSbtCheck; scalafmtCheckAll; test"
)
