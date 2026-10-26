// See LICENSE for license details.
package midas.core

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import midas.widgets._
import org.chipsalliance.cde.config.Parameters

/** Optional register transport for the CPU stream engine's atomic single-beat interface. Connect directly to that
  * engine; an AXI buffer would split its coupled acknowledgements.
  */
class MMIOStreamWidget(implicit p: Parameters) extends Widget {
  val streamNode  = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(Seq(AXI4MasterParameters("mmio-stream", id = IdRange(0, 1), maxFlight = Some(1)))))
  )
  lazy val module = new WidgetImp(this) {
    val io          = IO(new WidgetIO)
    val bus         = streamNode.out.head._1
    require(bus.params.dataBits == 32 && bus.params.addrBits == 32)
    val address     = RegInit(0.U(32.W))
    val tx          = Wire(Decoupled(UInt(32.W)))
    val readCommand = Wire(Decoupled(UInt(32.W)))
    val remaining   = RegInit(0.U(32.W))
    val responses   = Module(new Queue(UInt(32.W), 32))
    bus.aw.bits            := 0.U.asTypeOf(bus.aw.bits)
    bus.aw.bits.addr       := address
    bus.aw.bits.size       := 2.U
    bus.aw.valid           := tx.valid
    bus.w.bits             := 0.U.asTypeOf(bus.w.bits)
    bus.w.bits.data        := tx.bits
    bus.w.bits.strb        := 15.U
    bus.w.bits.last        := true.B
    bus.w.valid            := tx.valid
    bus.b.ready            := true.B
    tx.ready               := bus.aw.ready && bus.w.ready && bus.b.valid
    bus.ar.bits            := 0.U.asTypeOf(bus.ar.bits)
    bus.ar.bits.addr       := address
    bus.ar.bits.size       := 2.U
    bus.ar.valid           := remaining =/= 0.U
    bus.r.ready            := responses.io.enq.ready
    responses.io.enq.valid := bus.r.valid
    responses.io.enq.bits  := bus.r.bits.data
    readCommand.ready      := remaining === 0.U && responses.io.count === 0.U
    when(readCommand.fire) { remaining := readCommand.bits }
    when(bus.r.fire) { remaining := remaining - 1.U }
    when(tx.valid) {
      assert(
        bus.aw.fire === bus.w.fire && bus.w.fire === bus.b.fire,
        "MMIO stream transport requires atomic single-beat writes",
      )
    }
    assert(bus.ar.fire === bus.r.fire, "MMIO stream transport requires atomic single-beat reads")
    when(bus.b.fire) { assert(bus.b.bits.resp === 0.U, "MMIO stream write response error") }
    when(bus.r.fire) { assert(bus.r.bits.resp === 0.U && bus.r.bits.last, "MMIO stream read response error") }
    attach(address, "address", substruct = false)
    attachDecoupledSink(tx, "tx", substruct                  = false)
    attachDecoupledSink(readCommand, "read_words", substruct = false)
    // The V80 NoC may extend a 32-bit read through the remaining lanes of its
    // 128-bit granule. Put the destructive FIFO read in the last lane so that
    // such a suffix neither consumes extra words nor reads a write-only sink.
    attachDecoupledSource(responses.io.deq, "rx", substruct  = false)
    genCRFile()
    override def genHeader(base: BigInt, regions: Map[String, BigInt], sb: StringBuilder): Unit = {
      sb.append("#ifndef V80_MMIO_STREAM_METADATA\n#define V80_MMIO_STREAM_METADATA\n")
      for (name <- Seq("address", "tx", "rx", "read_words"))
        sb.append(s"#define V80_MMIO_STREAM_${name.toUpperCase} ${base + crRegistry.lookupAddress(name).get}ULL\n")
      sb.append("#endif\n")
    }
  }
}
