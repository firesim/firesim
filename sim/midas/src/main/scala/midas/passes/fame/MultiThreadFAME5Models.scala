// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.annotations.{ModuleName, CircuitName}
import firrtl.annotations.TargetToken.{Instance, OfModule}
import firrtl.Utils.BoolType
import firrtl.passes.InlineAnnotation

import midas.targetutils.FirrtlEnableModelMultiThreadingAnnotation

import collection.mutable

import midas.passes._

// PREREQUISITE: Top-level simulator is already fully FAME-Transformed
// ASSUMPTION: Each channel is bulk-connected to EXACTLY ONE top-level port
// ASSUMPTION: The direction of a channel port on a model mirrors its data flow direction

trait ReadyValidSignal {
  val ref: Expression
  def ready: WSubField = WSubField(ref, "ready", BoolType, UnknownFlow)
  def valid: WSubField = WSubField(ref, "valid", BoolType, UnknownFlow)
  def bits: WSubField = WSubField(ref, "bits", UnknownType, UnknownFlow)
}

case class ReadyValidSink(ref: Expression) extends ReadyValidSignal

case class ReadyValidSource(ref: Expression) extends ReadyValidSignal

object FAME5Info {
  def info = FileInfo(StringLit("@ [Added during FAME5Transform]"))
}

object Counter {
  def apply(nVals: Integer, hostClock: Expression, hostReset: Expression)(implicit ns: Namespace): SignalInfo = {
    val maxLit = UIntLiteral(BigInt(nVals - 1))
    val decl = DefRegister(FAME5Info.info, ns.newName("threadIdx"), maxLit.tpe, hostClock, hostReset, UIntLiteral(0))
    val ref = WRef(decl)
    val inc = DoPrim(PrimOps.Add, Seq(ref, UIntLiteral(1)), Nil, UnknownType)
    val wrap = DoPrim(PrimOps.Eq, Seq(ref, maxLit), Nil, BoolType)
    val assign = Connect(FAME5Info.info, ref, Mux(wrap, UIntLiteral(0), inc, UnknownType))
    SignalInfo(decl, assign, ref)
  }
}

class StaticArbiter(counter: SignalInfo) {
  private def muxIdx(select: Expression, signals: Seq[Expression]): Expression = {
    signals.zipWithIndex.tail.foldLeft(signals.head) { case (fval, (tval, idx)) =>
      Mux(DoPrim(PrimOps.Eq, Seq(select, UIntLiteral(idx)), Nil, BoolType), tval, fval, UnknownType)
    }
  }

  private def counterMask(counter: SignalInfo, idx: Integer, signal: WSubField): DoPrim = {
    val eq = DoPrim(PrimOps.Eq, Seq(counter.ref, UIntLiteral(BigInt(idx))), Nil, BoolType)
    DoPrim(PrimOps.And, Seq(signal, eq), Nil, BoolType)
  }

  def mux(sink: ReadyValidSink, sources: Seq[ReadyValidSource]): Statement = {
    val valid = muxIdx(counter.ref, sources.map(_.valid))
    val validConn = Connect(FAME5Info.info, sink.valid, valid)
    val bits = muxIdx(counter.ref, sources.map(_.bits))
    val bitsConn = Connect(FAME5Info.info, sink.bits, bits)
    val readyConns = sources.zipWithIndex.map {
      case (source, idx) => Connect(FAME5Info.info, source.ready, counterMask(counter, idx, sink.ready))
    }
    Block(validConn +: bitsConn +: readyConns)
  }

  def demux(sinks: Seq[ReadyValidSink], source: ReadyValidSource): Statement = {
    val ready = muxIdx(counter.ref, sinks.map(_.ready))
    val readyConn = Connect(FAME5Info.info, source.ready, ready)
    val bitsConns = sinks.map(sink => Connect(FAME5Info.info, sink.bits, source.bits))
    val validConns = sinks.zipWithIndex.map {
      case (sink, idx) => Connect(FAME5Info.info, sink.valid, counterMask(counter, idx, source.valid))
    }
    Block(readyConn +: bitsConns ++: validConns)
  }
}

