package midas
package widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

trait AutoCounterConsts {
  val counterWidth = 64
}

class AutoCounterBundle(val numCounters: Int) extends Bundle with AutoCounterConsts {
  val counters = Input(Vec(numCounters, UInt(counterWidth.W)))
}

case class AutoCounterBridgeConstArgs(numcounters: Int, autoCounterPortsMap: scala.collection.mutable.Map[Int, String], hastracerwidget: Boolean = false)

class AutoCounterToHostToken(val numCounters: Int) extends Bundle {
  val data_out = Vec(numCounters, UInt(64.W))
  val cycle = UInt(64.W)
}

class AutoCounterBridgeModule(constructorArg: AutoCounterBridgeConstArgs)(implicit p: Parameters) extends BridgeModule[HostPortIO[AutoCounterBundle]]()(p) {

  val numCounters = constructorArg.numcounters
  val labels = constructorArg.autoCounterPortsMap
  val hastracerwidget = constructorArg.hastracerwidget
  val trigger = WireDefault(true.B)

  val io = IO(new WidgetIO())
  val hPort = IO(HostPort(new AutoCounterBundle(numCounters)))
  val cycles = RegInit(0.U(64.W))
  val acc_cycles = RegInit(0.U(64.W))

  val hostCyclesWidthOffset = 64 - p(CtrlNastiKey).dataBits
  val hostCyclesLowWidth = if (hostCyclesWidthOffset > 0) p(CtrlNastiKey).dataBits else 64
  val hostCyclesHighWidth = if (hostCyclesWidthOffset > 0) hostCyclesWidthOffset else 0

  val hostCounterWidthOffset = 64 - p(CtrlNastiKey).dataBits
  val hostCounterLowWidth = if (hostCounterWidthOffset > 0) p(CtrlNastiKey).dataBits else 64
  val hostCounterHighWidth = if (hostCounterWidthOffset > 0) hostCounterWidthOffset else 0

  val hostReadrateWidthOffset = 64 - p(CtrlNastiKey).dataBits
  val hostReadrateLowWidth = if (hostReadrateWidthOffset > 0) p(CtrlNastiKey).dataBits else 64
  val hostReadrateHighWidth = if (hostReadrateWidthOffset > 0) hostReadrateWidthOffset else 0

  val readrate_low = RegInit(0.U(hostReadrateLowWidth.W))
  val readrate_high = RegInit(0.U(hostReadrateHighWidth.W))
  val readrate = Wire(UInt(64.W))
  val readrate_dly = RegInit(0.U(64.W))
  readrate := Cat(readrate_high, readrate_low)

  val acc_counters = RegInit(VecInit(Seq.fill(numCounters)(0.U(64.W))))

  hastracerwidget match {
      case true => BoringUtils.addSink(trigger, s"trace_trigger")
      case _ => trigger := true.B
   }

  val tFireHelper = DecoupledHelper(hPort.toHost.hValid, hPort.fromHost.hReady)
  val targetFire = tFireHelper.fire
  // We only sink tokens, so tie off the return channel
  hPort.fromHost.hValid := true.B

  when (targetFire) {
    cycles := cycles + 1.U
    readrate_dly := readrate
  }

  val periodcycles = RegInit(1.U(64.W))
  when (targetFire & (readrate =/= readrate_dly)) {
    periodcycles := readrate - 2.U
  } .elsewhen (targetFire & (periodcycles === 0.U) & (readrate > 0.U)) {
    periodcycles := readrate - 1.U
  } .elsewhen (targetFire & (cycles > 0.U) & (readrate > 0.U)) {
    periodcycles := periodcycles - 1.U
  }

  val btht_queue = Module(new Queue(new AutoCounterToHostToken(numCounters), 10))

  btht_queue.io.enq.valid := (periodcycles === 0.U) & (cycles > 0.U) & (cycles >= readrate-1.U) & targetFire & trigger
  btht_queue.io.enq.bits.data_out := hPort.hBits.counters
  btht_queue.io.enq.bits.cycle := cycles
  hPort.toHost.hReady := btht_queue.io.enq.ready & hPort.fromHost.hReady


  //communication with the driver
  val readdone = RegInit(false.B)
  val readdone_dly = RegInit(false.B)
  val readdone_negedge = Wire(Bool())
  val readdone_posedge = Wire(Bool())
  val med = RegInit(false.B)
  readdone_dly := readdone
  readdone_posedge := readdone & ~readdone_dly
  readdone_negedge := readdone_dly & ~readdone


  when (btht_queue.io.deq.fire()) {
    for (i <- 0 to numCounters-1)  { acc_counters(i) := btht_queue.io.deq.bits.data_out(i) }
    acc_cycles := btht_queue.io.deq.bits.cycle
    med := false.B
  } .elsewhen (readdone_posedge) {
    med := true.B
  } .elsewhen (readdone_negedge) {
    med := false.B
  }

  btht_queue.io.deq.ready := med 


  labels.keys.foreach {
    case (index) => {
      attach(acc_counters(index)(hostCounterLowWidth-1, 0), s"autocounter_low_${labels(index)}", ReadOnly)
      attach(acc_counters(index) >> hostCounterLowWidth, s"autocounter_high_${labels(index)}", ReadOnly)
    }
  }


  attach(acc_cycles(hostCyclesLowWidth-1, 0), "cycles_low", ReadOnly)
  attach(acc_cycles >> hostCyclesLowWidth, "cycles_high", ReadOnly)
  attach(readrate_low, "readrate_low", WriteOnly)
  attach(readrate_high, "readrate_high", WriteOnly)
  attach(btht_queue.io.deq.valid, "countersready", ReadOnly)
  attach(readdone, "readdone", WriteOnly)


  genCRFile()
}
