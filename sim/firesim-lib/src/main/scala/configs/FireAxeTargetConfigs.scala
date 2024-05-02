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

// order assigned to FPGAs
// [A, B, C, D, E] and you have 2 FPGAs, then modules will be split like so [A, B, C] & [D, E]

// SoC0.rest
// SoC1.rest
// SoC0.AES256ECBAccel
// SoC0.MemcpyAccel
// SoC1.AES256ECBAccel_1
// SoC1.MemcpyAccel_1
// SoC0.ProtoAccelSerializer
// SoC1.ProtoAccelSerializer_1
// SoC1.ProtoAccel_1
// SoC0.ProtoAccel

// SoC0.SnappyCompressor
// SoC0.SnappyDecompressor
// SoC1.ZstdCompressor
// SoC1.ZstdDecompressor

// class AppSoCConfig extends Config(
//   new compressacc.WithSnappyDecompressor ++
//   new compressacc.WithSnappyCompressor ++
//   new protoacc.WithProtoAccelSerOnly ++
//   new protoacc.WithProtoAccelDeserOnly ++
//   new memcpyacc.WithMemcpyAccel ++ // base
//   new aes.WithAES256ECBAccel ++ // base
//   new FastBuildAppSoCConfig) // base
//
// class SmartNICSoCConfig extends Config(
//   new compressacc.WithZstdDecompressor32 ++
//   new compressacc.WithZstdCompressor ++
//   new protoacc.WithProtoAccelSerOnly ++
//   new protoacc.WithProtoAccelDeserOnly ++
//   new memcpyacc.WithMemcpyAccel ++ // base
//   new aes.WithAES256ECBAccel ++ // base
//   new FastBuildSmartNICSoCConfig) // base

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
