package strober

import Chisel._
import junctions.{NASTIDataBits, NASTIAddrBits, NASTIIdBits}
import junctions.{MIFAddrBits, MIFDataBits, MIFTagBits, MIFDataBeats}

// NASTI Params
case object NASTIName extends Field[String]
case object NASTIAddrSizeBits extends Field[Int]
case object ResetAddr extends Field[Int]
case object SRAMRestartAddr extends Field[Int]

// Mem Params
case object MemBlockBytes extends Field[Int]

// Simulation Params
case object SampleNum  extends Field[Int]
case object TraceLen   extends Field[Int]
case object DaisyWidth extends Field[Int]

object NASTIParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case NASTIAddrBits => site(NASTIName) match {
      case "Master" => Dump("NASTI_ADDR_WIDTH", 32)
      case "Slave"  => 32 
    }
    case NASTIDataBits => site(NASTIName) match {
      case "Master" => Dump("NASTI_DATA_WIDTH", 32)
      case "Slave"  => 64
    }
    case NASTIIdBits => site(NASTIName) match {
      case "Master" => 12 
      case "Slave"  => 6
    }
    case NASTIAddrSizeBits => 10
    // case ResetAddr         => Dump("RESET_ADDR", (1 << site(MAXIAddrSize)) - 1)
    // case SRAMRestartAddr   => Dump("SRAM_RESTART_ADDR", site(ResetAddr) - 1)

    case MIFAddrBits     => Dump("MEM_ADDR_WIDTH", 32)
    case MIFDataBits     => Dump("MEM_DATA_WIDTH", 16 << 3)
    case MIFTagBits      => 5
    case MIFDataBeats    => 0
    case MemBlockBytes   => 16
  }
}

object SimParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case SampleNum  => Dump("SAMPLE_NUM",  30)
    case TraceLen   => Dump("TRACE_LEN",   16)
    case DaisyWidth => Dump("DAISY_WIDTH", 32)
  }
}
