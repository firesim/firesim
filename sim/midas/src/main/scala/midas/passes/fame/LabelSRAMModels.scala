// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import Mappers._
import ir._
import annotations._
import collection.mutable.ArrayBuffer
import midas.targetutils.{FirrtlMemModelAnnotation, FirrtlFAMEModelAnnotation}

class LabelSRAMModels extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  // Wrapper gets converted to strip clocks from ports, has one top-level clock
  def mem2Module(mem: DefMemory): Module = {
    val clockPort = Port(NoInfo, "clk", Input, ClockType)
    def stripClocks(tpe: Type): Type = tpe match {
      case BundleType(fields) => BundleType(fields.filterNot(_.tpe == ClockType))
    }
    val ports = mem.readers ++ mem.writers ++ mem.readwriters
    val connects = ports.map(p => PartialConnect(NoInfo, WSubField(WRef(mem.name), p), WRef(p)))
    val clkConnects = ports.map(p => Connect(NoInfo, WSubField(WSubField(WRef(mem.name), p), "clk"), WRef(clockPort)))
    val modPorts = clockPort +: passes.MemPortUtils.memType(mem).fields.map(f => Port(NoInfo, f.name, Input, stripClocks(f.tpe)))
    Module(mem.info, mem.name, modPorts, Block(mem +: connects ++: clkConnects))
  }

  override def execute(state: CircuitState): CircuitState = {
    val circ = state.circuit
    val moduleNS = Namespace(circ)
    val memModelAnnotations = new ArrayBuffer[Annotation]
    val memModules = new ArrayBuffer[Module]
    val annotatedMems = state.annotations.collect({
      case FirrtlMemModelAnnotation(rt) => rt
    }).toSet

    println(s"[MIDAS 2.0] RAM Models To Extract: ${annotatedMems.size}")

    val transformedModules = circ.modules.map({
      case m: Module =>
        val mt = ModuleTarget(circ.main, m.name)
        def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
          case mem: DefMemory if annotatedMems.contains(mt.ref(mem.name)) =>
            val wrapper = mem2Module(mem).copy(name = moduleNS.newName(mem.name))
            val wrapperTarget = ModuleTarget(circ.main, wrapper.name)
            memModules += wrapper
            memModelAnnotations += FirrtlFAMEModelAnnotation(mt.instOf(mem.name, wrapper.name))
            // For now, avoid deduping mem models at the top level (annotations fragile)
            memModelAnnotations += firrtl.transforms.NoDedupAnnotation(wrapperTarget)
            memModelAnnotations ++= mem.readers.map(rp => ModelReadPort(wrapperTarget.ref(rp)))
            memModelAnnotations ++= mem.writers.map(rp => ModelWritePort(wrapperTarget.ref(rp)))
            memModelAnnotations ++= mem.readwriters.map(rp => ModelReadWritePort(wrapperTarget.ref(rp)))
            WDefInstance(mem.info, mem.name, wrapper.name, UnknownType)
          case c: Connect if (Utils.kind(c.loc) == MemKind && c.loc.tpe == ClockType) =>
            // change clock connects to target single mem wrapper clock
            val (wr, e) = Utils.splitRef(c.loc)
            val wrapperClock = Utils.mergeRef(wr, Utils.splitRef(e)._2)
            if (annotatedMems.contains(mt.ref(wr.name))) c.copy(loc = wrapperClock) else c
          case s => s
        }
        m.copy(body = m.body.map(onStmt))
      case m => m
    })
    val transformedCircuit = circ.copy(modules = (memModules ++ transformedModules).toSeq)
    // At this point, the FIRRTLMemModelAnnotations are no longer used, so remove them for cleanup.
    val filteredAnnos = state.annotations.filterNot(_.isInstanceOf[FirrtlMemModelAnnotation])
    state.copy(circuit = transformedCircuit, annotations = filteredAnnos ++ memModelAnnotations)
  }
}
