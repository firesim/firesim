package firesim.configs

import org.chipsalliance.cde.config.Config
import midas._

// DOC include start: F1 Rocket Partition
//////////////////////////////////////////////////////////////////////////////
// - F1 partition a RocketTile out
// - Connect FPGAs using PCIe peer to peer communication scheme
//
// FPGA 0 (RocketTile) ----------- FPGA 1 (SoC subsystem)
//////////////////////////////////////////////////////////////////////////////
class RocketTileF1Config
    extends Config(
      new WithPCIM ++ // Use PCIM (PCIe peer to peer) communication scheme
        // WithPartitionGlobalInfo takes in a Seq of Seq
        // The inner Seq specifies the list of modules that should be grouped together in a single partition.
        // The outer Seq specifies the list of partition groups.
        // Each partition group is mapped onto a separate FPGA.
        new WithPartitionGlobalInfo(
          Seq(
            Seq("RocketTile")
          )
        ) ++
        new BaseF1Config
    )

class RocketTileF1PCIMBase
    extends Config(
      new WithPartitionBase ++ // Base partition (SoC subsystem)
        new RocketTileF1Config
    )

class RocketTileF1PCIMPartition0
    extends Config(
      new WithPartitionIndex(0) ++ // Partition 0 containing the partitioned RocketTile
        new RocketTileF1Config
    )
// DOC include end: F1 Rocket Partition

//////////////////////////////////////////////////////////////////////////////
// - Xilinx U250 partition a RocketTile out
// - Connect FPGAs using QSFP cables
//
// FPGA 0 (RocketTile) ----------- FPGA 1 (SoC subsystem)
//////////////////////////////////////////////////////////////////////////////
class RocketTileQSFPXilinxAlveoConfig
    extends Config(
      new WithQSFP ++                 // Use QSFP FPGA to FPGA connections. This will instantiate Aurora IPs in the FPGA shell.
        new WithPartitionGlobalInfo(Seq(Seq("RocketTile"))) ++
        new BaseXilinxAlveoU250Config // Xilinx U250 config
    )

class RocketTileQSFPBase
    extends Config(
      new WithPartitionBase ++
        new RocketTileQSFPXilinxAlveoConfig
    )

class RocketTileQSFPPartition0
    extends Config(
      new WithPartitionIndex(0) ++
        new RocketTileQSFPXilinxAlveoConfig
    )

//////////////////////////////////////////////////////////////////////////////
// - Xilinx U250 partition RocketTiles onto separate FPGAs
// - Connect FPGAs using QSFP cables
//
// FPGA 0 (RocketTile)     FPGA 1 (RocketTile_1)
//             \            /
//              \          /
//           FPGA 2 (base SoC)
//////////////////////////////////////////////////////////////////////////////
class DualRocketTileQSFPXilinxAlveoConfig
    extends Config(
      new WithQSFP ++
        new WithPartitionGlobalInfo(
          Seq(
            Seq("RocketTile"),
            Seq("RocketTile_1"),
          )
        ) ++
        new BaseXilinxAlveoU250Config
    )

class DualRocketTileQSFPBase
    extends Config(
      new WithPartitionBase ++
        new DualRocketTileQSFPXilinxAlveoConfig
    )

class DualRocketTileQSFP0
    extends Config(
      new WithPartitionIndex(0) ++
        new DualRocketTileQSFPXilinxAlveoConfig
    )

class DualRocketTileQSFP1
    extends Config(
      new WithPartitionIndex(1) ++
        new DualRocketTileQSFPXilinxAlveoConfig
    )

// DOC include start: Xilinx U250 Ring NoC Partition
//////////////////////////////////////////////////////////////////////////////
// - Xilinx U250 partition a ring NoC onto 3 FPGA connected as a ring
//
//        FPGA 0     ------------------ FPGA 1
// - router 0 & tile 0               - router 2 & tile 2
// - router 1 & tile 1               - router 3 & tile 3
//                \                     /
//                 \                   /
//                  \                 /
//                   \               /
//                         FPGA 2
//                  - router nodes 4 ~ 10
//                  - SoC subsystem
//////////////////////////////////////////////////////////////////////////////
class QuadTileRingNoCTopoQSFPXilinxAlveoConfig
    extends Config(
      new WithQSFP ++             // FireSim will instantiate Aurora IPs
        new WithFireAxeNoCPart ++ // Compiler will detect NoC router nodes to partition
        new WithPartitionGlobalInfo(
          Seq(
            Seq("0", "1"),
            Seq("2", "3"),
            // base group has to be put last
            (4 until 10).map(i => s"${i}"),
          )
        ) ++
        new BaseXilinxAlveoU250Config
    )

class QuadTileRingNoCU250Base
    extends Config(
      new WithPartitionBase ++
        new QuadTileRingNoCTopoQSFPXilinxAlveoConfig
    )

class QuadTileRingNoCU250Partition0
    extends Config(
      new WithPartitionIndex(0) ++
        new QuadTileRingNoCTopoQSFPXilinxAlveoConfig
    )

class QuadTileRingNoCU250Partition1
    extends Config(
      new WithPartitionIndex(1) ++
        new QuadTileRingNoCTopoQSFPXilinxAlveoConfig
    )
// DOC include end: Xilinx U250 Ring NoC Partition

//////////////////////////////////////////////////////////////////////////////
// - F1 partition a ring NoC onto 3 FPGA connected as a ring
//////////////////////////////////////////////////////////////////////////////
class QuadTileRingNoCTopoF1Config
    extends Config(
      new WithPCIM ++
        new WithFireAxeNoCPart ++
        new WithPartitionGlobalInfo(
          Seq(
            Seq("0", "1"),
            Seq("2", "3"),
            // base group has to be put last
            (4 until 10).map(i => s"${i}"),
          )
        ) ++
        new BaseF1Config
    )

class QuadTileRingNoCF1Base
    extends Config(
      new WithPartitionBase ++
        new QuadTileRingNoCTopoF1Config
    )

class QuadTileRingNoCF1Partition0
    extends Config(
      new WithPartitionIndex(0) ++
        new QuadTileRingNoCTopoF1Config
    )

class QuadTileRingNoCF1Partition1
    extends Config(
      new WithPartitionIndex(1) ++
        new QuadTileRingNoCTopoF1Config
    )
