package strober

import Chisel._
import junctions._

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

    case MIFAddrBits     => Dump("MEM_ADDR_WIDTH", 32)
    case MIFDataBits     => Dump("MEM_DATA_WIDTH", 16 << 3)
    case MIFTagBits      => 5
    case MIFDataBeats    => 1
    case MemBlockBytes   => 16
    case MemAddrSizeBits => 28
  }
}

object SimParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case SampleNum  => Dump("SAMPLE_NUM",  30)
    case TraceLen   => Dump("TRACE_LEN",   16)
    case DaisyWidth => Dump("DAISY_WIDTH", 32)
    case ChannelWidth => Dump("CHANNEL_WIDTH", 32)
  }
}
