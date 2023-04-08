
package goldengate.tests

import midas.stage._
import chisel3._
import chisel3.stage.{ChiselCircuitAnnotation, ChiselGeneratorAnnotation, ChiselStage, CircuitSerializationAnnotation}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.TestSuite
import firrtl._
import firrtl.annotations.{DeletedAnnotation, JsonProtocol}
import firrtl.options.TargetDirAnnotation
import firrtl.stage.FirrtlCircuitAnnotation
import midas.widgets.{BridgeAnnotation, PeekPokeBridge, RationalClock, RationalClockBridge, ResetPulseBridge, ResetPulseBridgeParameters}

import java.io.{File, PrintWriter}



trait GoldenGateCompilerTest { this: TestSuite =>
  protected def annos: AnnotationSeq = Seq()

  protected def compile[M <: RawModule](gen: => M, target: String, a: AnnotationSeq = List(), ll: String = "warn"): (String, AnnotationSeq) = {
    val stage = new ChiselStage

    val testName = this.suiteName
    val testRunDir = TargetDirAnnotation("test_run_dir" + File.separator + testName)

    val r = stage.execute(Array("-X", target, "-ll", ll), ChiselGeneratorAnnotation(() => gen) +: testRunDir +: a ++: annos)
    val src = r.collect {
      case EmittedFirrtlCircuitAnnotation(a) => a
      case EmittedFirrtlModuleAnnotation(a) => a
      case EmittedVerilogCircuitAnnotation(a) => a
      case EmittedVerilogModuleAnnotation(a) => a
    }.map(_.value).mkString("")
    (src, r)
  }
}

class GoldenGateGenerateSpec extends AnyFreeSpec with GoldenGateCompilerTest {
  class Adder extends Module {
    val io = IO(new Bundle{
      val a = Input(UInt(8.W))
      val b = Input(UInt(8.W))
      val c = Output(UInt(8.W))
    })
    io.c := io.b + io.a
  }

  class FireSim extends RawModule {
    freechips.rocketchip.util.property.cover.setPropLib(new midas.passes.FireSimPropertyLibrary())

    val buildtopClock = Wire(Clock())
    val buildtopReset = WireInit(false.B)

    val dummy = WireInit(false.B)
    val peekPokeBridge = PeekPokeBridge(buildtopClock, dummy)

    val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
    resetBridge.io.clock := buildtopClock
    buildtopReset := resetBridge.io.reset

    midas.targetutils.GlobalResetCondition(buildtopReset)

    def dutReset = { require(false, "dutReset should not be used in FireSim"); false.B }
    def success  = { require(false, "success should not be used in FireSim"); false.B }

    val allClocks = Seq(RationalClock("baseClock", 1, 1))
    val clockBridge = Module(new RationalClockBridge(allClocks))

    buildtopClock := clockBridge.io.clocks(0)

  }

  "GoldenGateGenerateSpec" in {
    val (firrtl, annos) = compile(new FireSim, "low")

    val firrtlWriter = new PrintWriter(new File("firesim.fir"))
    firrtlWriter.write(firrtl)
    firrtlWriter.close()
    println("firrtlWriter Done")

    val annoWriter = new PrintWriter(new File("firesim.anno.json"))
    annoWriter.write(JsonProtocol.serialize(annos.filter(_ match {
// case _: DeletedAnnotation => false
// case _: EmittedComponent => false
// case _: EmittedAnnotation[_] => false
// case _: FirrtlCircuitAnnotation => false
// case _: ChiselCircuitAnnotation => false
// case _: CircuitSerializationAnnotation => false
      case _: BridgeAnnotation => true
      case _ => false
    })))
    annoWriter.close()
    println("annoWriter Done")


    GoldenGateMain.main(
      Array(
        "-i", // FIRRTL_FILE
        "firesim.fir",
        "-ggcp",
        "firesim.midasexamples",
        "-faf",
        "firesim.anno.json",
        "-ggcs",
        "F1Config",
        "-ofb",
        "FireSim-generated",
        "--no-dedup"
      )
    )
  }
}
