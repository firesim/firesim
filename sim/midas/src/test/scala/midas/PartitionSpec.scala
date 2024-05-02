
package goldengate.tests

import chisel3._
import chisel3.util._
import chisel3.experimental.{IO, annotate, DataMirror}
import chisel3.stage.{ChiselCircuitAnnotation, ChiselGeneratorAnnotation, ChiselStage, CircuitSerializationAnnotation}

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.TestSuite

import firrtl._
import firrtl.annotations.{DeletedAnnotation, JsonProtocol}
import firrtl.options.TargetDirAnnotation
import firrtl.stage.{RunFirrtlTransformAnnotation, FirrtlCircuitAnnotation}
import firrtl.transforms.DontTouchAnnotation


import midas.stage._
import midas.widgets.{BridgeAnnotation, PeekPokeBridge, RationalClock, RationalClockBridge, ResetPulseBridge, ResetPulseBridgeParameters}
import midas.targetutils.{EnableModelMultiThreadingAnnotation, FirrtlEnableModelMultiThreadingAnnotation, MemModelAnnotation, FirrtlMemModelAnnotation}
import midas.passes.partition.PrintAllPass

import freechips.rocketchip.prci._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.{Config, Field, Parameters}

import java.io.{File, PrintWriter}
import midas.targetutils.RoCCBusyFirrtlAnnotation
import freechips.rocketchip.util.DecoupledHelper
import midas.targetutils.MakeRoCCBusyLatencyInsensitive

//////////////////////////////////////////////////////////////////////////////


// object TestLogger {
// def logInfo(format: String, args: Bits*)(implicit p: Parameters) {
// val loginfo_cycles = RegInit(0.U(64.W))
// loginfo_cycles := loginfo_cycles + 1.U

// printf("cy: %d, ", loginfo_cycles)
// printf(Printable.pack(format, args:_*))
// }
// }

// case object NUMTILES extends Field[Int](5)
// case object BITWIDTH extends Field[Int](8)

// class SkidBuffer[T <: Data](data: T, latencyToEndure: Int) extends Module {
// val io = IO(new Bundle {
// val enq = Flipped(Decoupled(data))
// val deq = Decoupled(data)
// })

// val depth = 2 * latencyToEndure
// val buf = Module(new Queue(data, depth, flow=true))

// io.deq <> buf.io.deq

// buf.io.enq.valid := io.enq.valid
// buf.io.enq.bits <> io.enq.bits
// io.enq.ready := (buf.io.count === 0.U)
// }

// class TLBundle(bitWidth: Int) extends Bundle {
// val a = Decoupled(UInt(bitWidth.W))
// val d = Flipped(Decoupled(UInt(bitWidth.W)))
// }

// class RoCCReq extends Bundle {
// val r1 = UInt(32.W)
// val r2 = UInt(32.W)
// }

// class RoCCResp extends Bundle {
// val rd = UInt(32.W)
// }

// class RoCCCommand() extends Bundle {
// val req = Decoupled(new RoCCReq)
// val resp = Flipped(Decoupled(new RoCCResp))
// val busy = Input(Bool())
// }

// class RoCCIO(bitWidth: Int) extends Bundle {
// val rocc = Flipped(new RoCCCommand)
// val tl = new TLBundle(bitWidth)
// }

// class BaseRoCC(bitWidth: Int)(implicit p: Parameters) extends Module {
// val io = IO(new RoCCIO(bitWidth))
// dontTouch(io)
// MakeRoCCBusyLatencyInsensitive(io.rocc.busy, io.rocc.req.ready, io.rocc.req.valid)
// val busy = io.rocc.busy
// val ready = io.rocc.req.ready

// val req = Module(new Queue(new RoCCReq, 4))
// req.io.enq <> io.rocc.req

// io.rocc.busy := io.rocc.req.valid || (req.io.count =/= 0.U)
// val resp_fire = DecoupledHelper(
// req.io.deq.valid,
// io.rocc.resp.ready,
// io.tl.a.ready)

// req.io.deq.ready := resp_fire.fire(req.io.deq.valid)

