package firesim.configs

import org.chipsalliance.cde.config.Config
import midas._

class RocketTilePCISF1Config extends Config(
  new WithFireAxePCISConfig("RocketTile") ++
  new BaseF1Config)

class DualRocketTilePCISF1Config extends Config(
  new WithFireAxePCISConfig("RocketTile.0~1") ++
  new BaseF1Config)

class QuadRocketTilePCISF1Config extends Config(
  new WithFireAxePCISConfig("RocketTile.0~3") ++
  new BaseF1Config)

class RocketTileQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig("RocketTile") ++
  new BaseXilinxAlveoU250Config
)

class HyperscaleAccelsQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig("SnappyCompressor+SnappyDecompressor+ProtoAccel+ProtoAccelSerializer") ++
  new BaseXilinxAlveoU250Config)

class MinQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig(
    "RocketTile"
  ) ++
  new BaseXilinxAlveoU250Config)

class QuadTileRingNoCTopoQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPNoCConfig("0~1+2~3+4~9") ++
  new BaseXilinxAlveoU250Config)

class OctaTileMeshNoCTopoQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPNoCConfig("0.1.4.5+2.3.6.7+8~15") ++
  new BaseXilinxAlveoU250Config)

class DualRocketTileQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig("RocketTile.0~1") ++
  new BaseXilinxAlveoU250Config)

class Sha3QSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig("Sha3Accel") ++
  new BaseXilinxAlveoU250Config)

class GemminiQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig("Gemmini") ++
  new BaseXilinxAlveoU250Config)

class BoomQSFPXilinxAlveoConfig extends Config(
  new WithFireAxeQSFPConfig("BoomBackend") ++
  new BaseXilinxAlveoU250Config)
