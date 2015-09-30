package strober

import Chisel._

// AXI Params
case object MAXIAddrWidth extends Field[Int]
case object MAXIDataWidth extends Field[Int]
case object MAXITagWidth extends Field[Int]
case object MAXIAddrSize extends Field[Int]
case object MAXIAddrOffset extends Field[Int]
case object ResetAddr extends Field[Int]
case object SRAMRestartAddr extends Field[Int]
case object SAXIAddrWidth extends Field[Int]
case object SAXIDataWidth extends Field[Int]
case object SAXITagWidth extends Field[Int]

// Mem Params
case object MemDataCount extends Field[Int]
case object MemDataWidth extends Field[Int]
case object MemAddrWidth extends Field[Int]
case object MemTagWidth extends Field[Int]
case object MemBlockSize extends Field[Int]
case object MemBlockOffset extends Field[Int]

// Simulation Params
case object SampleNum extends Field[Int]
case object TraceLen extends Field[Int]
case object DaisyWidth extends Field[Int]

object AXI4Params {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case MAXIAddrWidth => Dump("AXI_ADDR_WIDTH", 32)
    case MAXIDataWidth => Dump("AXI_DATA_WIDTH", 32)
    case MAXITagWidth => 12
    case MAXIAddrSize => 10
    case MAXIAddrOffset => log2Up(site(MAXIAddrWidth)>>3)
    case ResetAddr => Dump("RESET_ADDR", (1 << site(MAXIAddrSize)) - 1)
    case SRAMRestartAddr => Dump("SRAM_RESTART_ADDR", site(ResetAddr) - 1)
    case SAXIAddrWidth => 32 
    case SAXIDataWidth => 64 
    case SAXITagWidth => 6
    case MemAddrWidth => Dump("MEM_ADDR_WIDTH", 32)
    case MemDataWidth => Dump("MEM_DATA_WIDTH", 16 << 3)
    case MemDataCount => Dump("MEM_DATA_COUNT", (site(MemBlockSize) << 3) / site(MemDataWidth))
    case MemTagWidth => 5
    case MemBlockSize => 16 // in bytes
    case MemBlockOffset => Dump("MEM_BLOCK_OFFSET", log2Up(site(MemBlockSize))) // bit width 
  }
}

object SimParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case SampleNum => Dump("SAMPLE_NUM", 30)
    case TraceLen => Dump("TRACE_LEN", 16)
    case DaisyWidth => Dump("DAISY_WIDTH", 32)
  }
}