// io.rocc.resp.valid := resp_fire.fire(io.rocc.resp.ready)
// io.rocc.resp.bits.rd := req.io.deq.bits.r1 + req.io.deq.bits.r2

// io.tl.a.valid := resp_fire.fire(io.tl.a.ready)
// io.tl.a.bits := req.io.deq.bits.r1
// io.tl.d.ready := true.B
// }

// class Abe(bitWidth: Int)(implicit p: Parameters) extends BaseRoCC(bitWidth)
// class Joonho(bitWidth: Int)(implicit p: Parameters) extends BaseRoCC(bitWidth)
// class Sagar(bitWidth: Int)(implicit p: Parameters) extends BaseRoCC(bitWidth)
// class MilkTea(bitWidth: Int)(implicit p: Parameters) extends BaseRoCC(bitWidth)


// class RocketCore(bitWidth: Int, roccs: Int)(implicit p: Parameters) extends Module {
// val io = IO(new Bundle {
// val rocc_if = Vec(roccs, new RoCCCommand)
// val master = new TLBundle(bitWidth)
// val slave = Flipped(new TLBundle(bitWidth))
// val interrupt = Input(UInt(1.W))
// })
// dontTouch(io)

// val dummy_pc = RegInit(0.U(64.W))
// dummy_pc := dummy_pc + 4.U

// val rf = SyncReadMem(32, UInt(bitWidth.W))
// val frf = SyncReadMem(32, UInt(bitWidth.W))

// val addr = ~dummy_pc(log2Up(bitWidth)-1, 0)
// when (io.interrupt.asBool) {
// io.master.a.bits := rf.read(addr, true.B) & frf.read(addr, true.B)
// } .otherwise {
// rf.write(addr, true.B)
// frf.write(addr, true.B)
// io.master.a.bits := 0.U
// }

// io.master.a.valid := (dummy_pc < 128.U) && io.rocc_if.map(!_.busy).reduce(_ && _)
// io.master.d.ready := true.B

// for (i <- 0 until roccs) {
// io.rocc_if(i).req.valid := dummy_pc >= 128.U
// io.rocc_if(i).req.bits.r1 := dummy_pc
// io.rocc_if(i).req.bits.r2 := dummy_pc
// io.rocc_if(i).resp.ready := true.B
// }

// io.slave.a.ready := true.B
// io.slave.d.valid := false.B
// io.slave.d.bits := 0.U
// }

// class TLArbiter(bitWidth: Int, cnt: Int) extends Module {
// val io = IO(new Bundle {
// val in = Vec(cnt, Flipped(new TLBundle(bitWidth)))
// val out = new TLBundle(bitWidth)
// })
// dontTouch(io)

// val idx = RegInit(0.U(log2Ceil(cnt).W))
// idx := idx + 1.U

// for (i <- 0 until cnt) {
// when (i.U === idx) {
// io.out.a <> io.in(i).a
// io.in(i).d <> io.out.d
// } .otherwise {
// io.in(i).a.ready := false.B
// io.in(i).d.valid := false.B
// io.in(i).d.bits := 0.U

// io.out.a.valid := false.B
// io.out.a.bits := 0.U
// io.out.d.ready := false.B
// }
// }
// }

// class TileIO(bitWidth: Int) extends Bundle {
// val master = new TLBundle(bitWidth)
// val slave = Flipped(new TLBundle(bitWidth))
// val interrupt = Input(UInt(1.W))
// }

// class RocketTile(bitWidth: Int)(implicit p: Parameters) extends Module {
// val io = IO(new TileIO(bitWidth))
// dontTouch(io)

// val coreInstance = Module(new RocketCore(bitWidth, 2))
// val joonhoInstance = Module(new Joonho(bitWidth))
// val abeInstance = Module(new Abe(bitWidth))
// val arbiter = Module(new TLArbiter(bitWidth, 3))

// arbiter.io.in(0) <> joonhoInstance.io.tl
// arbiter.io.in(1) <> abeInstance.io.tl
// arbiter.io.in(2) <> coreInstance.io.master
// io.master <> arbiter.io.out

