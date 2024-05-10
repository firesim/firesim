package firesim.configs

import org.chipsalliance.cde.config.Config
import midas._

class RocketTilePCISF1Config extends Config(
  new WithFireAxePCISConfig(Seq(
    Seq("RocketTile")
  )) ++
  new BaseF1Config)

class DualRocketTilePCISF1Config extends Config(
  new WithFireAxePCISConfig(Seq(
    (0 until 2).map(i => s"RocketTile_${i}")
  )) ++
  new BaseF1Config)

class QuadRocketTilePCISF1Config extends Config(
  new WithFireAxePCISConfig(Seq(
    (0 until 4).map(i => s"RocketTile_${i}")
  )) ++
  new BaseF1Config)

class RocketTileQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig(Seq(Seq("RocketTile"))) ++
  new BaseXilinxAlveoU250Config)

class QuadTileRingNoCTopoQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPNoCConfig(Seq(
    Seq("0", "1"),
    Seq("2", "3"),
    // base group has to be put last
    (4 until 10).map(i => s"${i}")
  )) ++
  new BaseXilinxAlveoU250Config)

class OctaTileMeshNoCTopoQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPNoCConfig(Seq(
    Seq("0", "1", "4", "5"),
    Seq("2", "3", "6", "7"),
    (8 until 16).map(i => s"${i}")
  )) ++
  new BaseXilinxAlveoU250Config)

class Sha3QSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig(Seq(
    Seq("Sha3Accel")
  )) ++
  new BaseXilinxAlveoU250Config)

class BoomQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig(Seq(
    Seq("BoomBackend")
  ))++
  new BaseXilinxAlveoU250Config)

class RocketTileQSFPBase extends Config(
  new WithPartitionBase ++
  new RocketTileQSFPXilinxAlveoConfig)

class RocketTileQSFPPartition0 extends Config(
  new WithPartitionIndex(0) ++
  new RocketTileQSFPXilinxAlveoConfig)

class DualRocketTileQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig(Seq(
    Seq("RocketTile"),
    Seq("RocketTile_1"),
  )) ++
  new BaseXilinxAlveoU250Config)

class DualRocketTileQSFPBase extends Config(
  new WithPartitionBase ++
  new DualRocketTileQSFPXilinxAlveoConfig)

class DualRocketTileQSFP0 extends Config(
  new WithPartitionIndex(0) ++
  new DualRocketTileQSFPXilinxAlveoConfig)

class DualRocketTileQSFP1 extends Config(
  new WithPartitionIndex(1) ++
  new DualRocketTileQSFPXilinxAlveoConfig)
