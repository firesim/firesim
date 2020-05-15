// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.Utils.{BoolType, kind, zero, one}

import firrtl.passes.MemPortUtils

import midas.passes.SignalInfo

import collection.mutable
import collection.immutable.HashMap

case class TopLevelReadPort(name: String) {
  def addrRef: Expression = ???
  def dataRef: Expression = ???
}

case class TopLevelWritePort(name: String) {
  def clockRef: Expression = ???
  def enRef: Expression = ???
  def addrRef: Expression = ???
  def dataRef: Expression = ???
}

object ShiftRegister {
  def parallelIn(parallel: Seq[Expression], baseName: String, trigger: Expression, default: Option[Literal] = None)
    (implicit hostClock: Expression, ns: Namespace): SignalInfo = {
    ???
  }

  def parallelOut(input: Expression, baseName: String, n: Int, enable: Expression)
    (implicit hostClock: Expression, ns: Namespace): Seq[SignalInfo] = {
    ???
  }
}

object EnableTimer {
  def apply(durationAfterTrigger: BigInt, baseName: String, trigger: Expression)
    (implicit hostClock: Expression, ns: Namespace): SignalInfo = {
    val lit = UIntLiteral(durationAfterTrigger)
    val timer = DefRegister(FAME5Info.info, ns.newName(baseName), lit.tpe, hostClock, lit, trigger)
    val minusOne = DoPrim(PrimOps.Sub, Seq(WRef(timer), Utils.one), Nil, UnknownType)
    val nonZero = DefNode(FAME5Info.info, ns.newName(s"${timer.name}_active"), Neq(WRef(timer), Utils.zero))
    val dec = ConditionalConnect(WRef(nonZero), WRef(timer), minusOne)
    SignalInfo(Block(timer, nonZero), dec, WRef(nonZero))
  }
}

object StateUpdateTriggerTracker {
  def apply(targetClock: Expression, trigger: Expression)
    (implicit hostClock: Expression, ns: Namespace): SignalInfo = {
    // Was the trigger high and did targetClock toggle on the last hostClock edge?
    // Make target-clocked register, make it toggle iff trigger is high
    val tgtClocked = RegZeroPreset(FAME5Info.info, ns.newName("targetTriggerTracker"), targetClock)
    val tgtUpdate = ConditionalConnect(trigger, WRef(tgtClocked), Negate(WRef(tgtClocked)))
    val hostClocked = RegZeroPreset(FAME5Info.info, ns.newName("triggerTrackerLast"), hostClock)
    val hostUpdate = ConditionalConnect(trigger, WRef(tgtClocked), WRef(tgtClocked))
    val triggered = DefNode(FAME5Info.info, ns.newName("targetGatedTrigger"), Xor(WRef(tgtClocked), WRef(hostClocked)))
    SignalInfo(Block(tgtClocked, hostClocked, triggered), Block(tgtUpdate, hostUpdate), WRef(triggered))
  }
}

case class SimpleReadCmd(addr: Expression)
case class SimpleWriteCmd(en: Expression, addr: Expression, data: Expression)

object SimpleDualPortMem {
  def apply(name: String, dataType: Type, depth: BigInt, clk: Expression, read: SimpleReadCmd, write: SimpleWriteCmd): SignalInfo = {
    require(dataType.isInstanceOf[GroundType])
    val mem = DefMemory(FAME5Info.info, name, dataType, depth, 1, 1, Seq("r"), Seq("w"), Nil, ReadUnderWrite.Old)
    def connectReadField(field: String, rhs: Expression) =
      Connect(FAME5Info.info, MemPortUtils.memPortField(mem, mem.readers.head, field), rhs)
    def connectWriteField(field: String, rhs: Expression) =
      Connect(FAME5Info.info, MemPortUtils.memPortField(mem, mem.readers.head, field), rhs)
    SignalInfo(
      mem,
      Block(
        connectReadField("clk", clk),
        connectReadField("en", Utils.one),
        connectReadField("addr", read.addr),
        connectWriteField("clk", clk),
        connectWriteField("en", write.en),
        connectWriteField("mask", Utils.one),
        connectWriteField("addr", write.addr),
        connectWriteField("data", write.data)),
      MemPortUtils.memPortField(mem, mem.readers.head, "data")
    )
  }
}

object ThreadedMemModel {
  def apply(model: DefMemory, schedule: ThreadSchedule, moduleName: String): Module = {
    require(model.readwriters.isEmpty)
    val rPortIO = model.readers.map(r => TopLevelReadPort(r))
    val wPortIO = model.writers.map(w => TopLevelWritePort(w))

    implicit val hostClock = WRef(WrapTop.hostClockName)
    implicit val ns = Namespace(model.readers ++ model.writers)

    val scheduler = schedule.construct(hostClock, WRef(WrapTop.hostResetName), ns)

    val logic: Seq[Statement] = (0 to scheduler.nThreads).flatMap { tid =>
      val rAddrRegs = ShiftRegister.parallelIn(rPortIO.map(_.addrRef), s"t${tid}_rAddrPipe", scheduler.startReads(model, tid))
      val rTimer = EnableTimer(rPortIO.length, s"t${tid}_rTimer", scheduler.startReads(model, tid))

      val wAddrRegs = ShiftRegister.parallelIn(wPortIO.map(_.addrRef), s"t${tid}_wAddrPipe", scheduler.startWrites(model, tid))
      val wDataRegs = ShiftRegister.parallelIn(wPortIO.map(_.dataRef), s"t${tid}_wDataPipe", scheduler.startWrites(model, tid))
      val wEnRegs = ShiftRegister.parallelIn(wPortIO.map(_.enRef), s"t${tid}_wEnPipe", scheduler.startWrites(model, tid), Some(Utils.zero))

      // PRECONDITION: Assumes that this memory is used in a single-clock context
      val wEnTracker = StateUpdateTriggerTracker(wPortIO.head.clockRef, scheduler.startWrites(model, tid))

      val rCmd = SimpleReadCmd(rAddrRegs.ref)
      val wCmd = SimpleWriteCmd(And(wEnTracker.ref, wEnRegs.ref), wAddrRegs.ref, wDataRegs.ref)
      val bram = SimpleDualPortMem(s"t${tid}_mem", model.dataType, model.depth, hostClock, rCmd, wCmd)

      val rDataRegs = ShiftRegister.parallelOut(bram.ref, s"t${tid}_rDataPipe", rPortIO.length, rTimer.ref)

      val components = Seq(rAddrRegs, rTimer, wAddrRegs, wDataRegs, wEnRegs, wEnTracker, bram) ++ rDataRegs

      val topConnects = (rPortIO zip rDataRegs).map { case (p, r) => ConditionalConnect(scheduler.readsVisible(model, tid), p.dataRef, r.ref) }

      components.map(_.decl) ++ components.map(_.assigns) ++ topConnects
    }

    val ports = MemPortUtils.memType(model).fields.map { f => Port(FAME5Info.info, f.name, Input, f.tpe) }
    AddHostClockAndReset(Module(FAME5Info.info, moduleName, ports, Block(scheduler.impl +: logic)))
  }
}
