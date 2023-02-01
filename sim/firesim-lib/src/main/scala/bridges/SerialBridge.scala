//See LICENSE for license details
package firesim.bridges

import midas.widgets._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

import testchipip.{SerialIO, SerialAdapter}

/**
  * Class which parameterizes the SerialBridge
  *
  * memoryRegionNameOpt, if unset, indicates that firesim-fesvr should not attempt to write a payload into DRAM through the loadmem unit.
  * This is suitable for target designs which do not use the FASED DRAM model.
  * If a FASEDBridge for the backing AXI4 memory is present, then memoryRegionNameOpt should be set to the same memory region name which is passed
  * to the FASEDBridge. This enables fast payload loading in firesim-fesvr through the loadmem unit.
  */
case class SerialBridgeParams(memoryRegionNameOpt: Option[String])

class SerialBridge(memoryRegionNameOpt: Option[String]) extends BlackBox with Bridge[HostPortIO[SerialBridgeTargetIO], SerialBridgeModule] {
  val io = IO(new SerialBridgeTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = Some(SerialBridgeParams(memoryRegionNameOpt))
  generateAnnotations()
}

object SerialBridge {
  def apply(clock: Clock, port: SerialIO, memoryRegionNameOpt: Option[String], reset: Bool)(implicit p: Parameters): SerialBridge = {
    val ep = Module(new SerialBridge(memoryRegionNameOpt))
    ep.io.serial <> port
    ep.io.clock := clock
    ep.io.reset := reset
    ep
  }
}

class SerialBridgeTargetIO extends Bundle {
  val serial = Flipped(new SerialIO(SerialAdapter.SERIAL_TSI_WIDTH))
  val reset = Input(Bool())
  val clock = Input(Clock())
}

class SerialBridgeModule(serialBridgeParams: SerialBridgeParams)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[SerialBridgeTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO)
    val hPort = IO(HostPort(new SerialBridgeTargetIO))

    val serialBits = SerialAdapter.SERIAL_TSI_WIDTH
    val inBuf  = Module(new Queue(UInt(serialBits.W), 16))
    val outBuf = Module(new Queue(UInt(serialBits.W), 16))
    val tokensToEnqueue = RegInit(0.U(32.W))

    val target = hPort.hBits.serial
    val tFire = hPort.toHost.hValid && hPort.fromHost.hReady && tokensToEnqueue =/= 0.U
    val targetReset = tFire & hPort.hBits.reset
    inBuf.reset  := reset.asBool || targetReset
    outBuf.reset := reset.asBool || targetReset

    hPort.toHost.hReady := tFire
    hPort.fromHost.hValid := tFire

    target.in <> inBuf.io.deq
    inBuf.io.deq.ready := target.in.ready && tFire

    outBuf.io.enq <> target.out
    outBuf.io.enq.valid := target.out.valid && tFire

    genWOReg(inBuf.io.enq.bits, "in_bits")
    Pulsify(genWORegInit(inBuf.io.enq.valid, "in_valid", false.B), pulseLength = 1)
    genROReg(inBuf.io.enq.ready, "in_ready")
    genROReg(outBuf.io.deq.bits, "out_bits")
    genROReg(outBuf.io.deq.valid, "out_valid")
    Pulsify(genWORegInit(outBuf.io.deq.ready, "out_ready", false.B), pulseLength = 1)

    val stepSize = Wire(UInt(32.W))
    val start = Wire(Bool())
    when (start) {
      tokensToEnqueue := stepSize
    }.elsewhen (tFire) {
      tokensToEnqueue := tokensToEnqueue - 1.U
    }

    genWOReg(stepSize, "step_size")
    genROReg(tokensToEnqueue === 0.U, "done")
    Pulsify(genWORegInit(start, "start", false.B), pulseLength = 1)

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, memoryRegions, sb)
      val memoryRegionNameOpt = serialBridgeParams.memoryRegionNameOpt
      val offsetConst = memoryRegionNameOpt.map(memoryRegions(_)).getOrElse(BigInt(0))
      sb.append(genMacro(s"${headerWidgetName}_has_memory", memoryRegionNameOpt.isDefined.toString))
      sb.append(genMacro(s"${headerWidgetName}_memory_offset", UInt64(offsetConst)))
    }
  }
}
