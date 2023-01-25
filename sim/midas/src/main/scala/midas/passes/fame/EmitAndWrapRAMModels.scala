// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import Mappers._
import annotations._

import collection.mutable

/**************
 PRECONDITIONS:
 **************
 1.) FAME1 Tranform has been run on all models
 */

// Assumptions:
// - MemModelAnnotations are on instances of FAME1 models now
// - All mem port annotations on different instances point at the same model ports
//

//class DummyRAMModel extends Module

trait IsMemoryPort {
  def anno: MemPortAnnotation
  def ports: Seq[Port]
  def mTarget = anno.addr.moduleTarget
  def portMap: Map[ReferenceTarget, Port] = ports.map(p => mTarget.ref(p.name) -> p).toMap
  def dataPortRT: ReferenceTarget
  // From a reference target, look up the field name
  def getFieldName(rT: ReferenceTarget): String = {
    rT.component.reverse.head match {
      case TargetToken.Field(value) => value
      case _ => throw new RuntimeException("Leaf component of a field RT should be a Field.")
    }
  }

  def getSubType(rT: ReferenceTarget): Type = {
    val subField = portMap(rT.copy(component = Nil)).tpe match {
      case t: BundleType if IsDecoupled(t) =>
        t.fields.collectFirst({
          case f@Field(name,_, _: GroundType) if name == "bits" => f
          case f@Field(name,_,BundleType(subFields)) if name == "bits" =>
            subFields.collectFirst({
              case sf@Field(name,_,_) if name == getFieldName(rT) => sf
            }).get
          }).get
      case _ => throw new RuntimeException("Expected (aggregate) decoupled channel")
    }
    subField.tpe
  }

  def addrWidth: Int = {
    val tpe = getSubType(anno.addr)
    tpe match {
      case UIntType(w: IntWidth) => w.width.toInt
      case _ => throw new RuntimeException("Unresolvable address width in RAM model port")
    }
  }

  def dataWidth: Int = {
    getSubType(dataPortRT) match {
      case UIntType(w: IntWidth) => w.width.toInt
      case _ => throw new RuntimeException("Unresolvable data width in RAM model port")
    }
  }
  def bits(rT: ReferenceTarget): WSubField = {
    require(rT.component.size <= 2, "FAME1 channels should not have aggregates in their payloads")
    val channel = portMap(rT.copy(component = Nil))
    val bitsSF = WSubField(WRef(channel), "bits")

    if (rT.component.size == 2) {
      WSubField(bitsSF, getFieldName(rT))
    } else {
      bitsSF
    }
  }
  def valid(rT: ReferenceTarget): WSubField = WSubField(WRef(portMap(rT.copy(component = Nil))), "valid")
  def ready(rT: ReferenceTarget): WSubField = WSubField(WRef(portMap(rT.copy(component = Nil))), "ready")
}

class ReadPort(val anno: ModelReadPort, val ports: Seq[Port]) extends IsMemoryPort {
  def dataPortRT = anno.data
  def attachChannels(readCmd: Decoupled, readData: Decoupled): Seq[Statement] = {
    val iSignals = Seq(anno.addr, anno.en)
    val iValids  = iSignals.map(valid)
    def iReady(i: ReferenceTarget) = {
      And.reduce(readCmd.ready +: iSignals.filterNot(_ == i).map(valid))
    }
    Seq(
      Connect(NoInfo, readCmd.valid, And.reduce(iValids)),
      Connect(NoInfo, readCmd.bits("en"), bits(anno.en)),
      Connect(NoInfo, readCmd.bits("addr"), bits(anno.addr)),
      Connect(NoInfo, ready(anno.addr), iReady(anno.addr)),
      Connect(NoInfo, ready(anno.en), iReady(anno.en)),
      Connect(NoInfo, bits(anno.data), readData.bits()),
      Connect(NoInfo, valid(anno.data), readData.valid),
      Connect(NoInfo, readData.ready, ready(anno.data))
    )
  }
}

class WritePort(val anno: ModelWritePort, val ports: Seq[Port]) extends IsMemoryPort {
  def dataPortRT = anno.data
  def attachChannels(writeCmd: Decoupled): Seq[Statement] = {
    val iSignals = Seq(anno.data, anno.mask, anno.addr, anno.en)
    val iValids  = iSignals.map(valid)
    def iReady(i: ReferenceTarget) = {
      And.reduce(writeCmd.ready +: iSignals.filterNot(_ == i).map(valid))
    }
    iSignals.flatMap(rT => Seq(
      Connect(NoInfo, ready(rT), iReady(rT))
    )) ++ Seq(
      Connect(NoInfo, writeCmd.valid, And.reduce(iValids)),
      Connect(NoInfo, writeCmd.bits("en"), bits(anno.en)),
      Connect(NoInfo, writeCmd.bits("addr"), bits(anno.addr)),
      Connect(NoInfo, writeCmd.bits("data"), bits(anno.data)),
      Connect(NoInfo, writeCmd.bits("mask"), bits(anno.mask)))
  }
}

class ReadWritePort(val anno: ModelReadWritePort, val ports: Seq[Port]) extends IsMemoryPort {
  def dataPortRT = anno.wdata
}

class RAMModelInst(name: String, val readPorts: Seq[ReadPort], val writePorts: Seq[WritePort]) {
  val allPorts = (readPorts ++ writePorts).toSeq
  def resolveAndCheckParameter(getParamFunc: IsMemoryPort => Int)
              (memoryPortList: Seq[IsMemoryPort]): Int = {
    require(memoryPortList.size > 0)
    memoryPortList.foldLeft(None: Option[Int])((prevWidth: Option[Int], port) => {
      val currentWidth = getParamFunc(port)
      (prevWidth, currentWidth) match {
        case (Some(prevWidth), currentWidth) if prevWidth != currentWidth =>
          throw new RuntimeException("All memory port widths must be equal")
        case _ => Some(currentWidth)
      }
      Some(currentWidth)
    }).get
  }

