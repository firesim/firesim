// See LICENSE for license details.

package firesim.lib.nasti

import chisel3.{fromIntToWidth, Bool, Bundle, Flipped, UInt}
import chisel3.util.Decoupled

import scala.math.max

case class NastiParameters(dataBits: Int, addrBits: Int, idBits: Int)

// Explicitly chose to not use implicits s.t. it is clear where parameters are propagating.
trait HasNastiParameters {
  val nastiParams: NastiParameters
  val nastiXDataBits   = nastiParams.dataBits
  val nastiWStrobeBits = nastiXDataBits / 8
  val nastiXAddrBits   = nastiParams.addrBits
  val nastiWIdBits     = nastiParams.idBits
  val nastiRIdBits     = nastiParams.idBits
  val nastiXIdBits     = max(nastiWIdBits, nastiRIdBits)
  val nastiXUserBits   = 1
  val nastiAWUserBits  = nastiXUserBits
  val nastiWUserBits   = nastiXUserBits
  val nastiBUserBits   = nastiXUserBits
  val nastiARUserBits  = nastiXUserBits
  val nastiRUserBits   = nastiXUserBits
  val nastiXLenBits    = 8
  val nastiXSizeBits   = 3
  val nastiXBurstBits  = 2
  val nastiXCacheBits  = 4
  val nastiXProtBits   = 3
  val nastiXQosBits    = 4
  val nastiXRegionBits = 4
  val nastiXRespBits   = 2
}

abstract class NastiBundle(val nastiParams: NastiParameters) extends Bundle with HasNastiParameters

abstract class NastiChannel(nastiParams: NastiParameters) extends NastiBundle(nastiParams)
abstract class NastiMasterToSlaveChannel(nastiParams: NastiParameters) extends NastiChannel(nastiParams)
abstract class NastiSlaveToMasterChannel(nastiParams: NastiParameters) extends NastiChannel(nastiParams)

class NastiReadIO(nastiParams: NastiParameters) extends Bundle {
  val ar = Decoupled(new NastiReadAddressChannel(nastiParams))
  val r  = Flipped(Decoupled(new NastiReadDataChannel(nastiParams)))
}

class NastiWriteIO(nastiParams: NastiParameters) extends Bundle {
  val aw = Decoupled(new NastiWriteAddressChannel(nastiParams))
  val w  = Decoupled(new NastiWriteDataChannel(nastiParams))
  val b  = Flipped(Decoupled(new NastiWriteResponseChannel(nastiParams)))
}

class NastiIO(nastiParams: NastiParameters) extends NastiBundle(nastiParams) {
  val aw = Decoupled(new NastiWriteAddressChannel(nastiParams))
  val w  = Decoupled(new NastiWriteDataChannel(nastiParams))
  val b  = Flipped(Decoupled(new NastiWriteResponseChannel(nastiParams)))
  val ar = Decoupled(new NastiReadAddressChannel(nastiParams))
  val r  = Flipped(Decoupled(new NastiReadDataChannel(nastiParams)))
}

trait HasNastiMetadata extends HasNastiParameters {
  val addr   = UInt(nastiXAddrBits.W)
  val len    = UInt(nastiXLenBits.W)
  val size   = UInt(nastiXSizeBits.W)
  val burst  = UInt(nastiXBurstBits.W)
  val lock   = Bool()
  val cache  = UInt(nastiXCacheBits.W)
  val prot   = UInt(nastiXProtBits.W)
  val qos    = UInt(nastiXQosBits.W)
  val region = UInt(nastiXRegionBits.W)
}

class NastiAddressChannel(nastiParams: NastiParameters)
    extends NastiMasterToSlaveChannel(nastiParams)
    with HasNastiMetadata

class NastiResponseChannel(nastiParams: NastiParameters) extends NastiSlaveToMasterChannel(nastiParams) {
  val resp = UInt(nastiXRespBits.W)
}

class NastiWriteAddressChannel(nastiParams: NastiParameters) extends NastiAddressChannel(nastiParams) {
  val id   = UInt(nastiWIdBits.W)
  val user = UInt(nastiAWUserBits.W)
}

trait HasNastiData extends HasNastiParameters {
  val data = UInt(nastiXDataBits.W)
  val last = Bool()
}

class NastiWriteDataChannel(nastiParams: NastiParameters)
    extends NastiMasterToSlaveChannel(nastiParams)
    with HasNastiData {
  val id   = UInt(nastiWIdBits.W)
  val strb = UInt(nastiWStrobeBits.W)
  val user = UInt(nastiWUserBits.W)
}

class NastiWriteResponseChannel(nastiParams: NastiParameters) extends NastiResponseChannel(nastiParams) {
  val id   = UInt(nastiWIdBits.W)
  val user = UInt(nastiBUserBits.W)
}

class NastiReadAddressChannel(nastiParams: NastiParameters) extends NastiAddressChannel(nastiParams) {
  val id   = UInt(nastiRIdBits.W)
  val user = UInt(nastiARUserBits.W)
}

class NastiReadDataChannel(nastiParams: NastiParameters) extends NastiResponseChannel(nastiParams) with HasNastiData {
  val id   = UInt(nastiRIdBits.W)
  val user = UInt(nastiRUserBits.W)
}
