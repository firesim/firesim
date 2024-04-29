import Tests._

val chiselVersion = "3.6.0"

// keep chisel/firrtl specific class files, rename other conflicts
val chiselFirrtlMergeStrategy = CustomMergeStrategy.rename { dep =>
  import sbtassembly.Assembly.{Project, Library}
  val nm = dep match {
    case p: Project => p.name
    case l: Library => l.moduleCoord.name
  }
  if (Seq("firrtl", "chisel3").contains(nm.split("_")(0))) { // split by _ to avoid checking on major/minor version
    dep.target
  } else {
    "renamed/" + dep.target
  }
}

// This is set by CI and should otherwise be unmodified
val apiDirectory = settingKey[String]("The site directory into which the published scaladoc should placed.")
apiDirectory := "latest"

lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.13.10",
  scalacOptions ++= Seq("-deprecation","-unchecked","-Ywarn-unused","-Ymacro-annotations"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.2" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.10",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  libraryDependencies += "edu.berkeley.cs" %% "chisel3" % chiselVersion,
  libraryDependencies += "com.lihaoyi" %% "sourcecode" % "0.3.1",

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
    Resolver.mavenLocal),
  assembly / test := {},
  assembly / assemblyMergeStrategy := {
    case PathList("chisel3", "stage", xs @ _*) => chiselFirrtlMergeStrategy
    case PathList("firrtl", "stage", xs @ _*) => chiselFirrtlMergeStrategy
    // should be safe in JDK11: https://stackoverflow.com/questions/54834125/sbt-assembly-deduplicate-module-info-class
    case x if x.endsWith("module-info.class") => MergeStrategy.discard
    case x =>
      val oldStrategy = (assembly / assemblyMergeStrategy).value
      oldStrategy(x)
  }
)

lazy val chiselSettings = Seq(
  libraryDependencies ++= Seq(
    "edu.berkeley.cs" %% "chisel3" % "3.6.0",
    "org.apache.commons" % "commons-lang3" % "3.12.0",
    "org.apache.commons" % "commons-text" % "1.9"
  ),
  addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % "3.6.0" cross CrossVersion.full)
)

lazy val firesimAsLibrary = sys.env.get("FIRESIM_STANDALONE") == None

lazy val chipyardDir = if(firesimAsLibrary) {
  file("./chipyard-symlink")
} else {
  file("./target-rtl/chipyard")
}


// Fork each scala test for now, to work around persistent mutable state
// in Rocket-Chip based generators
def isolateAllTests(tests: Seq[TestDefinition]) = tests map { test =>
      val options = ForkOptions()
      new Group(test.name, Seq(test), SubProcess(options))
  } toSeq


lazy val cde = (project in chipyardDir / "tools" / "cde")
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(Compile / scalaSource := baseDirectory.value / "cde/src/chipsalliance/rocketchip")

lazy val hardfloat = (project in chipyardDir / "generators" / "hardfloat" / "hardfloat")
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val rocketMacros  = (project in chipyardDir / "generators" / "rocket-chip" / "macros")
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val diplomacy = (project in chipyardDir / "generators" / "diplomacy" / "diplomacy")
  .dependsOn(cde)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(Compile / scalaSource := baseDirectory.value / "src" / "diplomacy")


lazy val rocketchip = (project in chipyardDir / "generators" / "rocket-chip")
  .dependsOn(hardfloat, rocketMacros, diplomacy, cde)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "mainargs" % "0.5.0",
      "org.scala-lang" % "scala-reflect" % scalaVersion.value,
      "org.json4s" %% "json4s-jackson" % "4.0.5",
      "org.scalatest" %% "scalatest" % "3.2.0" % "test",
      "org.scala-graph" %% "graph-core" % "1.13.5",
      "com.lihaoyi" %% "sourcecode" % "0.3.1"
    )
  )

lazy val icenet = (project in chipyardDir / "generators" / "icenet")
  .dependsOn(rocketchip)
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val testchipip = (project in chipyardDir / "generators" / "testchipip" / "src")
  .dependsOn(rocketchip, rocketchip_blocks)
  .settings(commonSettings)
  .settings(chiselSettings)
  .settings(Compile / scalaSource := baseDirectory.value / "main" / "scala")
  .settings(Compile / resourceDirectory := baseDirectory.value / "main" / "resources")


lazy val rocketchip_blocks = (project in chipyardDir / "generators" / "rocket-chip-blocks")
  .dependsOn(rocketchip)
  .settings(commonSettings)
  .settings(chiselSettings)

lazy val targetutils   = (project in file("midas/targetutils"))
  .settings(commonSettings)

// We cannot forward reference firesim from midas (this creates a circular
// dependency on the project definitions), so declare a reference to it
// first and use that to append to our RuntimeClasspath
lazy val firesimRef = ProjectRef(file("."), "firesim")

lazy val midas = (project in file("midas"))
  .dependsOn(rocketchip, targetutils)
  .settings(libraryDependencies ++= Seq(
    "org.scalatestplus" %% "scalacheck-1-14" % "3.1.3.0" % "test"))
  .settings(commonSettings)


lazy val firesimLib = (project in file("firesim-lib"))
  .dependsOn(midas, icenet, testchipip, rocketchip_blocks)
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
  .dependsOn(rocketchip, midas, firesimLib % "test->test;compile->compile")