  def getAddressWidth: Seq[IsMemoryPort] => Int = resolveAndCheckParameter(_.addrWidth)
  def getDataWidth: Seq[IsMemoryPort] => Int = resolveAndCheckParameter(_.dataWidth)

  val addrWidth = getAddressWidth(allPorts)
  val dataWidth = getDataWidth(allPorts)
  val depth = BigInt(1) << addrWidth

  def readCommand  = BundleType(Seq(Field("en", Default, Utils.BoolType), Field("addr", Flip, UnknownType)))
  def readDataType = UIntType(UnknownWidth)
  def writeCommand = BundleType(Seq(
    Field("en", Default, Utils.BoolType),
    Field("mask", Default, Utils.BoolType),
    Field("addr", Default, UnknownType),
    Field("data", Default, UnknownType)))
  def instType() = new BundleType(Seq(
    Field("clock", Flip, ClockType),
    Field("reset", Flip, Utils.BoolType),
    Field("channels", Default, BundleType(Seq(
      Field("reset", Flip, Decouple(Utils.BoolType)),
      Field("read_cmds", Flip, VectorType(Decouple(readCommand), readPorts.size)),
      Field("read_resps", Default, VectorType(Decouple(UnknownType), readPorts.size)),
      Field("write_cmds", Flip, VectorType(Decouple(writeCommand), writePorts.size)))))
  ))

  def defInst() = WDefInstance(NoInfo, "model", name, instType())
  def refInst() = WRef(defInst())

  private def portNo(vecName: String, elmType: Type)(idx: Int) =
    new Decoupled(WSubIndex(WSubField(WSubField(refInst(), "channels"), vecName), idx, elmType, UnknownFlow))
  def readCmdPort: Int => Decoupled = portNo("read_cmds", readCommand)
  def readDataPort: Int => Decoupled = portNo("read_resps", readDataType)
  def writePort: Int => Decoupled = portNo("write_cmds", writeCommand)
  def resetChannel = new Decoupled(WSubField(WSubField(refInst(), "channels"), "reset"))

  def emitStatements(clock: WRef, hostReset: WRef): Seq[Statement] = {
    val readConnects = readPorts.zipWithIndex.flatMap({ case (readPort, idx) =>
      readPort.attachChannels(readCmdPort(idx), readDataPort(idx)) })
    val writeConnects = writePorts.zipWithIndex.flatMap({ case (wPort, idx) =>
      wPort.attachChannels(writePort(idx)) })
    Seq(defInst(),
        Connect(NoInfo, WSubField(refInst(), "clock"), clock),
        Connect(NoInfo, WSubField(refInst(), "reset"), hostReset),
        Connect(NoInfo, resetChannel.valid, Utils.one),
        Connect(NoInfo, resetChannel.bits(), Utils.zero)
    ) ++ readConnects ++ writeConnects
  }

  def elaborateModel(parentCircuitNS: Namespace): Module = {
    val chirrtl = chisel3.stage.ChiselStage.convert(new midas.models.sram.AsyncMemChiselModel(depth.toInt, dataWidth, readPorts.size, writePorts.size))
    val state = new MiddleFirrtlCompiler().compile(CircuitState(chirrtl, ChirrtlForm, Nil), Nil)
    require(state.circuit.modules.length == 1)
    state.circuit.modules.collectFirst({
      case m: Module => m.copy(name = name)
    }).get
  }
}


class EmitAndWrapRAMModels extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm

  val portAnnos = new mutable.LinkedHashMap[ModuleTarget, mutable.Set[MemPortAnnotation]] with
                      mutable.MultiMap[ModuleTarget, MemPortAnnotation]

  val addedModules = new mutable.ArrayBuffer[DefModule]
  val addedAnnotations = new mutable.ArrayBuffer[Annotation]


  override def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val ns = Namespace(c)

    val memModelAnnotations = state.annotations.collect({
      case anno: MemPortAnnotation => portAnnos.addBinding(anno.addr.moduleTarget, anno) 
    })


    def wrapRAMModel(mod: DefModule): DefModule = {
      val annos = portAnnos(ModuleTarget(c.main, mod.name))
      val readPorts = annos.collect({ case anno: ModelReadPort  => new ReadPort(anno, mod.ports)}).toSeq
      val writePorts = annos.collect({ case anno: ModelWritePort  => new WritePort(anno, mod.ports)}).toSeq
      val readWritePorts = annos.collect({ case anno: ModelReadWritePort  => new ReadWritePort(anno, mod.ports)})
      require(readWritePorts.isEmpty)

      val hostClock = mod.ports.find(_.name == WrapTop.hostClockName).get
      val hostReset = mod.ports.find(_.name == WrapTop.hostResetName).get

      val name = ns.newName("RamModel")
      val inst = new RAMModelInst(name, readPorts, writePorts)
      addedModules += inst.elaborateModel(Namespace(c))
      Module(NoInfo, mod.name, mod.ports, Block(inst.emitStatements(WRef(hostClock), WRef(hostReset))))
    }

    def onModule(mod: DefModule): DefModule = mod match {
      case mod if portAnnos.contains(ModuleTarget(c.main, mod.name)) => wrapRAMModel(mod)
      case mod => mod
    }

    val newCircuit = state.circuit.map(onModule)

    state.copy(circuit = newCircuit.copy(modules = newCircuit.modules ++ addedModules))
  }
}
