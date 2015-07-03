package strober

import Chisel._

// AXI Params
case object MAXIAddrWidth extends Field[Int]
case object MAXIDataWidth extends Field[Int]
case object MAXITagWidth extends Field[Int]
case object MAXIAddrSize extends Field[Int]
case object MAXIAddrOffset extends Field[Int]
case object ResetAddr extends Field[Int]
case object SAXIAddrWidth extends Field[Int]
case object SAXIDataWidth extends Field[Int]
case object SAXITagWidth extends Field[Int]

// Mem Params
case object MemDataCount extends Field[Int]
case object MemDataWidth extends Field[Int]
case object MemAddrWidth extends Field[Int]
case object MemTagWidth extends Field[Int]
case object BlockOffset extends Field[Int]

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
    case SAXIAddrWidth => 32 
    case SAXIDataWidth => 64 
    case SAXITagWidth => 6
    case MemAddrWidth => Dump("MEM_ADDR_WIDTH", 32)
    case MemDataWidth => Dump("MEM_DATA_WIDTH", 32)
    case MemDataCount => Dump("MEM_DATA_COUNT", (1 << site(BlockOffset)) / (site(MemDataWidth) >> 3))
    case MemTagWidth => 5
    case BlockOffset => Dump("MEM_BLOCK_OFFSET", 2) 
  }
}

object SimParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case SampleNum => 10
    case TraceLen => 1
    case DaisyWidth => 32
  }
}