// abeInstance.io.rocc <> coreInstance.io.rocc_if(0)
// joonhoInstance.io.rocc <> coreInstance.io.rocc_if(1)

// coreInstance.io.interrupt := io.interrupt
// coreInstance.io.slave <> io.slave
// }

// class RocketTile_1(bitWidth: Int)(implicit p: Parameters) extends RocketTile(bitWidth)
// class RocketTile_2(bitWidth: Int)(implicit p: Parameters) extends RocketTile(bitWidth)
// class RocketTile_3(bitWidth: Int)(implicit p: Parameters) extends RocketTile(bitWidth)

// class BoomTile(bitWidth: Int)(implicit p: Parameters) extends Module {
// val io = IO(new TileIO(bitWidth))
// dontTouch(io)

// val coreInstance = Module(new RocketCore(bitWidth, 3))
// val sagarInstance = Module(new Sagar(bitWidth))
// val milkTeaInstance = Module(new MilkTea(bitWidth))
// val joonhoInstance = Module(new Joonho(bitWidth))
// val arbiter = Module(new TLArbiter(bitWidth, 4))

// arbiter.io.in(0) <> sagarInstance.io.tl
// arbiter.io.in(1) <> milkTeaInstance.io.tl
// arbiter.io.in(2) <> joonhoInstance.io.tl
// arbiter.io.in(3) <> coreInstance.io.master
// io.master <> arbiter.io.out

// sagarInstance.io.rocc <> coreInstance.io.rocc_if(0)
// milkTeaInstance.io.rocc <> coreInstance.io.rocc_if(1)
// joonhoInstance.io.rocc <> coreInstance.io.rocc_if(2)


// coreInstance.io.interrupt := io.interrupt
// coreInstance.io.slave <> io.slave
// }


// Stuff to tests
// 1. Partition both the selected rocket & Boom tile when there are multiple of them
// e.g., split rocket-1 & boom-0 when there are 3 rocket & 2 boom tiles
// 2. Partition the Joonho out from rocket-tile & Joonho out from boom-tile
// 3. Partition the Abe out from rocket-tile & MilkTea out from boom-tile
// 3. Partition the Joonho out from rocket-tile & a boom-tile


// class SBus(numTiles: Int, bitWidth: Int) extends Module {
// val io = IO(new Bundle {
// val tiles = Vec(numTiles, Flipped(new TileIO(bitWidth)))

// val otherStuff = Input(UInt(64.W))
// })

// for (i <- 0 until numTiles) {
// io.tiles(i).master.a.ready := true.B
// io.tiles(i).master.d.valid := true.B
// io.tiles(i).master.d.bits := 0.U

// io.tiles(i).slave.a.valid := true.B
// io.tiles(i).slave.a.bits := 0.U
// io.tiles(i).slave.d.ready := true.B

// io.tiles(i).interrupt := false.B
// }
// dontTouch(io)
// }

// class DigitalTop(implicit p: Parameters) extends LazyModule {
// val numTiles = p(NUMTILES)
// val bitWidth = p(BITWIDTH)
// override lazy val module = Module(new DigitalTopImp(numTiles, bitWidth)(this))
// }

// class DigitalTopImp(numTiles: Int, bitWidth: Int)(outer: DigitalTop) extends LazyModuleImp(outer) {
// val io = IO(new Bundle {
// val otherStuff = Input(UInt(64.W))
// })

// assert(numTiles >= 5)

// val rocketTile_0 = Module(new RocketTile(16))
// val rocketTile_1 = Module(new RocketTile_1(16))
// val rocketTile_2 = Module(new RocketTile_2(8))
// val rocketTile_3 = Module(new RocketTile_3(8))
// val boomTile_1 = Module(new BoomTile(bitWidth))

// val sbus = Module(new SBus(numTiles, bitWidth))

// sbus.io.tiles(0) <> rocketTile_0.io
// sbus.io.tiles(1) <> rocketTile_1.io
// sbus.io.tiles(2) <> rocketTile_2.io
// sbus.io.tiles(3) <> rocketTile_3.io
// sbus.io.tiles(4) <> boomTile_1.io

