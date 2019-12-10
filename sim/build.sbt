import Tests._

lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.12.10",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.8" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.1",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
  exportJars := true,
  resolvers ++= Seq(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    Resolver.mavenLocal)
)

// Fork each scala test for now, to work around persistent mutable state
// in Rocket-Chip based generators
def isolateAllTests(tests: Seq[TestDefinition]) = tests map { test =>
      val options = ForkOptions()
      new Group(test.name, Seq(test), SubProcess(options))
  } toSeq

testGrouping in Test := isolateAllTests( (definedTests in Test).value )

lazy val firesimAsLibrary = sys.env.get("FIRESIM_STANDALONE") == None

lazy val chipyardDir = if(firesimAsLibrary) {
  file("../../../")
} else {
  file("../target-design/chipyard")
}

lazy val chisel        = ProjectRef(chipyardDir, "chisel")
lazy val rocketchip    = ProjectRef(chipyardDir, "rocketchip")
lazy val barstools     = ProjectRef(chipyardDir, "barstoolsMacros")
lazy val icenet        = ProjectRef(chipyardDir, "icenet")
lazy val testchipip    = ProjectRef(chipyardDir, "testchipip")
lazy val sifive_blocks = ProjectRef(chipyardDir, "sifive_blocks")
lazy val firechip      = ProjectRef(chipyardDir, "firechip")

lazy val targetutils   = (project in file("midas/targetutils"))
  .settings(commonSettings)
  .dependsOn(chisel)

// We cannot forward reference firesim from midas (this creates a circular
// dependency on the project definitions), so declare a reference to it
// first and use that to append to our RuntimeClasspath
lazy val firesimRef = ProjectRef(file("."), "firesim")

lazy val midas = (project in file("midas"))
  .dependsOn(barstools, rocketchip)
  .settings(commonSettings,
    Runtime / fullClasspathAsJars ++= ((firesimRef / Runtime / fullClasspath).value ++
                                       (firechip / Runtime / fullClasspath).value)
  )

lazy val firesimLib = (project in file("firesim-lib"))
  .settings(commonSettings).dependsOn(midas, icenet, testchipip, sifive_blocks)

// Contains example targets, like the MIDAS examples, and FASED tests
lazy val firesim    = (project in file("."))
  .settings(commonSettings).dependsOn(chisel, rocketchip, midas, firesimLib % "test->test;compile->compile")
