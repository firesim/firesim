import sbt._
import Keys._

object FAEEBuild extends Build {
  lazy val chisel   = Project("chisel",   base=file("chisel"))
  lazy val tutorial = Project("tutorial", base=file("tutorial/examples")).dependsOn(chisel)
  lazy val root     = Project("faee",     base=file(".")).dependsOn(tutorial)
}
