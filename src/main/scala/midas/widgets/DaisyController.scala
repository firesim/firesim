package midas
package widgets

import chisel3._
import chisel3.util._
import cde.Parameters
import midas.core.{DaisyBundle, DaisyData, ChainType}

class DaisyControllerIO(daisyIO: DaisyBundle)(implicit p: Parameters) extends WidgetIO()(p){
  val daisy = Flipped(daisyIO.cloneType)
}

class DaisyController(daisyIF: DaisyBundle)(implicit p: Parameters) extends Widget()(p) {
  val io = IO(new DaisyControllerIO(daisyIF))

  def daisyAddresses(): Map[ChainType.Value, Int] = ChainType.values.toList map {t => (t -> getCRAddr(s"${t.toString}_0"))} toMap

  override def genHeader(base: BigInt, sb: StringBuilder) {
    sb append "#define SRAM_RESTART_ADDR %d\n".format(base)
    sb append "enum CHAIN_TYPE {%s,CHAIN_NUM};\n".format(
      ChainType.values.toList map (t => s"${t.toString.toUpperCase}_CHAIN") mkString ",")
    sb append "static const unsigned CHAIN_SIZE[CHAIN_NUM] = {%s};\n".format(
      ChainType.values.toList map (t => io.daisy(t).size) mkString ",")
    sb append "static const unsigned CHAIN_ADDR[CHAIN_NUM] = {%s};\n".format(
      ChainType.values.toList map (t => base + getCRAddr(s"${t.toString.toUpperCase}_0")) mkString ",")
  }

  def bindDaisyChain[T <: DaisyData](daisy: Vec[T], name: String): Unit = {
    val inputs = daisy.toSeq map (_.in)
    inputs.zipWithIndex foreach { case (channel, idx) =>
      attachDecoupledSink(channel, s"${name}_IN_$idx")
    }
    val outputs = daisy.toSeq map (_.out)
    outputs.zipWithIndex foreach { case (channel, idx) =>
      attachDecoupledSource(channel, s"${name}_$idx")
    }
  }

  // Handle SRAM restarts
  io.daisy.sram.zipWithIndex foreach { case (sram, i) =>
    Pulsify(genWORegInit(sram.restart, s"SRAM_RESTART_$i", Bool(false)), pulseLength = 1)
  }
  ChainType.values foreach { cType => bindDaisyChain(io.daisy(cType), cType.toString.toUpperCase) }

  genCRFile()
}
