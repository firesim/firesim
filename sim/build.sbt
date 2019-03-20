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

lazy val firesimAsLibrary = sys.env.get("FIRESIM_IS_TOP") == None

lazy val fireChipDir = if(firesimAsLibrary) {
  file("../../")
} else {
  file("target-rtl/firechip")
}
//
//// Target-specific dependencies
lazy val rocketchip    = ProjectRef(fireChipDir, "rocketchip")
lazy val testchipip    = ProjectRef(fireChipDir, "testchipip")
lazy val boom          = ProjectRef(fireChipDir, "boom")
lazy val sifive_blocks = ProjectRef(fireChipDir, "sifive_blocks")
lazy val firechip      = ProjectRef(fireChipDir, "firechip")
// MIDAS-specific dependencies
lazy val mdf        = project in file("barstools/mdf/scalalib")
lazy val barstools  = (project in file("barstools/macros"))
  .settings(commonSettings)
  .dependsOn(mdf, rocketchip)

lazy val midas      = (project in file("midas"))
  .settings(commonSettings).dependsOn(barstools, rocketchip)

lazy val common     = (project in file("common"))
  .settings(commonSettings).dependsOn(midas)

lazy val rebar      = RootProject(fireChipDir)

lazy val firesim    = (project in file("."))
  .settings(commonSettings)
  .dependsOn(rebar, common % "test->test;compile->compile")
