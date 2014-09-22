organization := "edu.berkeley.cs"

version := "2.0-SNAPSHOT"

name := "examples"

scalaVersion := "2.10.2"

//addSbtPlugin("com.github.scct" % "sbt-scct" % "0.2")

resolvers ++= Seq(
  "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "Sonatype Releases" at "http//oss.snoatype.org/content/repositories/releases",
  "scct-github-repository" at "http://mtkopone.github.com/scct/maven-repo"
)
