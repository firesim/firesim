import Tests._

val chiselVersion = "3.5.5"

// This is set by CI and should otherwise be unmodified
val apiDirectory = settingKey[String]("The site directory into which the published scaladoc should placed.")
apiDirectory := "latest"

lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.13.10",
  scalacOptions ++= Seq("-deprecation","-unchecked","-Ywarn-unused"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.2" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.10",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  libraryDependencies += "edu.berkeley.cs" %% "chisel3" % chiselVersion,
  addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
  // Scalafix
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,
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

lazy val chipyard      = ProjectRef(chipyardDir, "chipyard")
lazy val rocketchip    = ProjectRef(chipyardDir, "rocketchip")
lazy val icenet        = ProjectRef(chipyardDir, "icenet")
lazy val testchipip    = ProjectRef(chipyardDir, "testchipip")
lazy val sifive_blocks = ProjectRef(chipyardDir, "sifive_blocks")
lazy val firechip      = ProjectRef(chipyardDir, "firechip")

lazy val targetutils   = (project in file("midas/targetutils"))
  .settings(commonSettings)

// We cannot forward reference firesim from midas (this creates a circular
// dependency on the project definitions), so declare a reference to it
// first and use that to append to our RuntimeClasspath
lazy val firesimRef = ProjectRef(file("."), "firesim")

lazy val midas = (project in file("midas"))
  .dependsOn(rocketchip)
  .settings(libraryDependencies ++= Seq(
    "org.scalatestplus" %% "scalacheck-1-14" % "3.1.3.0" % "test"))
  .settings(commonSettings)


lazy val firesimLib = (project in file("firesim-lib"))
  .dependsOn(midas, icenet, testchipip, sifive_blocks)
  .settings(commonSettings)

// Contains example targets, like the MIDAS examples, and FASED tests
lazy val firesim    = (project in file("."))
  .enablePlugins(ScalaUnidocPlugin, GhpagesPlugin, SiteScaladocPlugin)
  .settings(commonSettings,
    git.remoteRepo := "git@github.com:firesim/firesim.git",
    // Publish scala doc only for the library projects -- classes under this
    // project are all integration test-related
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(targetutils, midas, firesimLib),
    ScalaUnidoc / siteSubdirName := apiDirectory.value + "/api",
    // Only delete the files in the docs branch that are in the directory were
    // trying to publish to.  This prevents main-versions from blowing away
    // tagged versions and vice versa
    ghpagesCleanSite / includeFilter := new sbt.io.PrefixFilter(apiDirectory.value),
    ghpagesCleanSite / excludeFilter := NothingFilter,

    // Clobber the existing doc task to instead have it use the unified one
    Compile / doc := (ScalaUnidoc / doc).value,
    // Registers the unidoc-generated html with sbt-site
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName),
    concurrentRestrictions += Tags.limit(Tags.Test, 1)
  )
  .dependsOn(rocketchip, midas, firesimLib % "test->test;compile->compile", chipyard)
