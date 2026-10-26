// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.annotations._
import firrtl.Utils.BoolType
import midas.targetutils.FirrtlMemModelAnnotation
import scala.collection.mutable

/** The serialized RAM implementation is asynchronous. Keep synchronous-read latency in target-clocked registers outside
  * the RAM model wrapper, rather than silently dropping the original memory's read latency during wrapping.
  */
class NormalizeSynchronousRAMs extends Transform {
  def inputForm  = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState): CircuitState = {
    val marked  = state.annotations.collect { case FirrtlMemModelAnnotation(t) => t }.toSet
    val renames = RenameMap()
    def transform(module: DefModule): DefModule = module match {
      case m: Module =>
        val mt       = ModuleTarget(state.circuit.main, m.name)
        val memories = mutable.ArrayBuffer[DefMemory]()
        val inputs   = mutable.Map[(String, String, String), Expression]()
        def collect(s: Statement): Unit = {
          s match {
            case mem: DefMemory if marked(mt.ref(mem.name)) && mem.readLatency > 0                => memories += mem
            case Connect(_, WSubField(WSubField(WRef(n, _, _, _), port, _, _), field, _, _), rhs) =>
              inputs((n, port, field)) = rhs
            case _                                                                                =>
          }
          s.foreach(collect)
        }
        collect(m.body)
        if (memories.isEmpty) m
        else {
          val ns           = Namespace(m)
          val declarations = mutable.ArrayBuffer[Statement]()
          val assignments  = mutable.ArrayBuffer[Statement]()
          val replacements = mutable.Map[(String, String), WRef]()
          val pipelines    = mutable.Map[(String, String), (DefWire, Seq[WRef])]()
          val selected     = memories.map(_.name).toSet
          def rewrite(e: Expression): Expression = e.map(rewrite) match {
            case s @ WSubField(WSubField(WRef(n, _, _, _), port, _, _), "data", _, _) =>
              replacements.getOrElse((n, port), s)
            case other                                                                => other
          }
          for (mem <- memories) {
            require(mem.readwriters.isEmpty, "RAM read/write ports require separate port lowering before optimization")
            require(mem.dataType.isInstanceOf[GroundType], "RAM normalization expects lowered ground data")
            for (reader <- mem.readers) {
              val clk    = DefWire(NoInfo, ns.newName(s"${mem.name}_${reader}_read_clock"), ClockType)
              declarations += clk
              val stages = (0 until mem.readLatency).map { index =>
                val name = ns.newName(s"${mem.name}_${reader}_read_delay_$index")
                val ref  = WRef(name, mem.dataType, RegKind, SourceFlow)
                val reg  = DefRegister(NoInfo, name, mem.dataType, WRef(clk), UIntLiteral(0), ref)
                declarations += reg
                ref
              }
              pipelines((mem.name, reader)) = (clk, stages)
              replacements((mem.name, reader)) = stages.last
              renames.record(mt.ref(mem.name).field(reader).field("data"), mt.ref(stages.last.name))
            }
          }
          for {
            mem <- memories
            reader <- mem.readers
          } {
            val (clk, regs)       = pipelines((mem.name, reader))
            assignments += Connect(NoInfo, WRef(clk), rewrite(inputs((mem.name, reader, "clk"))))
            val en                = rewrite(inputs((mem.name, reader, "en")))
            val addr              = rewrite(inputs((mem.name, reader, "addr")))
            var value: Expression = WSubField(WSubField(WRef(mem.name), reader), "data", mem.dataType)
            // Preserve explicitly requested write-first behavior on collisions.
            if (mem.readUnderWrite == ReadUnderWrite.New) for (writer <- mem.writers) {
              val active  = DoPrim(
                PrimOps.And,
                Seq(rewrite(inputs((mem.name, writer, "en"))), rewrite(inputs((mem.name, writer, "mask")))),
                Nil,
                BoolType,
              )
              val same    = DoPrim(PrimOps.Eq, Seq(addr, rewrite(inputs((mem.name, writer, "addr")))), Nil, BoolType)
              val forward = DoPrim(PrimOps.And, Seq(active, same), Nil, BoolType)
              value = Mux(forward, rewrite(inputs((mem.name, writer, "data"))), value, mem.dataType)
            }
            assignments += Connect(NoInfo, regs.head, Mux(en, value, regs.head, mem.dataType))
            regs.sliding(2).foreach {
              case Seq(previous, next) => assignments += Connect(NoInfo, next, previous); case _ =>
            }
          }
          def statement(s: Statement): Statement = s.map(statement).map(rewrite) match {
            case mem: DefMemory if selected(mem.name) => mem.copy(readLatency = 0)
            case other                                => other
          }
          m.copy(body = Block(declarations.toSeq ++ Seq(statement(m.body)) ++ assignments.toSeq))
        }
      case other     => other
    }
    state.copy(circuit = state.circuit.copy(modules = state.circuit.modules.map(transform)), renames = Some(renames))
  }
}
