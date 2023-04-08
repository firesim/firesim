import Tests._

organization := "edu.berkeley.cs"

version := "1.0-SNAPSHOT"

name := "midas"

scalaVersion := "2.13.10"
val chiselVersion = "3.5.5"

val chiselFirrtlMergeStrategy = CustomMergeStrategy("cfmergestrategy") { deps =>
  import sbtassembly.Assembly.{Project, Library}
  val keepDeps = deps.filter { dep =>
    val nm = dep match {
      case p: Project => p.name
      case l: Library => l.moduleCoord.name
    }
    Seq("firrtl", "chisel3").contains(nm.split("_")(0)) // split by _ to avoid checking on major/minor version
  }
  if (keepDeps.size <= 1) {
    Right(keepDeps.map(dep => JarEntry(dep.target, dep.stream)))
  } else {
    Left(s"Unable to resolve conflict (${keepDeps.size}>1 conflicts):\n${keepDeps.mkString("\n")}")
  }
}


lazy val commonSettings = Seq(
  organization := "berkeley",
  version      := "1.0",
  scalaVersion := "2.13.10",
  scalacOptions ++= Seq("-deprecation","-unchecked","-Ywarn-unused"),
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.2" % "test",
  libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.10",
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  libraryDependencies += "edu.berkeley.cs" %% "chisel3" % chiselVersion,
  libraryDependencies += "edu.berkeley.cs" %% "rocketchip" % "1.6.0-c49644ecd-SNAPSHOT",
  addCompilerPlugin("edu.berkeley.cs" % "chisel3-plugin" % chiselVersion cross CrossVersion.full),
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
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
  }
)

/* lazy val firesimStuff = (project in file("..")) */
/* .settings(commonSettings) */

lazy val midasTargetUtils   = (project in file("./targetutils"))
  .settings(commonSettings)

lazy val midas = (project in file("."))
/* .dependsOn(midasTargetUtils, firesimStuff) */
  .dependsOn(midasTargetUtils)
  .settings(libraryDependencies ++= Seq(
    "org.scalatestplus" %% "scalacheck-1-14" % "3.1.3.0" % "test"))
  .settings(commonSettings)
