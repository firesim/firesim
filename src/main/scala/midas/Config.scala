package midas

import core._
import widgets._
import platform._
import cde.{Parameters, Config, Field}
import junctions.{NastiKey, NastiParameters}

trait PlatformType
case object Zynq extends PlatformType
case object Catapult extends PlatformType
case object Platform extends Field[PlatformType]
case object EnableSnapshot extends Field[Boolean]
case object EnableMemModel extends Field[Boolean]

class SimConfig extends Config(
  (key, site, here) => key match {
    case TraceMaxLen    => 1024
    case DaisyWidth     => 32
    case SRAMChainNum   => 1
    case ChannelLen     => 16
    case ChannelWidth   => 32
    case EnableSnapshot => false
    case EnableMemModel => true
    case CtrlNastiKey   => NastiParameters(32, 32, 12)
    case MemNastiKey    => NastiParameters(64, 32, 6)
    case MemModelKey    => None
    case FpgaMMIOSize   => BigInt(1) << 12 // 4 KB
  }
)

class ZynqConfig extends Config(new Config(
  (key, site, here) => key match {
    case Platform       => Zynq
    case MasterNastiKey => site(CtrlNastiKey)
    case SlaveNastiKey  => site(MemNastiKey)
  }) ++ new SimConfig
)

class ZynqConfigWithSnapshot extends Config(new Config(
  (key, site, here) => key match {
    case EnableSnapshot => true
  }) ++ new ZynqConfig
)

class CatapultConfig extends Config(new Config(
  (key, site, here) => key match {
    case Platform       => Catapult
    case PCIeWidth      => 640
    case NastiKey       => site(CtrlNastiKey)
    case EnableMemModel => false
  }) ++ new SimConfig
)

class CatapultConfigWithSnapshot extends Config(new Config(
  (key, site, here) => key match {
    case EnableSnapshot => true
  }) ++ new CatapultConfig
)
