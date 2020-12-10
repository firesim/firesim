import Tests._

// This is set by CI and should otherwise be unmodified
val apiDirectory = settingKey[String]("The site directory into which the published scaladoc should placed.")
apiDirectory := "latest"

lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.12.10",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.1.0" % "test",
  // Remove after bump to FIRRTL 1.3
  libraryDependencies += "org.scalatestplus" %% "scalacheck-1-14" % "3.1.0.1" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.1",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
  // ScalaDoc
  autoAPIMappings  := true,
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

lazy val firesimAsLibrary = sys.env.get("FIRESIM_STANDALONE") == None

lazy val chipyardDir = if(firesimAsLibrary) {
  file("../../../")
} else {
  file("../target-design/chipyard")
}

lazy val chipyardRoot  = ProjectRef(chipyardDir, "chipyardRoot")
lazy val chipyard      = ProjectRef(chipyardDir, "chipyard")
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
    Test / unmanagedBase := (chipyardRoot / baseDirectory).value / "test_lib",
  )

lazy val firesimLib = (project in file("firesim-lib"))
  .settings(commonSettings).dependsOn(midas, icenet, testchipip, sifive_blocks)

// Contains example targets, like the MIDAS examples, and FASED tests
lazy val firesim    = (project in file("."))
  .enablePlugins(ScalaUnidocPlugin, GhpagesPlugin, SiteScaladocPlugin)
  .settings(commonSettings,
    git.remoteRepo := "git@github.com:firesim/firesim.git",
    // Publish scala doc only for the library projects -- classes under this
    // project are all integration test-related
    unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(targetutils, midas, firesimLib),
    siteSubdirName in ScalaUnidoc := apiDirectory.value + "/api",
    // Only delete the files in the docs branch that are in the directory were
    // trying to publish to.  This prevents dev-versions from blowing away
    // tagged versions and vice versa
    includeFilter in ghpagesCleanSite := new sbt.io.PrefixFilter(apiDirectory.value),
    excludeFilter in ghpagesCleanSite := NothingFilter,

    // Clobber the existing doc task to instead have it use the unified one
    Compile / doc := (doc in ScalaUnidoc).value,
    // Registers the unidoc-generated html with sbt-site
    addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), siteSubdirName in ScalaUnidoc),
    concurrentRestrictions += Tags.limit(Tags.Test, 1)
  )
  .dependsOn(chisel, rocketchip, midas, firesimLib % "test->test;compile->compile", chipyard)
