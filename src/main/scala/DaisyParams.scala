package daisy

import Chisel._

case object HostLen extends Field[Int]
case object AddrLen extends Field[Int]
case object TagLen extends Field[Int]
case object MemLen extends Field[Int]
case object DaisyLen extends Field[Int]
case object CmdLen extends Field[Int]
case object TraceLen extends Field[Int]

object daisyParams {
  val hostlen = 32
  val addrlen = 26
  val memlen = 32
  val taglen = 5
  val cmdlen = 4
  val daisylen = 32
  val tracelen = 16  

  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case HostLen => Dump("HTIF_WIDTH", hostlen)
    case AddrLen => Dump("MIF_ADDR_BITS", addrlen)
    case MemLen => Dump("MIF_DATA_BITS", memlen)
    case TagLen => Dump("MIF_TAG_BITS", taglen)
    case CmdLen => Dump("CMD_BITS", cmdlen)
    case DaisyLen => daisylen
    case TraceLen => tracelen
  }
}

import daisyParams._

// Enum type for step response
object StepResp extends Enumeration {
  val FIN, TRACE, PEEKD = Value
}

abstract trait DaisyShimParams extends UsesParameters {
  val hostLen = params(HostLen)
  val addrLen = params(AddrLen)
  val tagLen  = params(TagLen)
  val memLen  = params(MemLen) 
  val daisyLen = params(DaisyLen)
  val traceLen = params(TraceLen)
  val tagNum = math.pow(2, tagLen).toInt
  val step_FIN   = UInt(StepResp.FIN.id)
  val step_TRACE = UInt(StepResp.TRACE.id)
  val step_PEEKD = UInt(StepResp.PEEKD.id)
}

// Enum type for debug commands
object DebugCmd extends Enumeration {
  val STEP, POKE, PEEK, POKED, PEEKD, TRACE, MEM = Value
}

abstract trait DebugCommands extends UsesParameters {
  val cmdLen = params(CmdLen)
  val STEP  = UInt(DebugCmd.STEP.id, cmdLen)
  val POKE  = UInt(DebugCmd.POKE.id, cmdLen)
  val PEEK  = UInt(DebugCmd.PEEK.id, cmdLen)
  val POKED = UInt(DebugCmd.POKED.id, cmdLen)
  val PEEKD = UInt(DebugCmd.PEEKD.id, cmdLen)
  val TRACE = UInt(DebugCmd.TRACE.id, cmdLen)
  val MEM   = UInt(DebugCmd.MEM.id, cmdLen)
}

