package midas
package widgets

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util._

import midas.targetutils.{PerfCounterOpType, PerfCounterOps}

trait AutoCounterConsts {
  val counterWidth = 64

  /* Quotes the description escapes potentially troublesome characters */
  def sanitizeDescriptionForCSV(description: String): String =
    '"' + description.replaceAll("\"", "\"\"") + '"'
}

/**
  * Captures target-side information about an annotated event
  *
  * @param portName the name of the IF exposed to the bridge by the autocounter transform
  *
  * @param label The user provided [[AutoCounterFirrtlAnnotation]].label prepended with an instance path.
  *
  * @param description A passthrough of [[AutoCounterFirrtlAnnotation]].description
  *
  * @param width The bitwidth of the event
  *
  * @param opType The type of accumulation operation to apply to event
  */
case class EventMetadata(
  portName: String,
  label: String,
  description: String,
  width: Int,
  opType: PerfCounterOpType) extends AutoCounterConsts

object EventMetadata {
  val localCycleCount = EventMetadata(
    "N/A",
    "local_cycle",
    "Clock cycles elapsed in the local domain.",
    1,
    PerfCounterOps.Accumulate)
}

class AutoCounterBundle(
    eventMetadata: Seq[EventMetadata],
    triggerName: String,
    resetPortName: String) extends Record {
  val triggerEnable = Input(Bool())
  val underGlobalReset = Input(Bool())
  val events = eventMetadata.map(e => e.portName -> Input(UInt(e.width.W)))
  val elements = collection.immutable.ListMap((
    (triggerName, triggerEnable) +:
    (resetPortName, underGlobalReset) +:
    events):_*)
}

case class AutoCounterParameters(eventMetadata: Seq[EventMetadata], triggerName: String, resetPortName: String)

class AutoCounterBridgeModule(key: AutoCounterParameters)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[AutoCounterBundle]]()(p) with AutoCounterConsts {

  val eventMetadata = key.eventMetadata
  val triggerName = key.triggerName
  val resetPortName = key.resetPortName

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(new AutoCounterBundle(eventMetadata, triggerName, resetPortName)))
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
    val targetFire = tFireHelper.fire()
    // We only sink tokens, so tie off the return channel
    hPort.fromHost.hValid := true.B

    when (targetFire) {
      cycles := cycles + 1.U
    }

    val counters = for (((_, field), metadata) <- hPort.hBits.events.zip(eventMetadata)) yield {
      metadata.opType match {
        case PerfCounterOps.Accumulate =>
          val count = RegInit(0.U(counterWidth.W))
          when (targetFire && !hPort.hBits.underGlobalReset) {
            count := count + field
          }
          count
        // Under local reset identity fields are zeroed out. This matches that behavior.
        case PerfCounterOps.Identity =>
          Mux(hPort.hBits.underGlobalReset, 0.U, field).pad(counterWidth)
      }
    }

    val periodcycles = RegInit(0.U(64.W))
    val isSampleCycle = periodcycles === readrate
    // Pipeline sample by one cycle, so that events on the final clock cycle of
    // the interval can be captured.  This has the effect of making a signal
    // that is always high read a multiple of N, where N is the sampling rate.
    val doSample = RegInit(false.B)
    when (targetFire && isSampleCycle) {
      periodcycles := 0.U
      doSample := true.B
    } .elsewhen (targetFire) {
      periodcycles := periodcycles + 1.U
      doSample := false.B
    }

    val allEventMetadata = EventMetadata.localCycleCount +: eventMetadata
    val allCounters      = cycles +: counters

    assert(allCounters.size == allEventMetadata.size)
    val numCounters = allCounters.size
    val labels = allEventMetadata.map(_.label)

    val btht_queue = Module(new Queue(Vec(numCounters, UInt(counterWidth.W)), 2))

    btht_queue.io.enq.valid := doSample && targetFire && trigger
    btht_queue.io.enq.bits := VecInit(allCounters)
    hPort.toHost.hReady := targetFire

    val (lowCountAddrs, highCountAddrs) = (for ((counter, label) <- btht_queue.io.deq.bits.zip(labels)) yield {
      val lowAddr = attach(counter(hostCounterLowWidth-1, 0), s"autocounter_low_${label}", ReadOnly, false)
      val highAddr = attach(counter >> hostCounterLowWidth, s"autocounter_high_${label}", ReadOnly, false)
      (lowAddr, highAddr)
    }).unzip

    //communication with the driver
    // These are not current used, but are convienent to poke at from the driver
    attach(btht_queue.io.deq.bits(0)(hostCyclesLowWidth-1, 0), "cycles_low", ReadOnly)
    attach(btht_queue.io.deq.bits(0) >> hostCyclesLowWidth, "cycles_high", ReadOnly)

    attach(readrate_low, "readrate_low", WriteOnly)
    attach(readrate_high, "readrate_high", WriteOnly)
    attach(initDone, "init_done", WriteOnly)
    attach(btht_queue.io.deq.valid, "countersready", ReadOnly)
    Pulsify(genWORegInit(btht_queue.io.deq.ready, "readdone", false.B), 1)

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
          base,
          sb,
          "autocounter_t",
          "autocounter",
          Seq(
              StdVector("autocounter_t::Counter",
                allEventMetadata.zip(highCountAddrs).zip(lowCountAddrs) map { case ((m, hi), lo) =>
                  CppStruct("autocounter_t::Counter", Seq(
                    "type" -> CStrLit(m.opType.toString),
                    "event_label" -> CStrLit(m.label),
                    "event_msg" -> CStrLit(m.description),
                    "bit_width" -> UInt32(m.width),
                    "accumulator_width" -> UInt32(m.counterWidth),
                    "event_addr_hi" -> UInt32(base + hi),
                    "event_addr_lo" -> UInt32(base + lo)
                  ))
              }),
              Verbatim(clockDomainInfo.toC)
          ),
          hasLoadMem = false,
          hasMMIOAddrMap = true
      )
    }
    genCRFile()
  }
}
