// See LICENSE for license details.

package goldengate.tests.core

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

import midas.F2Config
import midas.core._

class FPGAManagedH2CSpec extends AnyFlatSpec with ChiselScalatestTester {
  class OneStreamTop(val withC2H: Boolean, val h2cCount: Int = 1, val h2cDepth: Int = 128)(implicit p: Parameters)
      extends LazyModule {
    val cpuParams  = p(CPUManagedAXI4Key).get
    val fpgaParams = p(FPGAManagedAXI4Key).get
    val sources    = if (withC2H) Seq(StreamSourceParameters("c2h", 0, 128, 64)) else Seq.empty
    val sinks      = (0 until h2cCount).map(i => StreamSinkParameters(s"h2c$i", i, h2cDepth, 64))
    val engine     = LazyModule(new FPGAManagedStreamEngine(p, StreamEngineParameters(sources, sinks)))

    val pcisNode = AXI4MasterNode(
      Seq(AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = "host", id = IdRange(0, 1 << cpuParams.idBits)))))
    )
    engine.h2cPCISNodeOpt.get := AXI4Buffer() := pcisNode

    val pcimNode = AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(0, fpgaParams.size - 1)),
          resources     = (new MemoryDevice).reg,
          regionType    = RegionType.UNCACHED,
          executable    = false,
          supportsWrite = fpgaParams.writeTransferSizes,
          supportsRead  = fpgaParams.readTransferSizes,
          interleavedId = fpgaParams.interleavedId,
        )),
        beatBytes = fpgaParams.dataBits / 8,
      ))
    )
    pcimNode := AXI4IdIndexer(fpgaParams.idBits) := engine.fpgaManagedAXI4NodeOpt.get

    lazy val module = new OneStreamTopImp(this)
  }

  class OneStreamTopImp(outer: OneStreamTop) extends LazyModuleImp(outer) {
    val pcis = IO(Flipped(AXI4Bundle(outer.cpuParams.axi4BundleParams)))
    outer.pcisNode.out.head._1 <> pcis
    val pcim = IO(AXI4Bundle(outer.fpgaParams.axi4BundleParams))
    pcim <> outer.pcimNode.in.head._1
    val streams = IO(Vec(outer.h2cCount, chisel3.util.Decoupled(UInt(512.W))))
    streams <> outer.engine.streamsFromHostCPU
    val c2h = if (outer.withC2H) Some(IO(Flipped(chisel3.util.Decoupled(UInt(512.W))))) else None
    outer.engine.module.io.ctrl := DontCare
    c2h.foreach(port => outer.engine.streamsToHostCPU.head <> port)
  }

  private def write32(dut: OneStreamTopImp, addr: Int, word: Long, expectedResponse: Int = 0): Unit = {
    dut.pcis.aw.bits.id.poke(0.U)
    dut.pcis.aw.bits.addr.poke(addr.U)
    dut.pcis.aw.bits.len.poke(0.U)
    dut.pcis.aw.bits.size.poke(2.U)
    dut.pcis.aw.valid.poke(true.B)
    while (!dut.pcis.aw.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.pcis.aw.valid.poke(false.B)
    dut.pcis.w.bits.data.poke((BigInt(word) << (addr * 8)).U)
    dut.pcis.w.bits.strb.poke((BigInt(15) << addr).U)
    dut.pcis.w.bits.last.poke(true.B)
    dut.pcis.w.valid.poke(true.B)
    while (!dut.pcis.w.ready.peek().litToBoolean) dut.clock.step()
    dut.clock.step()
    dut.pcis.w.valid.poke(false.B)
    while (!dut.pcis.b.valid.peek().litToBoolean) dut.clock.step()
    dut.pcis.b.bits.resp.expect(expectedResponse.U)
    dut.clock.step()
  }

  "FPGAManagedStreamEngine" should "elaborate one H2C stream with and without C2H" in {
    implicit val p: Parameters = (new F2Config).toInstance
    ChiselStage.emitChirrtl(LazyModule(new OneStreamTop(false)).module)
    ChiselStage.emitChirrtl(LazyModule(new OneStreamTop(true)).module)
    ChiselStage.emitChirrtl(LazyModule(new OneStreamTop(true, 2)).module)
  }

  it should "move one PCIS-described H2C transfer through PCIM into the bridge stream" in {
    implicit val p: Parameters = (new F2Config).toInstance
    test(LazyModule(new OneStreamTop(false)).module).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.pcis.aw.valid.poke(false.B)
      dut.pcis.w.valid.poke(false.B)
      dut.pcis.b.ready.poke(true.B)
      dut.pcis.ar.valid.poke(false.B)
      dut.pcis.r.ready.poke(true.B)
      dut.pcim.ar.ready.poke(true.B)
      dut.pcim.aw.ready.poke(true.B)
      dut.pcim.w.ready.poke(true.B)
      dut.pcim.b.valid.poke(false.B)
      dut.pcim.r.valid.poke(false.B)
      dut.streams(0).ready.poke(false.B)
      dut.clock.step()

      write32(dut, 0, 0x3fc0)
      write32(dut, 4, 0)
      write32(dut, 8, 192)
      write32(dut, 12, 0)

      // The first beat ends at 4 KiB, so the engine must issue two reads.
      for ((addr, beats, firstBeat) <- Seq((0x3fc0, 1, 0), (0x4000, 2, 1))) {
        while (!dut.pcim.ar.valid.peek().litToBoolean) dut.clock.step()
        dut.pcim.ar.bits.addr.expect(addr.U)
        dut.pcim.ar.bits.len.expect((beats - 1).U)
        dut.clock.step()
        for (i <- 0 until beats) {
          dut.pcim.r.bits.id.poke(0.U)
          dut.pcim.r.bits.data.poke((BigInt(firstBeat + i + 1) << 448).U)
          dut.pcim.r.bits.resp.poke(0.U)
          dut.pcim.r.bits.last.poke((i == beats - 1).B)
          dut.pcim.r.valid.poke(true.B)
          while (!dut.pcim.r.ready.peek().litToBoolean) dut.clock.step()
          dut.clock.step()
        }
        dut.pcim.r.valid.poke(false.B)
      }
      for (beat <- 0 until 3) {
        while (!dut.streams(0).valid.peek().litToBoolean) dut.clock.step()
        dut.streams(0).bits.expect((BigInt(beat + 1) << 448).U)
        dut.streams(0).ready.poke(true.B)
        dut.clock.step()
        dut.streams(0).ready.poke(false.B)
      }
    }
  }

  it should "demultiplex queued H2C descriptors and out-of-order PCIM responses" in {
    implicit val p: Parameters = (new F2Config).toInstance
    test(LazyModule(new OneStreamTop(false, 2)).module).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.pcis.aw.valid.poke(false.B)
      dut.pcis.w.valid.poke(false.B)
      dut.pcis.b.ready.poke(true.B)
      dut.pcis.ar.valid.poke(false.B)
      dut.pcis.r.ready.poke(true.B)
      dut.pcim.ar.ready.poke(false.B)
      dut.pcim.aw.ready.poke(true.B)
      dut.pcim.w.ready.poke(true.B)
      dut.pcim.b.valid.poke(false.B)
      dut.pcim.r.valid.poke(false.B)
      dut.streams.foreach(_.ready.poke(false.B))
      dut.clock.step()

      def descriptor(addr: Long, streamId: Int): Unit = {
        write32(dut, 0, addr)
        write32(dut, 4, 0)
        write32(dut, 8, 64)
        write32(dut, 12, streamId)
      }
      descriptor(0x10000, 0)
      descriptor(0x20000, 1)
      descriptor(0x10040, 0) // Queue behind stream 0's active transfer.
      write32(dut, 12, 2, expectedResponse = 2) // No stream 2: SLVERR.

      def receiveAddress(): (Long, BigInt) = {
        dut.pcim.ar.ready.poke(true.B)
        while (!dut.pcim.ar.valid.peek().litToBoolean) dut.clock.step()
        dut.pcim.ar.bits.len.expect(0.U)
        val result = (dut.pcim.ar.bits.addr.peek().litValue.toLong, dut.pcim.ar.bits.id.peek().litValue)
        dut.clock.step()
        dut.pcim.ar.ready.poke(false.B)
        result
      }
      val first = receiveAddress()
      val second = receiveAddress()
      val ids = Map(first, second)
      assert(ids.keySet == Set(0x10000L, 0x20000L))
      assert(ids(0x10000L) != ids(0x20000L))

      def returnBeat(id: BigInt, value: Int): Unit = {
        dut.pcim.r.bits.id.poke(id.U)
        dut.pcim.r.bits.data.poke((BigInt(value) << 448).U)
        dut.pcim.r.bits.resp.poke(0.U)
        dut.pcim.r.bits.last.poke(true.B)
        dut.pcim.r.valid.poke(true.B)
        while (!dut.pcim.r.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.pcim.r.valid.poke(false.B)
      }
      returnBeat(ids(0x20000L), 0x22)
      while (!dut.streams(1).valid.peek().litToBoolean) dut.clock.step()
      dut.streams(1).bits.expect((BigInt(0x22) << 448).U)
      dut.streams(0).valid.expect(false.B)
      dut.streams(1).ready.poke(true.B)
      dut.clock.step()
      dut.streams(1).ready.poke(false.B)

      returnBeat(ids(0x10000L), 0x11)
      val third = receiveAddress()
      assert(third._1 == 0x10040L)
      returnBeat(third._2, 0x33)
      for (value <- Seq(0x11, 0x33)) {
        while (!dut.streams(0).valid.peek().litToBoolean) dut.clock.step()
        dut.streams(0).bits.expect((BigInt(value) << 448).U)
        dut.streams(0).ready.poke(true.B)
        dut.clock.step()
        dut.streams(0).ready.poke(false.B)
      }
    }
  }

  it should "stop PCIM reads when an H2C FIFO is full and resume after consumption" in {
    implicit val p: Parameters = (new F2Config).toInstance
    test(LazyModule(new OneStreamTop(false, 1, 2)).module).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.pcis.aw.valid.poke(false.B)
      dut.pcis.w.valid.poke(false.B)
      dut.pcis.b.ready.poke(true.B)
      dut.pcis.ar.valid.poke(false.B)
      dut.pcis.r.ready.poke(true.B)
      dut.pcim.ar.ready.poke(false.B)
      dut.pcim.aw.ready.poke(true.B)
      dut.pcim.w.ready.poke(true.B)
      dut.pcim.b.valid.poke(false.B)
      dut.pcim.r.valid.poke(false.B)
      dut.streams(0).ready.poke(false.B)
      dut.clock.step()
      write32(dut, 0, 0x3000)
      write32(dut, 4, 0)
      write32(dut, 8, 192)
      write32(dut, 12, 0)

      def acceptRead(expectedAddress: Int, expectedBeats: Int): BigInt = {
        dut.pcim.ar.ready.poke(true.B)
        while (!dut.pcim.ar.valid.peek().litToBoolean) dut.clock.step()
        dut.pcim.ar.bits.addr.expect(expectedAddress.U)
        dut.pcim.ar.bits.len.expect((expectedBeats - 1).U)
        val id = dut.pcim.ar.bits.id.peek().litValue
        dut.clock.step()
        dut.pcim.ar.ready.poke(false.B)
        id
      }
      def returnBeat(id: BigInt, value: Int, last: Boolean): Unit = {
        dut.pcim.r.bits.id.poke(id.U)
        dut.pcim.r.bits.data.poke((BigInt(value) << 448).U)
        dut.pcim.r.bits.resp.poke(0.U)
        dut.pcim.r.bits.last.poke(last.B)
        dut.pcim.r.valid.poke(true.B)
        while (!dut.pcim.r.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
        dut.pcim.r.valid.poke(false.B)
      }
      val firstID = acceptRead(0x3000, 2)
      returnBeat(firstID, 1, false)
      returnBeat(firstID, 2, true)
      for (_ <- 0 until 8) {
        dut.pcim.ar.valid.expect(false.B)
        dut.clock.step()
      }
      dut.streams(0).bits.expect((BigInt(1) << 448).U)
      dut.streams(0).ready.poke(true.B)
      dut.clock.step()
      dut.streams(0).ready.poke(false.B)
      val secondID = acceptRead(0x3080, 1)
      returnBeat(secondID, 3, true)
      for (value <- Seq(2, 3)) {
        while (!dut.streams(0).valid.peek().litToBoolean) dut.clock.step()
        dut.streams(0).bits.expect((BigInt(value) << 448).U)
        dut.streams(0).ready.poke(true.B)
        dut.clock.step()
        dut.streams(0).ready.poke(false.B)
      }
    }
  }

  it should "read H2C data while a C2H page write uses the same PCIM port" in {
    implicit val p: Parameters = (new F2Config).toInstance
    test(LazyModule(new OneStreamTop(true, 2)).module).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.pcis.aw.valid.poke(false.B)
      dut.pcis.w.valid.poke(false.B)
      dut.pcis.b.ready.poke(true.B)
      dut.pcis.ar.valid.poke(false.B)
      dut.pcis.r.ready.poke(true.B)
      dut.pcim.ar.ready.poke(false.B)
      dut.pcim.aw.ready.poke(false.B)
      dut.pcim.w.ready.poke(false.B)
      dut.pcim.b.valid.poke(false.B)
      dut.pcim.r.valid.poke(false.B)
      dut.streams.foreach(_.ready.poke(false.B))
      dut.c2h.get.valid.poke(false.B)
      dut.clock.step()

      for (beat <- 0 until 64) {
        dut.c2h.get.bits.poke((BigInt(beat + 1) << 448).U)
        dut.c2h.get.valid.poke(true.B)
        while (!dut.c2h.get.ready.peek().litToBoolean) dut.clock.step()
        dut.clock.step()
      }
      dut.c2h.get.valid.poke(false.B)
      while (!dut.pcim.aw.valid.peek().litToBoolean) dut.clock.step()
      dut.pcim.aw.bits.len.expect(63.U)
      val writeID = dut.pcim.aw.bits.id.peek().litValue

      write32(dut, 0, 0x8000)
      write32(dut, 4, 0)
      write32(dut, 8, 64)
      write32(dut, 12, 1)
      dut.pcim.ar.ready.poke(true.B)
      while (!dut.pcim.ar.valid.peek().litToBoolean) dut.clock.step()
      dut.pcim.ar.bits.addr.expect(0x8000.U)
      val readID = dut.pcim.ar.bits.id.peek().litValue
      dut.clock.step()
      dut.pcim.ar.ready.poke(false.B)
      dut.pcim.r.bits.id.poke(readID.U)
      dut.pcim.r.bits.data.poke((BigInt(0x55) << 448).U)
      dut.pcim.r.bits.resp.poke(0.U)
      dut.pcim.r.bits.last.poke(true.B)
      dut.pcim.r.valid.poke(true.B)
      while (!dut.pcim.r.ready.peek().litToBoolean) dut.clock.step()
      dut.clock.step()
      dut.pcim.r.valid.poke(false.B)
      while (!dut.streams(1).valid.peek().litToBoolean) dut.clock.step()
      dut.streams(1).bits.expect((BigInt(0x55) << 448).U)
      dut.streams(0).valid.expect(false.B)
      dut.streams(1).ready.poke(true.B)
      dut.clock.step()

      dut.pcim.aw.ready.poke(true.B)
      dut.clock.step()
      dut.pcim.aw.ready.poke(false.B)
      dut.pcim.w.ready.poke(true.B)
      for (beat <- 0 until 64) {
        while (!dut.pcim.w.valid.peek().litToBoolean) dut.clock.step()
        dut.pcim.w.bits.data.expect((BigInt(beat + 1) << 448).U)
        dut.pcim.w.bits.last.expect((beat == 63).B)
        dut.clock.step()
      }
      dut.pcim.w.ready.poke(false.B)
      dut.pcim.b.bits.id.poke(writeID.U)
      dut.pcim.b.bits.resp.poke(0.U)
      dut.pcim.b.valid.poke(true.B)
      dut.clock.step()
      dut.pcim.b.valid.poke(false.B)
    }
  }
}