// sbus.io.otherStuff := io.otherStuff
// }

// class ChipTop(implicit p: Parameters) extends LazyModule {
// lazy val lazySystem = LazyModule(new DigitalTop).suggestName("System")
// lazy val module: LazyModuleImpLike  = new LazyRawModuleImp(this) { }
// val implicitClockSourceNode = ClockSourceNode(Seq(ClockSourceParameters(name = Some("top_clock"))))
// val implicitClockSinkNode   = ClockSinkNode(Seq(ClockSinkParameters(name = Some("implicit_clock"))))
// implicitClockSinkNode := implicitClockSourceNode

// val numTiles = p(NUMTILES)
// val bitWidth = p(BITWIDTH)

// val topIO = InModuleBody {
// lazySystem.asInstanceOf[DigitalTop].module match { case l: LazyModuleImp => {
// val implicitClock = implicitClockSinkNode.in.head._1.clock
// val implicitReset = implicitClockSinkNode.in.head._1.reset
// l.clock := implicitClock
// l.reset := implicitReset
// val tio = IO(new Bundle {
// val otherStuff = Input(UInt(64.W))
// })
// l.io.otherStuff := tio.otherStuff
// tio
// }}
// }

// val clockIO = InModuleBody {
// implicitClockSourceNode.makeIOs()
// }
// }

// class FireSim(implicit p: Parameters) extends RawModule {
// freechips.rocketchip.util.property.cover.setPropLib(new midas.passes.FireSimPropertyLibrary())

// val numTiles = p(NUMTILES)

// val buildtopClock = Wire(Clock())
// val buildtopReset = WireInit(false.B)

// val dummy = WireInit(false.B)
// val peekPokeBridge = PeekPokeBridge(buildtopClock, dummy)

// val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
// resetBridge.io.clock := buildtopClock
// buildtopReset := resetBridge.io.reset

// midas.targetutils.GlobalResetCondition(buildtopReset)

// val lazyChipTop = LazyModule(new ChipTop())
// val chiptop = Module(lazyChipTop.module)
// lazyChipTop match {
// case dut: ChipTop =>
// dut.clockIO.head.clock := buildtopClock
// dut.clockIO.head.reset := buildtopReset
// dut.topIO.otherStuff := 0.U

// annotate(EnableModelMultiThreadingAnnotation(dut.lazySystem.module.rocketTile_0))
// annotate(EnableModelMultiThreadingAnnotation(dut.lazySystem.module.rocketTile_1))
// annotate(MemModelAnnotation(dut.lazySystem.module.rocketTile_0.coreInstance.rf))
// annotate(MemModelAnnotation(dut.lazySystem.module.rocketTile_1.coreInstance.rf))
// annotate(MemModelAnnotation(dut.lazySystem.module.rocketTile_0.coreInstance.frf))
// annotate(MemModelAnnotation(dut.lazySystem.module.rocketTile_1.coreInstance.frf))
// }

// def dutReset = { require(false, "dutReset should not be used in FireSim"); false.B }
// def success  = { require(false, "success should not be used in FireSim"); false.B }

// val allClocks = Seq(RationalClock("baseClock", 1, 1))
// val clockBridge = Module(new RationalClockBridge(allClocks))

// buildtopClock := clockBridge.io.clocks(0)
// }

// class WithFAME5 extends Config((site, here, up) => {
// case midas.EnableModelMultiThreading => true
// })

// class FAME5Config extends WithFAME5

// class WithNumTiles(n: Int) extends Config((site, here, up) => {
// case NUMTILES => n
// })

// class WithBitWidth(n: Int) extends Config((site, here, up) => {
// case BITWIDTH => n
// })

// class HeteroTopologyPartitionTestConfig extends Config(
// new WithBitWidth(64)
// )


////////////////////////////////////////////////////////////////////////////


