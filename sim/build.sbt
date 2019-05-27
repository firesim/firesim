import Tests._

lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.12.4",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.1",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
)

// Fork each scala test for now, to work around persistent mutable state
// in Rocket-Chip based generators
def isolateAllTests(tests: Seq[TestDefinition]) = tests map { test =>
      val options = ForkOptions()
      new Group(test.name, Seq(test), SubProcess(options))
  } toSeq

testGrouping in Test := isolateAllTests( (definedTests in Test).value )

lazy val firesimAsLibrary = sys.env.get("FIRESIM_STANDALONE") == None

lazy val rebarDir = if(firesimAsLibrary) {
  file("../../../")
} else {
  file("target-rtl/firechip")
}

lazy val chisel        = ProjectRef(rebarDir, "chisel")
lazy val rocketchip    = ProjectRef(rebarDir, "rebarRocketchip")
lazy val barstools     = ProjectRef(rebarDir, "barstoolsMacros")
lazy val icenet        = ProjectRef(rebarDir, "icenet")
lazy val testchipip    = ProjectRef(rebarDir, "testchipip")
lazy val sifive_blocks = ProjectRef(rebarDir, "sifive_blocks")
lazy val firechip      = ProjectRef(rebarDir, "firechip")

lazy val targetutils   = (project in file("midas/targetutils"))
  .settings(commonSettings)
  .dependsOn(chisel)

lazy val midas      = (project in file("midas"))
  .settings(commonSettings).dependsOn(barstools, rocketchip)

lazy val firesimLib = (project in file("firesim-lib"))
  .settings(commonSettings).dependsOn(midas, icenet, testchipip, sifive_blocks)

// Contains example targets, like the MIDAS examples, and FASED tests
lazy val firesim    = (project in file("."))
  .settings(commonSettings).dependsOn(chisel, rocketchip, midas, firesimLib % "test->test;compile->compile")
  .aggregate(firechip)
