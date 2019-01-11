package firesim.picorv32

import java.io.{File, FileWriter}
import scala.io.Source

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Circuit, Port, Width}
import chisel3.core._
import chisel3.experimental.MultiIOModule

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.system.{TestGeneration, RegressionTestSuite}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.XLen
import freechips.rocketchip.config.Config

import boom.system.{BoomTilesKey, BoomTestSuites}

import sifive.blocks.devices.uart.UARTPortIO

import firrtl.annotations.Annotation

trait FireSimGeneratorUtils {
  def elaborateAndCompileWithMidas(targetDir: String) {
    val targetTransforms = Seq(
      // firesim.passes.AsyncResetRegPass,
      // firesim.passes.PlusArgReaderPass
    )

    lazy val hostTransforms = Seq(
      new firesim.passes.ILATopWiringTransform(testDir)
    )

    lazy val testDir = new File(targetDir)

    def midasParams = (new Config(new firesim.firesim.PicoRV32Config)).toInstance

    val lines = Source.fromFile("src/main/scala/picorv32/synth.fir").getLines()
    val chirrtl = firrtl.Parser.parse(lines)

    val dut = chisel3.Driver.elaborate(() => new UARTWrapper)
    val annos = dut.annotations.map(_.toFirrtl)
    val portList = dut.components.find(_.name == "UARTWrapper").get.ports.flatMap(p => Some(p.id))

    println(dut.components.find(_.name == "UARTWrapper").get.ports.find(_.id.instanceName == "uart").get)
   
    midas.MidasCompiler(
      chirrtl, annos, portList, testDir, None, targetTransforms, hostTransforms
      )(midasParams)
    // Need replay
  }
}

object FireSimGenerator extends App with FireSimGeneratorUtils {
  require (args.size == 1, "Command line arg must be output directory!")
  elaborateAndCompileWithMidas(args(0))
}

class UARTWrapper extends MultiIOModule {
        val uart = IO(Vec(1, new UARTPortIO()))

        val txFromVerilog = Wire(Bool())
        val rxFromVerilog = Wire(Bool())

        txFromVerilog := DontCare
        uart(0).txd := txFromVerilog
        rxFromVerilog := uart(0).rxd
}

object UARTWrapperDriver extends App {
        chisel3.Driver.execute(args, () => new UARTWrapper)
}