// object Logger {
// def logInfo(format: String, args: Bits*)(implicit p: Parameters) {
// val loginfo_cycles = RegInit(0.U(64.W))
// loginfo_cycles := loginfo_cycles + 1.U

// printf("cy: %d, ", loginfo_cycles)
// printf(Printable.pack(format, args:_*))
// }
// }




trait GoldenGateCompilerTest { this: TestSuite =>
  protected def annos: AnnotationSeq = Seq()

  protected def compile[M <: RawModule](gen: => M, target: String, a: AnnotationSeq = List(), ll: String = "warn"): (String, AnnotationSeq) = {
    val stage = new ChiselStage

    val testName = this.suiteName
    val testRunDir = TargetDirAnnotation("test_run_dir" + File.separator + testName)

    val r = stage.execute(Array("-X", target, "-ll", ll), ChiselGeneratorAnnotation(() => gen) +: testRunDir +: a ++: annos)
    val src = r.collect {
      case EmittedFirrtlCircuitAnnotation(a) => a
      case EmittedFirrtlModuleAnnotation(a) => a
      case EmittedVerilogCircuitAnnotation(a) => a
      case EmittedVerilogModuleAnnotation(a) => a
    }.map(_.value).mkString("")
    (src, r)
  }
}

class FireSimFirrtlAndAnnotationGenerator extends AnyFreeSpec with GoldenGateCompilerTest {
  def generateFireSimFirrtlAndAnnotations(cfg: Config) = {
    val (firrtl, annos) = compile(new FireSim()(cfg), "low", a=Seq(RunFirrtlTransformAnnotation(new PrintAllPass)))

    val firrtlWriter = new PrintWriter(new File("midas/generated-src/firesim.fir"))
    firrtlWriter.write(firrtl)
    firrtlWriter.close()

    val annosWithoutFAME5ANnos = annos.filter(a => a match {
      case f: FirrtlEnableModelMultiThreadingAnnotation => false
      case f: FirrtlMemModelAnnotation => false
      case _ => true
    })

    val FAME5PathAnnos = annos.filter(a => a match {
      case f: FirrtlEnableModelMultiThreadingAnnotation => true
      case _ => false
    })

    val FAME5Annos = if (FAME5PathAnnos.size == 1) Seq() else FAME5PathAnnos.map{ a =>
      val fa = a.asInstanceOf[FirrtlEnableModelMultiThreadingAnnotation].targets.head
      val newfa = fa.copy(module=fa.path.last._2.value, path=Seq())
      FirrtlEnableModelMultiThreadingAnnotation(newfa)
    }

    val FAME5MemPathAnnos = annos.filter(a => a match {
      case f: FirrtlMemModelAnnotation => true
      case _ => false
    })

    val FAME5MemAnnos = if (FAME5MemPathAnnos.size == 1) Seq() else FAME5MemPathAnnos.map { a =>
      val fa = a.asInstanceOf[FirrtlMemModelAnnotation].target
      val newfa = fa.copy(module=fa.path.last._2.value, path=Seq())
      FirrtlMemModelAnnotation(newfa)
    }

    val newAnnos = annosWithoutFAME5ANnos ++ FAME5Annos ++ FAME5MemAnnos

    val annoWriter = new PrintWriter(new File("midas/generated-src/firesim.anno.json"))
    annoWriter.write(JsonProtocol.serialize(newAnnos.filter(_ match {
      case _: DeletedAnnotation => false
      case _: EmittedComponent => false
      case _: EmittedAnnotation[_] => false
      case _: FirrtlCircuitAnnotation => false
      case _: ChiselCircuitAnnotation => false
      case _: CircuitSerializationAnnotation => false
      case _: BridgeAnnotation => true
      case _: DontTouchAnnotation => true
      case _: FirrtlEnableModelMultiThreadingAnnotation => true
      case _: FirrtlMemModelAnnotation => true
      case x: RoCCBusyFirrtlAnnotation => // TODO : Add serializer to use this...?
        false
      case _ => false
    })))
    annoWriter.close()
  }
}

// class PartitionExtractModuleSpec extends FireSimFirrtlAndAnnotationGenerator {
// generateFireSimFirrtlAndAnnotations(new HeteroTopologyPartitionTestConfig)

