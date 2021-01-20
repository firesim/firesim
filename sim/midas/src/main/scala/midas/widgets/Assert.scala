// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}

import scala.collection.immutable.ListMap

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}

class AssertBundle(val numAsserts: Int) extends Bundle {
  val asserts = Output(UInt(numAsserts.W))
}

class AssertBridgeModule(assertMessages: Seq[String])(implicit p: Parameters) extends BridgeModule[HostPortIO[UInt]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val numAsserts = assertMessages.size
    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(Input(UInt(numAsserts.W))))
    val resume = WireInit(false.B)
    val enable = RegInit(false.B)
    val cycles = RegInit(0.U(64.W))
    val q = Module(new Queue(hPort.hBits.cloneType, 2))
    q.io.enq.valid := hPort.toHost.hValid
    hPort.toHost.hReady := q.io.enq.ready
    q.io.enq.bits := hPort.hBits
    // We only sink tokens, so tie off the return channel
    hPort.fromHost.hValid := true.B

    val asserts = q.io.deq.bits

    val assertId = PriorityEncoder(asserts)
    val assertFire = asserts.orR

    // Tokens will stall when an assertion is firing unless
    // resume is pulsed or assertions are disabled
    val stallN = (!assertFire || resume || !enable)

    val tFireHelper = DecoupledHelper(q.io.deq.valid, stallN)
    val targetFire = tFireHelper.fire
    q.io.deq.ready := tFireHelper.fire(q.io.deq.valid)
    when (targetFire) {
      cycles := cycles + 1.U
    }

    genROReg(assertId, "id")
    genROReg(assertFire && q.io.deq.valid, "fire")
    // FIXME: no hardcode
    genROReg(cycles(31, 0), "cycle_low")
    genROReg(cycles >> 32, "cycle_high")
    Pulsify(genWORegInit(resume, "resume", false.B), pulseLength = 1)
    attach(enable, "enable")
    genCRFile()

    override def genHeader(base: BigInt, sb: StringBuilder) {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, sb)
      sb.append(genConstStatic(s"${headerWidgetName}_assert_count", UInt32(assertMessages.size)))
      sb.append(genArray(s"${headerWidgetName}_assert_messages", assertMessages.map(CStrLit)))
    }
  }
}
