/// See LICENSE for license details.

package midas.models

import chisel3._
import junctions._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

/** A Wrapper around AXI4 User Yanker to limit max flight to 1 MaxInputFlight is
  * known because of constraints within the memory model.
  *
  * This can be deployed in the frontend of nasti timing models to ensure that
  * there are not multiple transactions in flight to the same ID.
  */
class NastiFlightLimiter(
    maxInputFlight: Int,
    maxReadXferBytes: Int,
    maxWriteXferBytes: Int,
    params: NastiParameters) (implicit p: Parameters) extends LazyModule {

  val inNode = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "fased-memory-timing-model",
        id   = IdRange(0, 1 << params.idBits),
        maxFlight = Some(maxInputFlight)
      )))))

  val outNode =
    AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(0, ((BigInt(1) << params.addrBits) - 1))),
          resources     = (new MemoryDevice).reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = false,
          supportsWrite = TransferSizes(1, maxWriteXferBytes),
          supportsRead  = TransferSizes(1, maxReadXferBytes),
          interleavedId = Some(0))), // slave does not interleave read responses
        beatBytes = params.dataBits/8)
    ))

  (outNode := AXI4UserYanker(Some(1)) := inNode)

  lazy val module = new LazyModuleImp(this) {
    implicit val np = p.alterPartial {
      case NastiKey => params
    }

    val in = IO(Flipped(new NastiIO()))
    val (axi4In, _)  = inNode.out.head
    AXI4NastiAssigner.toAXI4(axi4In, in)


    val out = IO(new NastiIO())
    val (axi4Out, _) = outNode.in.head
    AXI4NastiAssigner.toNasti(out, axi4Out)
  }
}
