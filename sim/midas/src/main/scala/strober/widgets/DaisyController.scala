// See LICENSE for license details.

package strober
package widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._
import strober.core.{DaisyBundle, DaisyData, ChainType}

class DaisyControllerIO(daisyIO: DaisyBundle)(implicit p: Parameters) extends WidgetIO()(p){
  val daisy = Flipped(daisyIO.cloneType)
}

class DaisyController(daisyIF: DaisyBundle)(implicit p: Parameters) extends Widget()(p) {
  val io = IO(new DaisyControllerIO(daisyIF))

  // Handle SRAM restarts
  io.daisy.sram.zipWithIndex foreach { case (sram, i) =>
    Pulsify(genWORegInit(sram.restart, s"SRAM_RESTART_$i", false.B), pulseLength = 1)
  }
  io.daisy.regfile.zipWithIndex foreach { case (regfile, i) =>
    Pulsify(genWORegInit(regfile.restart, s"REGFILE_RESTART_$i", false.B), pulseLength = 1)
  }

  def bindDaisyChain[T <: DaisyData](daisy: Vec[T], name: String) = {
    val inAddrs = (daisy.toSeq map (_.in)).zipWithIndex map {
      case (channel, idx) => attachDecoupledSink(channel, s"${name}_IN_$idx")
    }
    val outAddrs = (daisy.toSeq map (_.out)).zipWithIndex map {
      case (channel, idx) => attachDecoupledSource(channel, s"${name}_OUT_$idx")
    }
    (inAddrs, outAddrs)
  }
  val chains = ChainType.values.toList
  val names = (chains map { t => t -> t.toString.toUpperCase }).toMap
  val addrs = (chains map { t => t -> bindDaisyChain(io.daisy(t), names(t)) }).toMap

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    headerComment(sb)
    sb.append(genMacro("DAISY_WIDTH", UInt32(daisyIF.daisyWidth)))
    sb.append(genMacro("SRAM_RESTART_ADDR", UInt32(base)))
    sb.append(genMacro("REGFILE_RESTART_ADDR", UInt32(base + 1)))
    sb.append(genEnum("CHAIN_TYPE", (chains map (t => s"${names(t)}_CHAIN")) :+ "CHAIN_NUM"))
    sb.append(genArray("CHAIN_SIZE", chains map (t => UInt32(io.daisy(t).size))))
    sb.append(genArray("CHAIN_ADDR", chains map (t => UInt32(base + addrs(t)._2.head))))
  }

  genCRFile()
}
