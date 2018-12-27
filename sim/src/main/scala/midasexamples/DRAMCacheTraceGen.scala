package firesim.midasexamples

import chisel3._
import chisel3.util.Queue
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.groundtest.{GroundTestSubsystem, GroundTestSubsystemModuleImp}
import memblade.cache.{HasPeripheryDRAMCache, HasPeripheryDRAMCacheModuleImp}
import memblade.manager.TestMemBlade
import icenet.RateLimiterSettings

class DRAMCacheGroundTestTop(implicit p: Parameters) extends GroundTestSubsystem
    with HasPeripheryDRAMCache {
  override lazy val module = new DRAMCacheGroundTestTopModuleImp(this)
}

class DRAMCacheGroundTestTopModuleImp(outer: DRAMCacheGroundTestTop)
    extends GroundTestSubsystemModuleImp(outer)
    with HasPeripheryDRAMCacheModuleImp

class DRAMCacheTraceGen(implicit val p: Parameters) extends Module {
  val groundtest = Module(LazyModule(new DRAMCacheGroundTestTop).module)
  val memblade = Module(LazyModule(new TestMemBlade).module)

  val io = IO(new Bundle {
    val mem_axi4 = groundtest.mem_axi4.cloneType
    val cache_axi4 = groundtest.cache_axi4.cloneType
    val mb_mem_axi4 = memblade.mem_axi4.cloneType
    val success = Output(Bool())
  })

  io.mem_axi4 <> groundtest.mem_axi4
  io.cache_axi4 <> groundtest.cache_axi4
  io.mb_mem_axi4 <> memblade.mem_axi4
  io.success := groundtest.success

  val rlimit = Wire(new RateLimiterSettings)
  rlimit.inc := 1.U
  rlimit.period := 0.U
  rlimit.size := 8.U

  val latency = 736 * 2
  val qPackets = 64
  memblade.connectNet(groundtest.net, latency, qPackets)
  memblade.io.macAddr := (0x3 << 32).U(48.W)
  memblade.io.rlimit := rlimit
  groundtest.net.macAddr := (0x2 << 32).U(48.W)
  groundtest.net.rlimit := rlimit
}
