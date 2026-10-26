// See LICENSE for license details.

package midas.tests

import java.nio.file.{Files, Paths}
import firrtl.annotations._
import firrtl.stage.{FirrtlCircuitAnnotation, FirrtlStage, RunFirrtlTransformAnnotation}
import firrtl.options.TargetDirAnnotation
import midas._
import midas.passes.{AutoILATransform, HostClockSource}
import midas.stage.OutputBaseFilenameAnnotation
import midas.stage.phases.ConfigParametersAnnotation
import org.chipsalliance.cde.config.Config
import org.scalatest.flatspec.AnyFlatSpec

class AutoILAAXISSpec extends AnyFlatSpec {
  behavior.of("AutoILA AXIS link validation")
  private val top     = ModuleTarget("Top", "Top")
  private val links   = Seq(
    ("S_AXIS_tdata", true, 32),
    ("S_AXIS_tvalid", true, 1),
    ("S_AXIS_tlast", true, 1),
    ("S_AXIS_tready", false, 1),
    ("M_AXIS_tdata", false, 32),
    ("M_AXIS_tvalid", false, 1),
    ("M_AXIS_tlast", false, 1),
    ("M_AXIS_tready", true, 1),
    ("aresetn", true, 1),
  )
    .map { case (name, input, width) => AXIDebugLinkAnnotation(top.ref(name), name, input, width) }
  private val circuit = firrtl.Parser.parse("""circuit Top :
                                              |  module Top :
                                              |    input clock : Clock
                                              |    input probe : UInt<8>
                                              |    node S_AXIS_tdata = UInt<32>(1)
                                              |    node S_AXIS_tvalid = UInt<1>(1)
                                              |    node S_AXIS_tlast = UInt<1>(0)
                                              |    node M_AXIS_tready = UInt<1>(1)
                                              |    node aresetn = UInt<1>(1)
                                              |    wire S_AXIS_tready : UInt<1>
                                              |    S_AXIS_tready <= UInt<1>(0)
                                              |    wire M_AXIS_tdata : UInt<32>
                                              |    M_AXIS_tdata <= UInt<32>(0)
                                              |    node M_AXIS_tvalid = UInt<1>(0)
                                              |    wire M_AXIS_tlast : UInt<1>
                                              |    M_AXIS_tlast is invalid
                                              |    inst other of Other
                                              |  module Other :
                                              |    output S_AXIS_tdata : UInt<32>
                                              |    S_AXIS_tdata <= UInt<32>(0)
                                              |""".stripMargin)

  private def compile(
    name:     String,
    selected: Seq[AXIDebugLinkAnnotation],
    probe:    Boolean = true,
  ): java.nio.file.Path = {
    val parent      = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    val out         = Files.createTempDirectory(parent, name + "-").toAbsolutePath
    val config      = new Config((_, _, _) => {
      case EnableAutoILA  => true
      case AutoILAUseAXIS => true
    })
    val annotations = Seq(
      FirrtlCircuitAnnotation(circuit),
      TargetDirAnnotation(out.toString),
      ConfigParametersAnnotation(config.toInstance),
      HostClockSource(top.ref("clock")),
      OutputBaseFilenameAnnotation("AXIS"),
      RunFirrtlTransformAnnotation(AutoILATransform),
    ) ++ selected ++
      (if (probe) Seq(InternalFirrtlFpgaDebugAnnotation(ComponentName("probe", top.toNamed))) else Seq.empty)
    println(s"AXIS annotation regression output: $out")
    try new FirrtlStage().execute(Array("-E", "verilog"), annotations)
    catch {
      case scala.util.control.NonFatal(error) =>
        val writer = new java.io.PrintWriter(out.resolve("failure.txt").toFile)
        try error.printStackTrace(writer)
        finally writer.close()
        throw error
    }
    out
  }

  it should "connect each direction and tie off simulation outputs" in {
    val out     = compile("valid", links)
    val wrapper = Files.readString(out.resolve("AXIS.ila_wrapper_inst.v"))
    for (link <- links) {
      val direction = if (link.input) "input" else "output"
      assert(wrapper.contains(s"$direction [${link.width - 1}:0] ${link.port}"))
      if (!link.input) assert(wrapper.contains(s"assign ${link.port} = ${link.width}'b0;"))
    }
    assert(wrapper.contains(".aclk(clock)"))
  }
  for (
    (name, invalid) <- Seq(
                         "missing"        -> links.tail,
                         "duplicate"      -> (links :+ links.head.copy(target = top.ref("M_AXIS_tdata"))),
                         "direction"      -> (links.head.copy(input = false) +: links.tail),
                         "width"          -> (links.head.copy(width = 31) +: links.tail),
                         "unknown-port"   -> (links.head.copy(port = "other") +: links.tail),
                         "foreign-module" -> (links.head
                           .copy(target = ModuleTarget("Top", "Other").ref(links.head.target.ref)) +: links.tail),
                       )
  ) {
    it should s"reject a malformed $name transport" in {
      val error    = intercept[Exception] { compile(name, invalid) }
      val messages = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null).map(_.toString).mkString("\n")
      val expected =
        if (name == "foreign-module") "AXI debug transport must resolve to lowered top-level wires"
        else "correctly typed platform AXI Debug Hub transport"
      assert(messages.contains(expected), messages)
    }
  }
  it should "reject a transport whose ILA has no probes" in {
    val error    = intercept[Exception] { compile("no-probe", links, probe = false) }
    val messages = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null).map(_.toString).mkString("\n")
    assert(messages.contains("at least one AutoILA probe"), messages)
  }
}