object MultiThreadFAME5Models extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  type TopoMap = Map[OfModule, Map[String, mutable.Map[Instance, WRef]]]

  private def analyzeAndPruneTopo(fame5InstMap: Map[Instance, OfModule], topo: TopoMap)(stmt: Statement): Statement = {
    def processChannelConn(inst: Instance, modelPort: String, topConn: WRef) = {
      if (fame5InstMap.contains(inst)) {
        topo(fame5InstMap(inst))(modelPort)(inst) = topConn
        EmptyStmt
      } else {
        stmt
      }
    }

    stmt match {
      case Connect(_, WSubField(WRef(iName, _, InstanceKind, _), ipName, BundleType(_), _), wr: WRef) =>
        // Could infer that this is an input channel, but that would be an extra assumption
        processChannelConn(Instance(iName), ipName, wr)
      case Connect(_, wr: WRef, WSubField(WRef(iName, _, InstanceKind, _), ipName, BundleType(_), _)) =>
        // Could infer that this is an output channel, but that would be an extra assumption
        processChannelConn(Instance(iName), ipName, wr)
      case Connect(_, WSubField(WRef(iName, _, InstanceKind, _), _, _, _), _) if fame5InstMap.contains(Instance(iName)) =>
        EmptyStmt // prune non-channel connections
      case WDefInstance(_, iName, mName, _) if fame5InstMap.contains(Instance(iName)) =>
        EmptyStmt
      case s =>
        s.map(analyzeAndPruneTopo(fame5InstMap, topo))
    }
  }

  private def findFAME5(modInsts: collection.Map[OfModule, mutable.LinkedHashSet[Instance]])(stmt: Statement): Unit = {
    stmt match {
      case WDefInstance(_, iName, mName, _) => modInsts.get(OfModule(mName)).foreach(iSet => iSet += Instance(iName))
      case s => s.foreach(findFAME5(modInsts))
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p)  => p }).get
    if (p(midas.EnableModelMultiThreading)) {
      doTransform(state)
    } else {
      state
    }
  }

  private def doTransform(state: CircuitState): CircuitState = {
    val moduleDefs = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m}).toMap

    val top = moduleDefs(OfModule(state.circuit.main))
    implicit val ns = Namespace(top)

    val hostClock = WRef(top.ports.find(_.name == WrapTop.hostClockName).get)
    val hostReset = WRef(top.ports.find(_.name == WrapTop.hostResetName).get)
    
    // Populate keys from annotations, values from traversing statements
    val fame5RawInstances = new mutable.LinkedHashMap[OfModule, mutable.LinkedHashSet[Instance]]
    state.annotations.foreach {
      case FirrtlEnableModelMultiThreadingAnnotation(it) =>
        // TODO: why not use instance name from here?
        fame5RawInstances(OfModule(it.ofModule)) = new mutable.LinkedHashSet[Instance]
      case _ =>
    }

    top.body.foreach(findFAME5(fame5RawInstances))

    // filter models with one instance to avoid needless multithreading
    val fame5InstancesByModule = fame5RawInstances.filter { case (k, v) => v.size > 1 }
    val fame5ModulesByInstance = fame5InstancesByModule.flatMap({ case (k, v) => v.map(vv => vv -> k) }).toMap

    // Maps from an (OfModule, PortName) pair
    // It's actually nested (rather than indexed by tuple) for convenience
    // We don't track the direction in this map, since we can just find it later
    val fame5Topo: TopoMap = fame5InstancesByModule.map({
      case (m, iSet) => m -> moduleDefs(m).ports.collect({
        case Port(_, name, _, BundleType(_)) => name -> new mutable.HashMap[Instance, WRef]
      }).toMap
    }).toMap

    val prunedTopoTopBody = top.body.map(analyzeAndPruneTopo(fame5ModulesByInstance, fame5Topo))

    // For now, just one FAME5 model
    assert(fame5InstancesByModule.keySet.size <= 1)

    val nThreads = fame5InstancesByModule.headOption.map(_._2.size).getOrElse(1)

    val circuitNS = Namespace(state.circuit)
    val threadedModuleNames = state.circuit.modules.collect({
      // Don't replace blackbox instances! TODO: Check for illegal blackboxes.
      case m: Module => m.name -> circuitNS.newName(s"${m.name}_threaded")
    }).toMap

    val threadedInstances = fame5InstancesByModule.map({
      case (m, insts) => m -> WDefInstance(FAME5Info.info, ns.newName(s"${m.value}_threaded"), threadedModuleNames(m.value), UnknownType)
    }).toMap

    val threadCounters = fame5InstancesByModule.map { case (m, insts) => m -> Counter(insts.size, hostClock, hostReset) }

    val multiThreadedConns: Seq[Statement] = fame5Topo.toSeq.flatMap {
      case (mod, connsByPort) =>
        val arbiter = new StaticArbiter(threadCounters(mod))
        connsByPort.toSeq.map {
          case (port, connsByInstance) =>
            val canonicalOrderedConns = fame5InstancesByModule(mod).map(inst => connsByInstance(inst)).toSeq
            if (moduleDefs(mod).ports.exists(p => p.name == port && p.direction == Input)) {
              val sink = ReadyValidSink(WSubField(WRef(threadedInstances(mod)), port))
              val sources = canonicalOrderedConns.map(s => ReadyValidSource(s))
              arbiter.mux(sink, sources)
            } else {
              val sinks = canonicalOrderedConns.map(s => ReadyValidSink(s))
              val source = ReadyValidSource(WSubField(WRef(threadedInstances(mod)), port))
              arbiter.demux(sinks, source)
            }
        }
    }

    val insts = threadedInstances.toSeq.map { case (k, v) => v } // keep ordering
    val counters = threadCounters.toSeq.map { case (k, v) => v } // keep ordering
    val clockConns = insts.map(i => Connect(FAME5Info.info, WSubField(WRef(i), WrapTop.hostClockName), WRef(WrapTop.hostClockName)))
    val resetConns = insts.map(i => Connect(FAME5Info.info, WSubField(WRef(i), WrapTop.hostResetName), WRef(WrapTop.hostResetName)))

    val prologue = insts ++: clockConns ++: resetConns ++: counters.flatMap(c => Seq(c.decl, c.assigns))
    val multiThreadedTopBody = Block(prologue ++: prunedTopoTopBody +: multiThreadedConns)

    val transformedModules = state.circuit.modules.flatMap {
      case m: Module if (m.name == state.circuit.main) =>
        Seq(m.copy(body = multiThreadedTopBody))
      case m: Module =>
        val threaded = MuxingMultiThreader(threadedModuleNames)(m, nThreads) // all threaded by same amount, many get pruned
        Seq(m, threaded)
      case m => Seq(m)
    }

    // TODO: Renames!

    val threadedCircuit = state.circuit.copy(modules = transformedModules)
    val withMemImpls = ImplementThreadedMems(threadedCircuit)
    val memImplNames = withMemImpls.modules.map(_.name).toSet -- threadedCircuit.modules.map(_.name).toSet
    val inlineThreadedMemAnnos = memImplNames.map(s => InlineAnnotation(ModuleName(s, CircuitName(withMemImpls.main))))

    state.copy(circuit = withMemImpls, annotations = state.annotations ++ inlineThreadedMemAnnos)
  }
}
