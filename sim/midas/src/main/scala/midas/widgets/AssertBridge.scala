// See LICENSE for license details.

package midas.widgets

import scala.collection.immutable.ListMap

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.DecoupledHelper

import firesim.lib.bridgeutils._

class AssertBridgeRecord(
  assertPortName:  String,
  resetPortName:   String,
  numAsserts:      Int,
  contextPortName: Option[String],
  contextWidth:    Int,
) extends Record {
  val asserts          = Output(UInt(numAsserts.W))
  val underGlobalReset = Output(Bool())
  // The AssertContext signals of this clock domain, packed with the first field in the LSBs
  val context          = contextPortName.map(_ => Output(UInt(contextWidth.W)))
  val elements: ListMap[String, Data] =
    ListMap(assertPortName -> asserts, resetPortName -> underGlobalReset) ++ contextPortName.zip(context)
}

/** An AssertContext signal carried by an assertion bridge (see [[midas.targetutils.AssertContext]]). */
case class AssertContextField(label: String, width: Int)

case class AssertBridgeParameters(
  assertPortName:  String,
  resetPortName:   String,
  assertMessages:  Seq[String],
  contextPortName: Option[String]          = None,
  contextFields:   Seq[AssertContextField] = Seq(),
)

class AssertBridgeModule(params: AssertBridgeParameters)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[AssertBridgeRecord]]()(p) {

  val AssertBridgeParameters(assertPortName, resetPortName, assertMessages, contextPortName, contextFields) = params

  lazy val module = new BridgeModuleImp(this) {
    val numAsserts   = assertMessages.size
    val contextWidth = contextFields.map(_.width).sum
    val io           = IO(new WidgetIO())
    val hPort        = IO(
      HostPort(Input(new AssertBridgeRecord(assertPortName, resetPortName, numAsserts, contextPortName, contextWidth)))
    )
    val resume     = WireInit(false.B)
    val enable     = RegInit(false.B)
    val cycles     = RegInit(0.U(64.W))
    val q          = Module(new Queue(hPort.hBits.cloneType, 2))
    q.io.enq.valid        := hPort.toHost.hValid
    hPort.toHost.hReady   := q.io.enq.ready
    q.io.enq.bits         := hPort.hBits
    // We only sink tokens, so tie off the return channel
    hPort.fromHost.hValid := true.B

    val asserts          = q.io.deq.bits.asserts
    val underGlobalReset = q.io.deq.bits.underGlobalReset

    val assertId   = PriorityEncoder(asserts)
    val assertFire = asserts.orR && !underGlobalReset

    // Tokens will stall when an assertion is firing unless
    // resume is pulsed or assertions are disabled
    val stallN = (!assertFire || resume || !enable)

    val tFireHelper = DecoupledHelper(q.io.deq.valid, stallN)
    val targetFire  = tFireHelper.fire()
    q.io.deq.ready := tFireHelper.fire(q.io.deq.valid)
    when(targetFire) {
      cycles := cycles + 1.U
    }

    // The context values of the token at the head of the queue. While an assertion fires, that token stalls there,
    // so these are the values in the cycle the assertion fired.
    val context = q.io.deq.bits.context.map(c => RegEnable(c, 0.U, q.io.deq.valid))

    // (low, high) register addresses of each context field, zero-extended to 64 bits
    val contextAddrs = contextFields.zip(contextFields.scanLeft(0)(_ + _.width)).zipWithIndex.map {
      case ((field, offset), idx) =>
        val value = context.get.apply(offset + field.width - 1, offset).pad(64)
        (
          attach(value(31, 0), s"context_${idx}_low", ReadOnly, false),
          attach(value(63, 32), s"context_${idx}_high", ReadOnly, false),
        )
    }

    genROReg(assertId, "id")
    genROReg(assertFire && q.io.deq.valid, "fire")
    // FIXME: no hardcode
    genROReg(cycles(31, 0), "cycle_low")
    genROReg(cycles >> 32, "cycle_high")
    Pulsify(genWORegInit(resume, "resume", false.B), pulseLength = 1)
    attach(enable, "enable")
    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        "synthesized_assertions_t",
        "synthesized_assertions",
        Seq(
          StdVector("const char *", assertMessages.map(CStrLit(_))),
          StdVector(
            "synthesized_assertions_t::context_t",
            contextFields.zip(contextAddrs).map { case (field, (low, high)) =>
              CppStruct(
                "synthesized_assertions_t::context_t",
                Seq(
                  "label"     -> CStrLit(field.label),
                  "addr_low"  -> UInt32(base + low),
                  "addr_high" -> UInt32(base + high),
                ),
              )
            },
          ),
        ),
      )
    }
  }
}
