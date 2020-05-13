package midas
package widgets

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

trait AutoCounterConsts {
  val counterWidth = 64
}

class AutoCounterBundle(eventNames: Seq[String], triggerName: String) extends Record {
  val triggerEnable = Input(Bool())
  val events = eventNames.map(_ -> Input(Bool()))
  val elements = collection.immutable.ListMap(((triggerName, triggerEnable) +:
                                               events):_*)
  override def cloneType = new AutoCounterBundle(eventNames, triggerName).asInstanceOf[this.type]
}

class AutoCounterToHostToken(val numCounters: Int) extends Bundle with AutoCounterConsts {
  val data_out = Vec(numCounters, UInt(counterWidth.W))
  val cycle = UInt(counterWidth.W)
}

class AutoCounterBridgeModule(events: Seq[(String, String)], triggerName: String)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[AutoCounterBundle]]()(p) with AutoCounterConsts {
  lazy val module = new BridgeModuleImp(this) {
    val numCounters = events.size
    val (portNames, labels) = events.unzip

    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(new AutoCounterBundle(portNames, triggerName)))
    val trigger = hPort.hBits.triggerEnable
    val cycles = RegInit(0.U(counterWidth.W))
    val acc_cycles = RegInit(0.U(counterWidth.W))

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
    val readrate = Cat(readrate_high, readrate_low)
    val initDone = RegInit(false.B)

    val tFireHelper = DecoupledHelper(hPort.toHost.hValid, hPort.fromHost.hReady, initDone)
    val targetFire = tFireHelper.fire
    // We only sink tokens, so tie off the return channel
    hPort.fromHost.hValid := true.B

    when (targetFire) {
      cycles := cycles + 1.U
    }

    val counters = hPort.hBits.events.unzip._2.map({ increment =>
      val count = RegInit(0.U(counterWidth.W))
      when (targetFire && increment) {
        count := count + 1.U
      }
      count
    }).toSeq

    val periodcycles = RegInit(0.U(64.W))
    val isSampleCycle = periodcycles === readrate
    when (targetFire && isSampleCycle) {
      periodcycles := 0.U
    } .elsewhen (targetFire) {
      periodcycles := periodcycles + 1.U
    }

    val btht_queue = Module(new Queue(new AutoCounterToHostToken(numCounters), 2))

    btht_queue.io.enq.valid := isSampleCycle & targetFire & trigger
    btht_queue.io.enq.bits.data_out := VecInit(counters)
    btht_queue.io.enq.bits.cycle := cycles
    hPort.toHost.hReady := targetFire

    val (lowCountAddrs, highCountAddrs) = (for ((counter, label) <- btht_queue.io.deq.bits.data_out.zip(labels)) yield {
      val lowAddr = attach(counter(hostCounterLowWidth-1, 0), s"autocounter_low_${label}", ReadOnly)
      val highAddr = attach(counter >> hostCounterLowWidth, s"autocounter_high_${label}", ReadOnly)
      (lowAddr, highAddr)
    }).unzip

    //communication with the driver
    attach(btht_queue.io.deq.bits.cycle(hostCyclesLowWidth-1, 0), "cycles_low", ReadOnly)
    attach(btht_queue.io.deq.bits.cycle >> hostCyclesLowWidth, "cycles_high", ReadOnly)
    attach(readrate_low, "readrate_low", WriteOnly)
    attach(readrate_high, "readrate_high", WriteOnly)
    attach(initDone, "init_done", WriteOnly)
    attach(btht_queue.io.deq.valid, "countersready", ReadOnly)
    Pulsify(genWORegInit(btht_queue.io.deq.ready, "readdone", false.B), 1)

    override def genHeader(base: BigInt, sb: StringBuilder) {
      headerComment(sb)
      // Exclude counter addresses as their names can vary across AutoCounter instances, but 
      // we only generate a single struct typedef
      val headerWidgetName = wName.toUpperCase
      crRegistry.genHeader(headerWidgetName, base, sb, lowCountAddrs ++ highCountAddrs)
      crRegistry.genArrayHeader(headerWidgetName, base, sb)
      emitClockDomainInfo(headerWidgetName, sb)
    }
    genCRFile()
  }
}
