package strober

import Chisel._

// AXI Params
case object MAXIAddrWidth extends Field[Int]
case object MAXIDataWidth extends Field[Int]
case object MAXIAddrSize extends Field[Int]
case object MAXIAddrOffset extends Field[Int]
case object ResetAddr extends Field[Int]
case object SAXIAddrWidth extends Field[Int]
case object SAXIDataWidth extends Field[Int]

// Mem Params
case object MemDataWidth extends Field[Int]
case object MemAddrWidth extends Field[Int]
case object MemTagWidth extends Field[Int]
case object BlockOffset extends Field[Int]

case object DaisyWidth extends Field[Int]

object AXI4Params {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case MAXIAddrWidth => Dump("M_AXI_ADDR_WIDTH", 32)
    case MAXIDataWidth => Dump("M_AXI_DATA_WIDTH", 32)
    case MAXIAddrSize => 10
    case MAXIAddrOffset => Dump("M_ADDR_OFFSET", log2Up(site(MAXIAddrWidth)>>3))
    case ResetAddr => Dump("RESET_ADDR", (1 << site(MAXIAddrSize)) - 1)
    case SAXIAddrWidth => Dump("S_AXI_ADDR_WIDTH", 32)
    case SAXIDataWidth => Dump("S_AXI_DATA_WIDTH", 64)
    case MemAddrWidth => Dump("MEM_ADDR_WIDTH", 32)
    case MemDataWidth => Dump("MEM_DATA_WIDTH", 32)
    case MemTagWidth => 5
    case BlockOffset => 2
  }
}

object SimParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case _ =>
  }
}
