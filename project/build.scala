import sbt._
import Keys._

object DebugBuild extends Build {
  lazy val chisel   = Project("chisel",   base=file("chisel"))
  lazy val tutorial = Project("tutorial", base=file("tutorial/examples")).dependsOn(chisel)
  lazy val mini     = Project("riscv-mini", base=file("riscv-mini")).dependsOn(chisel)
  lazy val root     = Project("debug-machine", base=file(".")).dependsOn(tutorial, mini)
}
