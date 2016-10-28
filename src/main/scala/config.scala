package strober

import cde.{Parameters, Config}
import junctions.NastiParameters
import midas_widgets._

class SimConfig extends Config(
  (key, site, here) => key match {
    case TraceMaxLen    => 1024
    case DaisyWidth     => 32
    case SRAMChainNum   => 1
    case ChannelLen     => 16
    case ChannelWidth   => 32
    case EnableSnapshot => false
  }
)

class ZynqConfig extends Config(new SimConfig ++ new Config(
  (key, site, here) => key match {
    case CtrlNastiKey => NastiParameters(32, 32, 12)
    case SlaveNastiKey => NastiParameters(64, 32, 6)
    case MemModelKey => None
    case ZynqMMIOSize => BigInt(1) << 12 // 4 KB
  })
)
