package daisy

import Chisel._

object daisyParams {
  val hostlen = 32
  val addrlen = 32
  val taglen = 5
  val memlen = 32
  val daisylen = 32
  val cmdlen = 6

  val mask = (key: Any, site: View, here: View, up: View) => key match {
    case HostLen => hostlen
    case AddrLen => addrlen
    case TagLen => taglen
    case MemLen => memlen
    case DaisyLen => daisylen
    case CmdLen => cmdlen
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
