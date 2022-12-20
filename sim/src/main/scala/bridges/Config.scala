//See LICENSE for license details.

package firesim.bridges

import firesim.configs.WithDefaultMemModel

import freechips.rocketchip.config._
import freechips.rocketchip.subsystem.{PeripheryBusKey, PeripheryBusParams}
import testchipip.{BlockDeviceConfig, BlockDeviceKey}

class NoConfig extends Config(Parameters.empty)

class BaseBridgesConfig
    extends Config(
      new WithDefaultMemModel
    )

class DefaultF1Config
    extends Config(
      new BaseBridgesConfig ++
        new midas.F1Config
    )
class DefaultVitisConfig
    extends Config(
      new BaseBridgesConfig ++
        new midas.VitisConfig
    )

class UARTConfig
    extends Config((site, here, up) => { case PeripheryBusKey =>
      PeripheryBusParams(beatBytes = 1, blockBytes = 8, dtsFrequency = Some(100000000))
    })

class BlockDevConfig
    extends Config((site, here, up) => {
      case PeripheryBusKey =>
      case BlockDeviceKey  => Some(BlockDeviceConfig())
    })
