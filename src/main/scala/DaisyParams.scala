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
  val addrlen = 32
  val taglen = 5
  val memlen = 32
  val daisylen = 32
  val cmdlen = 6
  val tracelen = 16  

  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case HostLen => hostlen
    case AddrLen => addrlen
    case TagLen => taglen
    case MemLen => memlen
    case DaisyLen => daisylen
    case CmdLen => cmdlen
    case TraceLen => tracelen
  }
}

import daisyParams._

class DaisyConfig extends ChiselConfig (
  (pname,site,here) => pname match {
    case HostLen => hostlen
    case AddrLen => addrlen
    case TagLen => taglen
    case MemLen => memlen
    case DaisyLen => daisylen
    case CmdLen => cmdlen
  }
)

abstract trait DaisyShimParams extends UsesParameters {
  val hostLen = params(HostLen)
  val addrLen = params(AddrLen)
  val tagLen  = params(TagLen)
  val memLen  = params(MemLen) 
  val daisyLen = params(DaisyLen)
  val traceLen = params(TraceLen)
  val tagNum = math.pow(2, tagLen).toInt
}

abstract trait DebugCommands extends UsesParameters {
  val cmdLen = params(CmdLen)
  val STEP  = UInt(0, cmdLen)
  val POKE  = UInt(1, cmdLen)
  val PEEK  = UInt(2, cmdLen)
  val POKED = UInt(3, cmdLen)
  val PEEKD = UInt(4, cmdLen)
  val TRACE = UInt(5, cmdLen)
  val MEM   = UInt(6, cmdLen)
}

