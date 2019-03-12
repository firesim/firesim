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

//firesim as a library vs standalone firesim
def findFirstDirectory(dirs: Seq[String]): java.io.File = {
  for (d <- dirs) {
    val f = new java.io.File(d)
    if (f.exists() && f.isDirectory()) {
      return f
    }
  }

  throw new Exception("Oh no!")
}

def findFirstBuildSBTDirectory(dirs: Seq[String]): java.io.File = {
  for (d <- dirs) {
    val f = new java.io.File(d)
    val fs = new java.io.File(d,"build.sbt")
    if (fs.exists() && f.isDirectory()) {
      print(f)
      return f
    }
  }

  throw new Exception("Oh no!")
}

val rocketChipDir = file("target-rtl/firechip/rocket-chip")
val fireChipDir  = file("target-rtl/firechip")
//lazy val fireChipDir_f = findFirstBuildSBTDirectory(Seq("../", "target-rtl/firechip"))
//lazy val fireChipDir = RootProject(findFirstBuildSBTDirectory(Seq("../", "target-rtl/firechip")))
//lazy val rocketChipDir = file(fireChipDir_f.toString ++ "/rocket-chip")

// Subproject definitions begin
// NB: FIRRTL dependency is unmanaged (and dropped in sim/lib)
//lazy val chisel  = (project in rocketChipDir / "chisel3")

// Contains annotations & firrtl passes you may wish to use in rocket-chip without
// introducing a circular dependency between RC and MIDAS
/*
lazy val midasTargetUtils = (project in file("midas/targetutils"))
  .settings(commonSettings)
  .dependsOn(chisel)
*/
// Rocket-chip dependencies (subsumes making RC a RootProject)
/*
lazy val hardfloat  = (project in rocketChipDir / "hardfloat")
  .settings(
    commonSettings,
    crossScalaVersions := Seq("2.11.12", "2.12.4"))
  .dependsOn(chisel, midasTargetUtils)
lazy val macros     = (project in rocketChipDir / "macros")
  .settings(commonSettings)
*/
// HACK: I'm strugging to override settings in rocket-chip's build.sbt (i want
// the subproject to register a new library dependendency on midas's targetutils library)
// So instead, avoid the existing build.sbt altogether and specify the project's root at src/
/*
lazy val rocketchip = (project in rocketChipDir / "src")
  .settings(
    commonSettings,
    scalaSource in Compile := baseDirectory.value / "main" / "scala",
    resourceDirectory in Compile := baseDirectory.value / "main" / "resources")
  .dependsOn(chisel, hardfloat, macros, midasTargetUtils)
*/

// Target-specific dependencies
//lazy val firechip = (project in fireChipDir)
lazy val firechip = ProjectRef(fireChipDir, "firechip")
/*
lazy val firechip_plus = (project in fireChipDir / ".firechip-dummy")
  .dependsOn(midasTargetUtils, firechip)
*/
//lazy val targetdesignproject = (project in file(fireChipDir_f.toString))

// MIDAS-specific dependencies
lazy val mdf        = RootProject(file("barstools/mdf/scalalib"))
lazy val barstools  = (project in file("barstools/macros"))
  .settings(commonSettings)
  .dependsOn(mdf, firechip)

lazy val midas      = (project in file("midas"))
  .settings(commonSettings)
  .dependsOn(barstools, firechip)

// Finally the root project
lazy val firesim    = (project in file("."))
  .settings(commonSettings)
  .dependsOn(midas, firechip)
