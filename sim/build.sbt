lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.12.4",
  traceLevel   := 15,
  scalacOptions ++= Seq("-deprecation","-unchecked","-Xsource:2.11"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.5.3",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
  resolvers ++= Seq(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.sonatypeRepo("releases"),
    Resolver.mavenLocal)
)

lazy val rocketchip = RootProject(file("target-rtl/firechip/rocket-chip"))
lazy val boom       = project in file("target-rtl/firechip/boom") settings commonSettings dependsOn rocketchip
lazy val sifiveip   = project in file("target-rtl/firechip/sifive-blocks") settings commonSettings dependsOn rocketchip
lazy val testchipip = project in file("target-rtl/firechip/testchipip") settings commonSettings dependsOn rocketchip
lazy val icenet     = project in file("target-rtl/firechip/icenet") settings commonSettings dependsOn (rocketchip, testchipip)

lazy val mdf        = RootProject(file("barstools/mdf/scalalib"))
lazy val barstools  = project in file("barstools/macros") settings commonSettings dependsOn (mdf, rocketchip)
lazy val midas      = project in file("midas") settings commonSettings dependsOn barstools

lazy val firesim    = project in file(".") settings commonSettings dependsOn (midas, sifiveip, testchipip, icenet, boom)
