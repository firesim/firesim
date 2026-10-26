// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util.Cat
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import midas.widgets._
import org.chipsalliance.cde.config.Parameters

/** Register transport for the existing CPU-managed stream engine. Its stream port uses that engine's private atomic
  * single-beat protocol. Connect it directly: a generic AXI buffer would separate the acknowledgements.
  */
class SlashDDRStreamWidget(implicit p: Parameters) extends Widget {
  private def master(name: String) = AXI4MasterNode(
    Seq(
      AXI4MasterPortParameters(
        Seq(
          AXI4MasterParameters(name, id = IdRange(0, 1), maxFlight = Some(1))
        )
      )
    )
  )
  val memoryNode = master("slash-stream-memory")
  val streamNode  = master("slash-stream-tokens")
  lazy val module = new WidgetImp(this) {
    val io     = IO(new WidgetIO)
    val memory = memoryNode.out.head._1
    val stream = streamNode.out.head._1
    val dma    = Module(
      new SlashDDRStreamDMA(
        memory.params,
        stream.params,
        p(CPUStreamDMAReservedBytes),
        p(HostMemChannelKey).size,
        p(HostMemChannelKey).maxXferBytes,
      )
    )
    memory <> dma.io.mem
    stream <> dma.io.stream
    val lo            = RegInit(0.U(32.W))
    val hi            = RegInit(0.U(32.W))
    val streamAddress = RegInit(0.U(32.W))
    val bytes         = RegInit(0.U(32.W))
    dma.io.address       := Cat(hi, lo)
    dma.io.streamAddress := streamAddress
    dma.io.bytes         := bytes
    attach(lo, "ddr_lo", substruct                           = false)
    attach(hi, "ddr_hi", substruct                           = false)
    attach(streamAddress, "stream", substruct                = false)
    attach(bytes, "bytes", substruct                         = false)
    attachDecoupledSink(dma.io.command, "command", substruct = false)
    attach(dma.io.status, "status", ReadOnly, substruct      = false)
    genCRFile()
    override def genHeader(base: BigInt, regions: Map[String, BigInt], sb: StringBuilder): Unit = {
      sb.append("#ifndef V80_DDR_STREAM_METADATA\n#define V80_DDR_STREAM_METADATA\n")
      for (name <- Seq("ddr_lo", "ddr_hi", "stream", "bytes", "command", "status")) {
        val address = base + crRegistry.lookupAddress(name).get
        sb.append(s"#define V80_DDR_STREAM_${name.toUpperCase} ${address}ULL\n")
      }
      sb.append(s"#define V80_DDR_STREAM_MIN_DDR ${p(CPUStreamDMAReservedBytes)}ULL\n#endif\n")
    }
  }
}
