package midas.widgets


import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy.InModuleBody
import midas.MetasimPrintfEnable
import midas.targetutils.{FireSimQueueHelper}
import midas.targetutils.xdc

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

    val awAddrQIO = FireSimQueueHelper.makeIO(UInt(64.W), QUEUE_DEPTH,
                      isFireSim=true, overrideStyle=Some(xdc.RAMStyles.ULTRA))
    val arAddrQIO = FireSimQueueHelper.makeIO(UInt(64.W), QUEUE_DEPTH,
                      isFireSim=true, overrideStyle=Some(xdc.RAMStyles.ULTRA))

    awAddrQIO.enq.valid := fpga.pcis_awaddr.valid
    awAddrQIO.enq.bits  := fpga.pcis_awaddr.bits

    arAddrQIO.enq.valid := fpga.pcis_araddr.valid
    arAddrQIO.enq.bits  := fpga.pcis_araddr.bits

    genROReg(awAddrQIO.deq.valid, "aw_addr_deq_valid")
    genROReg(awAddrQIO.deq.bits(31, 0) ,  "aw_addr_deq_bits_lo")
    genROReg(awAddrQIO.deq.bits(63, 32) , "aw_addr_deq_bits_hi")
    Pulsify(genWORegInit(awAddrQIO.deq.ready, "aw_addr_deq_ready", false.B), pulseLength = 1)

    genROReg(arAddrQIO.deq.valid, "ar_addr_deq_valid")
    genROReg(arAddrQIO.deq.bits(31, 0) ,  "ar_addr_deq_bits_lo")
    genROReg(arAddrQIO.deq.bits(63, 32) , "ar_addr_deq_bits_hi")
    Pulsify(genWORegInit(arAddrQIO.deq.ready, "ar_addr_deq_ready", false.B), pulseLength = 1)

    val awAddrQ2IO = FireSimQueueHelper.makeIO(UInt(64.W), QUEUE_DEPTH,
                      isFireSim=true, overrideStyle=Some(xdc.RAMStyles.ULTRA))
    val arAddrQ2IO = FireSimQueueHelper.makeIO(UInt(64.W), QUEUE_DEPTH,
                      isFireSim=true, overrideStyle=Some(xdc.RAMStyles.ULTRA))

    awAddrQ2IO.enq.valid := fpga.pcim_awaddr.valid
    awAddrQ2IO.enq.bits  := fpga.pcim_awaddr.bits

    arAddrQ2IO.enq.valid := fpga.pcim_araddr.valid
    arAddrQ2IO.enq.bits  := fpga.pcim_araddr.bits

    genROReg(awAddrQ2IO.deq.valid, "m_aw_addr_deq_valid")
    genROReg(awAddrQ2IO.deq.bits(31, 0) ,  "m_aw_addr_deq_bits_lo")
    genROReg(awAddrQ2IO.deq.bits(63, 32) , "m_aw_addr_deq_bits_hi")
    Pulsify(genWORegInit(awAddrQ2IO.deq.ready, "m_aw_addr_deq_ready", false.B), pulseLength = 1)

    genROReg(arAddrQ2IO.deq.valid, "m_ar_addr_deq_valid")
    genROReg(arAddrQ2IO.deq.bits(31, 0) ,  "m_ar_addr_deq_bits_lo")
    genROReg(arAddrQ2IO.deq.bits(63, 32) , "m_ar_addr_deq_bits_hi")
    Pulsify(genWORegInit(arAddrQ2IO.deq.ready, "m_ar_addr_deq_ready", false.B), pulseLength = 1)

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
