import Tests._

lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.12.4",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  libraryDependencies += "org.json4s" %% "json4s-jackson" % "3.5.3",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
)

// Fork each scala test for now, to work around persistent mutable state
// in Rocket-Chip based generators when testing the same DESIGN
def isolateAllTests(tests: Seq[TestDefinition]) = tests map { test =>
      val options = ForkOptions()
      new Group(test.name, Seq(test), SubProcess(options))
  } toSeq

testGrouping in Test := isolateAllTests( (definedTests in Test).value )

val rocketChipDir = file("target-rtl/firechip/rocket-chip")
val fireChipDir  = file("target-rtl/firechip")

// Subproject definitions begin
// NB: FIRRTL dependency is unmanaged (and dropped in sim/lib)
lazy val chisel  = (project in rocketChipDir / "chisel3")

// Contains annotations & firrtl passes you may wish to use in rocket-chip without
// introducing a circular dependency between RC and MIDAS
lazy val midasTargetUtils = (project in file("midas/targetutils"))
  .settings(commonSettings)
  .dependsOn(chisel)

// Rocket-chip dependencies (subsumes making RC a RootProject)
lazy val hardfloat  = (project in rocketChipDir / "hardfloat")
  .settings(
    commonSettings,
    crossScalaVersions := Seq("2.11.12", "2.12.4"))
  .dependsOn(chisel, midasTargetUtils)
lazy val macros     = (project in rocketChipDir / "macros")
  .settings(commonSettings)

// HACK: I'm strugging to override settings in rocket-chip's build.sbt (i want
// the subproject to register a new library dependendency on midas's targetutils library)
// So instead, avoid the existing build.sbt altogether and specify the project's root at src/
lazy val rocketchip = (project in rocketChipDir / "src")
  .settings(
    commonSettings,
    scalaSource in Compile := baseDirectory.value / "main" / "scala",
    resourceDirectory in Compile := baseDirectory.value / "main" / "resources")
  .dependsOn(chisel, hardfloat, macros, midasTargetUtils)

// Target-specific dependencies
lazy val boom       = (project in fireChipDir / "boom")
  .settings(commonSettings)
  .dependsOn(rocketchip)
lazy val sifiveip   = (project in fireChipDir / "sifive-blocks")
  .settings(commonSettings)
  .dependsOn(rocketchip)
lazy val sifivecache = (project in fireChipDir / "block-inclusivecache-sifive")
  .settings(commonSettings)
  .dependsOn(rocketchip)
lazy val testchipip = (project in fireChipDir / "testchipip")
  .settings(commonSettings)
  .dependsOn(rocketchip)
lazy val icenet     = (project in fireChipDir / "icenet")
  .settings(commonSettings)
  .dependsOn(rocketchip, testchipip)

lazy val memblade   = (project in fireChipDir / "memory-blade")
  .settings(commonSettings)
  .dependsOn(rocketchip, testchipip, icenet)

// MIDAS-specific dependencies
lazy val mdf        = RootProject(file("barstools/mdf/scalalib"))
lazy val barstools  = (project in file("barstools/macros"))
  .settings(commonSettings)
  .dependsOn(mdf, rocketchip)
lazy val midas      = (project in file("midas"))
  .settings(commonSettings)
  .dependsOn(barstools)

// Finally the root project
lazy val firesim    = (project in file("."))
  .settings(commonSettings)
  .dependsOn(rocketchip, midas, boom, icenet, sifiveip, sifivecache, memblade)