// "PartitionExtractModule" in {
// GoldenGateMain.main(
// Array(
// "-i", // FIRRTL_FILE
// "midas/generated-src/firesim.fir",
// "-td",
// "midas/generated-src",
// "-ggcp",
// "firesim.midasexamples",
// "-faf",
// "midas/generated-src/firesim.anno.json",
// "-ggcs",
// "F1Config",
// "-ofb",
// "FireSim-generated",
// "-EMP",
// "RocketTile.0.2",
// "-FPGACNT",
// "2",
// "-EMGIDX",
// "0",
// "--no-dedup"
// )
// )
// }
// }

// class PartitionRemoveModuleSpec extends FireSimFirrtlAndAnnotationGenerator {
// generateFireSimFirrtlAndAnnotations(new HeteroTopologyPartitionTestConfig)

// "PartitionRemoveModule" in {
// GoldenGateMain.main(
// Array(
// "-i", // FIRRTL_FILE
// "midas/generated-src/firesim.fir",
// "-td",
// "midas/generated-src",
// "-ggcp",
// "firesim.midasexamples",
// "-faf",
// "midas/generated-src/firesim.anno.json",
// "-ggcs",
// "F1Config",
// "-ofb",
// "FireSim-generated",
// "-RMP",
// "RocketTile.0.2",
// "-FPGACNT",
// "3",
// "--no-dedup"
// )
// )
// }
// }

// class PartitionMultiThreadExtractModuleSpec extends FireSimFirrtlAndAnnotationGenerator {
// generateFireSimFirrtlAndAnnotations(new HeteroTopologyPartitionTestConfig)

// "PartitionMultiThreadExtractModule" in {
// GoldenGateMain.main(
// Array(
// "-i", // FIRRTL_FILE
// "midas/generated-src/firesim.fir",
// "-td",
// "midas/generated-src",
// "-ggcp",
// "firesim.midasexamples",
// "-faf",
// "midas/generated-src/firesim.anno.json",
// "-ggcs",
// "MTModels_MCRams_F1Config",
// "-ofb",
// "FireSim-generated",
// "-EMP",
// "RocketTile.0.2",
// "-FPGACNT",
// "2",
// "-EMGIDX",
// "0",
// "--no-dedup",
// "--allow-unrecognized-annotations"
// )
// )
// }
// }

// class PartitionMultiThreadRemoveModuleSpec extends FireSimFirrtlAndAnnotationGenerator {
// generateFireSimFirrtlAndAnnotations(new HeteroTopologyPartitionTestConfig)

// "PartitionMultiThreadRemoveModule" in {
// GoldenGateMain.main(
// Array(
// "-i", // FIRRTL_FILE
// "midas/generated-src/firesim.fir",
// "-td",
// "midas/generated-src",
// "-ggcp",
// "firesim.midasexamples",
// "-faf",
// "midas/generated-src/firesim.anno.json",
// "-ggcs",
// "F1Config", // has to be f1config (non-deleted annotations results in GG hang)
// "-ofb",
// "FireSim-generated",
// "-RMP",
// "RocketTile.0.2",
// "-FPGACNT",
// "2",
// "--no-dedup",
// "--allow-unrecognized-annotations"
// )
// )
// }
// }

// class FAME5Spec extends FireSimFirrtlAndAnnotationGenerator {
// generateFireSimFirrtlAndAnnotations(new HeteroTopologyPartitionTestConfig)

// "FAME5" in {
// GoldenGateMain.main(
// Array(
// "-i", // FIRRTL_FILE
// "midas/generated-src/firesim.fir",
// "-td",
// "midas/generated-src",
// "-ggcp",
// "firesim.midasexamples",
// "-faf",
// "midas/generated-src/firesim.anno.json",
// "-ggcs",
// "MCRams_MTModels_F1Config",
// "-ofb",
// "FireSim-generated",
// "--no-dedup",
// "--allow-unrecognized-annotations"
// )
// )
// }
// }
