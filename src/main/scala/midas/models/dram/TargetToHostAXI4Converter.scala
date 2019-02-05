package midas
package models

import Chisel._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import junctions.NastiParameters

// WARNING: The address widths are totally bungled here. This is intended
// for use with the memory model only
// We're going to rely on truncation of the (sometimes) wider master address
// later on
//
// For identical widths this module becomes passthrough
class TargetToHostAXI4Converter (
    mWidths: NastiParameters,
    sWidths: NastiParameters,
    mMaxTransfer: Int = 128)
  (implicit p: Parameters) extends LazyModule
{
  implicit val valname = ValName("FASEDWidthAdapter")
  val m   = AXI4MasterNode(Seq(AXI4MasterPortParameters(
    masters = Seq(AXI4MasterParameters(
      name = "widthAdapter",
      aligned = true,
      maxFlight = Some(2),
      id   = IdRange(0, (1 << mWidths.idBits))))))) // FIXME: Idbits

  val s   = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = Seq(AddressSet(0, (BigInt(1) << mWidths.addrBits) - 1)),
        supportsWrite = TransferSizes(1, mMaxTransfer),
        supportsRead  = TransferSizes(1, mMaxTransfer),
        interleavedId = Some(0))),             // slave does not interleave read responses
      beatBytes = sWidths.dataBits/8)
  ))

  // If no width change necessary, pass through with a buffer
  if (mWidths.dataBits == sWidths.dataBits) {
    s := m
  } else {
    // Otherwise we need to convert to TL2 and back
    val xbar = LazyModule(new TLXbar)
    val error = LazyModule(new TLError(ErrorParams(
      Seq(AddressSet(BigInt(1) << mWidths.addrBits, 0xff)), maxAtomic = 1, maxTransfer = 128),
      beatBytes = sWidths.dataBits/8))

    (xbar.node
      := TLWidthWidget(mWidths.dataBits/8)
      := TLFIFOFixer()
      := AXI4ToTL()
      := AXI4Buffer()
      := m )
    error.node := xbar.node
    (s := AXI4Buffer()
       := AXI4UserYanker()
       := TLToAXI4()
       := xbar.node)
   }

  lazy val module = new LazyModuleImp(this) {
    val mAxi4 = IO(Flipped(m.out.head._1.cloneType))
    m.out.head._1 <> mAxi4
    val sAxi4 = IO(s.in.head._1.cloneType)
    sAxi4 <> s.in.head._1
  }
}
