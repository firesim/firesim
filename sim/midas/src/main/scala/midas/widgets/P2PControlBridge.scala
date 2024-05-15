package midas.widgets


import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy.InModuleBody
import midas.MetasimPrintfEnable

object P2PLogger {
  def logInfo(format: String, args: Bits*)(implicit p: Parameters) {
    val loginfo_cycles = RegInit(0.U(64.W))
    loginfo_cycles := loginfo_cycles + 1.U

    printf("cy: %d, ", loginfo_cycles)
    printf(Printable.pack(format, args:_*))
  }
}


trait ConnectBridgeToFPGATop { self: Widget =>
  private val _info = InModuleBody {
    val fpga_top = IO(new Bundle {
      val pcis_awaddr = Flipped(Decoupled(UInt(64.W)))
      val pcis_araddr = Flipped(Decoupled(UInt(64.W)))
      val pcim_awaddr = Flipped(Decoupled(UInt(64.W)))
      val pcim_araddr = Flipped(Decoupled(UInt(64.W)))
    })
    fpga_top
  }

  def fpga = _info.getWrappedValue
}

class P2PControlBridgeIO(implicit p: Parameters) extends WidgetIO()(p)


class P2PControlBridge(implicit p: Parameters)
  extends Widget()(p)
  with ConnectBridgeToFPGATop 
{
  lazy val module = new WidgetImp(this) {
    val io = IO(new P2PControlBridgeIO)

    fpga.pcis_awaddr.ready := true.B
    fpga.pcis_araddr.ready := true.B
    fpga.pcim_awaddr.ready := true.B
    fpga.pcim_araddr.ready := true.B

    when (fpga.pcis_awaddr.fire) {
      P2PLogger.logInfo("pcis.aw.fire, addr: 0x%x\n", fpga.pcis_awaddr.bits)
    }
    when (fpga.pcis_araddr.fire) {
      P2PLogger.logInfo("pcis.ar.fire, addr: 0x%x\n", fpga.pcis_araddr.bits)
    }


    val QUEUE_DEPTH = 200

    val awAddrQ = Module(new BRAMQueue(QUEUE_DEPTH)(UInt(64.W)))
    val arAddrQ = Module(new BRAMQueue(QUEUE_DEPTH)(UInt(64.W)))

    awAddrQ.io.enq.valid := fpga.pcis_awaddr.valid
    awAddrQ.io.enq.bits  := fpga.pcis_awaddr.bits

    arAddrQ.io.enq.valid := fpga.pcis_araddr.valid
    arAddrQ.io.enq.bits  := fpga.pcis_araddr.bits

    genROReg(awAddrQ.io.deq.valid, "aw_addr_deq_valid")
    genROReg(awAddrQ.io.deq.bits(31, 0) ,  "aw_addr_deq_bits_lo")
    genROReg(awAddrQ.io.deq.bits(63, 32) , "aw_addr_deq_bits_hi")
    Pulsify(genWORegInit(awAddrQ.io.deq.ready, "aw_addr_deq_ready", false.B), pulseLength = 1)

    genROReg(arAddrQ.io.deq.valid, "ar_addr_deq_valid")
    genROReg(arAddrQ.io.deq.bits(31, 0) ,  "ar_addr_deq_bits_lo")
    genROReg(arAddrQ.io.deq.bits(63, 32) , "ar_addr_deq_bits_hi")
    Pulsify(genWORegInit(arAddrQ.io.deq.ready, "ar_addr_deq_ready", false.B), pulseLength = 1)

    val awAddrQ2 = Module(new BRAMQueue(QUEUE_DEPTH)(UInt(64.W)))
    val arAddrQ2 = Module(new BRAMQueue(QUEUE_DEPTH)(UInt(64.W)))

    awAddrQ2.io.enq.valid := fpga.pcim_awaddr.valid
    awAddrQ2.io.enq.bits  := fpga.pcim_awaddr.bits

    arAddrQ2.io.enq.valid := fpga.pcim_araddr.valid
    arAddrQ2.io.enq.bits  := fpga.pcim_araddr.bits

    genROReg(awAddrQ2.io.deq.valid, "m_aw_addr_deq_valid")
    genROReg(awAddrQ2.io.deq.bits(31, 0) ,  "m_aw_addr_deq_bits_lo")
    genROReg(awAddrQ2.io.deq.bits(63, 32) , "m_aw_addr_deq_bits_hi")
    Pulsify(genWORegInit(awAddrQ2.io.deq.ready, "m_aw_addr_deq_ready", false.B), pulseLength = 1)

    genROReg(arAddrQ2.io.deq.valid, "m_ar_addr_deq_valid")
    genROReg(arAddrQ2.io.deq.bits(31, 0) ,  "m_ar_addr_deq_bits_lo")
    genROReg(arAddrQ2.io.deq.bits(63, 32) , "m_ar_addr_deq_bits_hi")
    Pulsify(genWORegInit(arAddrQ2.io.deq.ready, "m_ar_addr_deq_ready", false.B), pulseLength = 1)

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
          base,
          sb,
          "p2p_control_t",
          "p2p_control",
          Seq(),
          "GET_CORE_CONSTRUCTOR"
      )
    }
  }
}
