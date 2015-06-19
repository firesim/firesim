package strober

import Chisel._

// AXI Params
case object AXIAddrWidth extends Field[Int]
case object AXIDataWidth extends Field[Int]
case object AddrRegWidth extends Field[Int]
case object ResetAddr extends Field[Int]

// Snapshot Params
case object TraceLen extends Field[Int]
case object DaisyWidth extends Field[Int]
// Strober Params
case object MemDataWidth extends Field[Int]
case object MemAddrWidth extends Field[Int]
case object MemTagWidth extends Field[Int]
case object HostCmdWidth extends Field[Int]

object AXI4Params {
  val axiAddrWidth = 32
  val axiDataWidth = 32
  val addrRegWidth = 10

  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case AXIAddrWidth => Dump("AXI_ADDR_WIDTH", axiAddrWidth)
    case AXIDataWidth => Dump("AXI_DATA_WIDTH", axiDataWidth)
    case AddrRegWidth => Dump("ADDR_REG_WIDTH", addrRegWidth)
    case ResetAddr => Dump("RESET_ADDR", (1 << addrRegWidth) - 1)
  }
}

object SimParams {
  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case _ =>
  }
}

object StroberParams {
  val axiFifoLen = 32
  val traceLen = 32
  val memAddrWidth = 32
  val memDataWidth = 32
  val memTagWidth = 5
  val cmdWidth = 2

  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case TraceLen => Dump("TRACE_LEN", traceLen)

    case MemAddrWidth => Dump("MIF_ADDR_BITS", memAddrWidth)
    case MemDataWidth => Dump("MIF_DATA_BITS", memDataWidth)
    case MemTagWidth => Dump("MIF_TAG_BITS", memTagWidth)
    case HostCmdWidth => Dump("HOST_CMD_LEN", cmdWidth)
    case DaisyWidth => here(AXIDataWidth)
  }
}

abstract trait SnapshotParams extends UsesParameters {
  val traceLen     = params(TraceLen)
  val daisyWidth   = params(DaisyWidth)
}

abstract trait AXIParams extends UsesParameters {
  val axiDataWidth = params(AXIDataWidth)
  val axiAddrWidth = params(AXIAddrWidth)
}

abstract trait StroberParams extends SnapshotParams with AXIParams

// Enum type for simulation commands
object Cmd extends Enumeration {
  val STEP, TRACE, MEM = Value
}

abstract trait HostIFParams extends StroberParams {
  val memAddrWidth = params(MemAddrWidth)
  val memDataWidth = params(MemDataWidth) 
  val memTagWidth  = params(MemTagWidth)
  val tagNum = math.pow(2, memTagWidth).toInt
  val cmdWidth = params(HostCmdWidth)
  val STEP  = UInt(Cmd.STEP.id,  cmdWidth)
  val TRACE = UInt(Cmd.TRACE.id, cmdWidth)
  val MEM   = UInt(Cmd.MEM.id,   cmdWidth)
}
