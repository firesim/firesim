//See LICENSE for license details.
package firesim.firesim

import freechips.rocketchip.config.Config
import firesim.configs._
import firesim.configs._
import firesim.bridges._
import firesim.configs._

/**
  * Adds BlockDevice to WithMinimalFireSimHighPerfConfigTweaks
  */
class WithMinimalAndBlockDeviceFireSimHighPerfConfigTweaks extends Config(
  new WithFireSimHighPerfClocking ++
  new freechips.rocketchip.subsystem.WithNoMemPort ++ // removes mem port for FASEDBridge to match against
  new testchipip.WithBackingScratchpad ++ // adds backing scratchpad for memory to replace FASED model
  new testchipip.WithBlockDevice(true) ++ // add in block device
  new WithMinimalFireSimDesignTweaks
)

/**
  *  Adds Block device to WithMinimalFireSimHighPerfConfigTweaks
  */
class WithMinimalAndFASEDFireSimHighPerfConfigTweaks extends Config(
  new WithFireSimHighPerfClocking ++
  new WithDefaultMemModel ++ // add default FASED memory model
  new WithMinimalFireSimDesignTweaks
)
