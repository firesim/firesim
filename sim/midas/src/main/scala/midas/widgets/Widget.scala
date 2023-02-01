// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.DataMirror
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.ParameterizedBundle

import scala.collection.mutable

// The AXI4-lite key for the simulation control bus
case object CtrlNastiKey extends Field[NastiParameters]

// Just NASTI, but pointing at the right key.
class WidgetMMIO(implicit p: Parameters) extends NastiIO()(p)
  with HasNastiParameters

object WidgetMMIO {
  def apply()(implicit p: Parameters): WidgetMMIO = {
    new WidgetMMIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) }))
  }
}

// All widgets must implement this interface
// NOTE: Changing ParameterizedBundle -> Bundle breaks PeekPokeWidgetIO when
// outNum = 0
class WidgetIO(implicit p: Parameters) extends ParameterizedBundle()(p){
  val ctrl = Flipped(WidgetMMIO())
}
abstract class Widget()(implicit p: Parameters) extends LazyModule()(p) {
  require(p(CtrlNastiKey).dataBits == 32, "Control bus data width must be 32b per AXI4-lite standard")

  override def module: WidgetImp
  val (wName, wId) = Widget.assignName(this)
  this.suggestName(wName)

  def getWId = wId
  def getWName = wName

  // Returns widget-relative word address
  def getCRAddr(name: String): Int = {
    module.crRegistry.lookupAddress(name).getOrElse(
      throw new RuntimeException(s"Could not find CR:${name} in widget: $wName"))
  }

  def headerComment(sb: StringBuilder): Unit = {
    val name = getWName.toUpperCase
    sb.append("\n// Widget: %s\n".format(getWName))
    sb.append(CppGenerationUtils.genMacro(s"${name}(x)", s"${name}_ ## x"))
  }

  val customSize: Option[BigInt] = None
  def memRegionSize = customSize.getOrElse(BigInt(1 << log2Up(module.numRegs * (module.ctrlWidth/8))))

  def printCRs = module.crRegistry.printCRs

  /**
    * Provides a mechanism for mixins to register additional collateral
    */
  private val _headerFragmentFuncs = new mutable.ArrayBuffer[(BigInt) => Seq[String]]()
  def appendHeaderFragment(f: (BigInt) => Seq[String]): Unit = {
    _headerFragmentFuncs += f
  }
  def getHeaderFragments(base: BigInt): Seq[String] =
    _headerFragmentFuncs.map { f => f(base) }
    .flatten
    .toSeq
}

abstract class WidgetImp(wrapper: Widget) extends LazyModuleImp(wrapper) {
  val ctrlWidth = p(CtrlNastiKey).dataBits
  val crRegistry = new MCRFileMap(ctrlWidth / 8)
  def numRegs = crRegistry.numRegs

  def io: WidgetIO

  def numChunks(e: Bits): Int = ((e.getWidth + ctrlWidth - 1) / ctrlWidth)

  def attach(reg: Data, name: String, permissions: Permissions = ReadWrite, substruct: Boolean = true): Int = {
    crRegistry.allocate(RegisterEntry(reg, name, permissions, substruct))
  }

  // Recursively binds the IO of a module:
  //   For inputs, generates a registers and binds that to the map
  //   For outputs, direct binds the wire to the map
  def attachIO(io: Record, prefix: String = ""): Unit = {

    /**
      * For FASED memory timing models, initalize programmable registers to defaults if provided.
      * See [[midas.models.HasProgrammableRegisters]] for more detail.
      */
    def getInitValue(field: Bits, parent: Data): Option[UInt] = parent match {
      case p: midas.models.HasProgrammableRegisters if p.regMap.isDefinedAt(field) =>
        Some(p.regMap(field).default.U)
      case _ => None
    }

    def innerAttachIO(node: Data, parent: Data, name: String): Unit = node match {
      case (b: Bits) => (DataMirror.directionOf(b): @unchecked) match {
        case ActualDirection.Output => attach(b, s"${name}", ReadOnly, substruct = false)
        case ActualDirection.Input =>
          genAndAttachReg(b, name, getInitValue(b, parent), substruct = false)
      }
      case (v: Vec[_]) => {
        (v.zipWithIndex).foreach({ case (elm, idx) => innerAttachIO(elm, node, s"${name}_$idx")})
      }
      case (r: Record) => {
        r.elements.foreach({ case (subName, elm) => innerAttachIO(elm, node, s"${name}_${subName}")})
      }
      case _ => new RuntimeException("Cannot bind to this sort of node...")
    }
    io.elements.foreach({ case (name, elm) => innerAttachIO(elm, io, s"${prefix}${name}")})
  }


  def attachDecoupledSink(channel: DecoupledIO[UInt], name: String, substruct: Boolean = true): Int = {
    crRegistry.allocate(DecoupledSinkEntry(channel, name, substruct))
  }

  def attachDecoupledSource(channel: DecoupledIO[UInt], name: String, substruct: Boolean = true): Int = {
    crRegistry.allocate(DecoupledSourceEntry(channel, name, substruct))
  }

  def genAndAttachQueue(channel: DecoupledIO[UInt], name: String, depth: Int = 2): DecoupledIO[UInt] = {
    val enq = Wire(channel.cloneType)
    channel <> Queue(enq, entries = depth)
    attachDecoupledSink(enq, name)
    channel
  }

  def genAndAttachReg[T <: Data](
      wire: T,
      name: String,
      default: Option[T] = None,
      masterDriven: Boolean = true,
      substruct: Boolean = true): T = {
    require(wire.getWidth <= ctrlWidth)
    val reg = default match {
      case None => Reg(wire.cloneType)
      case Some(init) => RegInit(init)
    }
    if (masterDriven) wire := reg else reg := wire
    attach(reg, name, substruct = substruct)
    reg suggestName name
    reg
  }

  def genWOReg[T <: Data](wire: T, name: String, substruct: Boolean = true): T =
    genAndAttachReg(wire, name, substruct = substruct)
  def genROReg[T <: Data](wire: T, name: String, substruct: Boolean = true): T =
    genAndAttachReg(wire, name, masterDriven = false, substruct = substruct)

  def genWORegInit[T <: Data](wire: T, name: String, default: T): T =
    genAndAttachReg(wire, name, Some(default))
  def genRORegInit[T <: Data](wire: T, name: String, default: T): T =
    genAndAttachReg(wire, name, Some(default), false)


  def genWideRORegInit[T <: Bits](default: T, name: String, substruct: Boolean = true): T = {
    val reg = RegInit(default)
    val shadowReg = Reg(default.cloneType)
    shadowReg.suggestName(s"${name}_mmreg")
    val baseAddr = Seq.tabulate((default.getWidth + ctrlWidth - 1) / ctrlWidth)({ i =>
      val msb = math.min(ctrlWidth * (i + 1) - 1, default.getWidth - 1)
      val slice = shadowReg(msb, ctrlWidth * i)
      attach(slice, s"${name}_$i", ReadOnly, substruct)
    }).head
    // When a read request is made of the low order address snapshot the entire register
    val latchEnable = WireInit(false.B).suggestName(s"${name}_latchEnable")
    attach(latchEnable, s"${name}_latch", WriteOnly, substruct)
    when (latchEnable) {
      shadowReg := reg
    }
    reg
  }

  def genCRFile(): MCRFile = {
    val crFile = Module(new MCRFile(numRegs)(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
    crFile.io.mcr := DontCare
    crFile.io.nasti <> io.ctrl
    crRegistry.bindRegs(crFile.io.mcr)
    crFile
  }

  /** Emits a header snippet for this widget.
    * @param base
    *    The base address of the MMIO region allocated to the widget.
    * @param memoryRegions
    *    A mapping of names to allocated FPGA-DRAM regions. This is one mechanism
    *    for establishing side-channels between two otherwise unconnected bridges or widgets.
    */
  def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
    wrapper.headerComment(sb)
    crRegistry.genHeader(wrapper.getWName.toUpperCase, base, sb)
    crRegistry.genArrayHeader(wrapper.getWName.toUpperCase, base, sb)
    wrapper.getHeaderFragments(base).foreach { sb.append(_) }
  }
}

object Widget {
  private val widgetInstCount = mutable.HashMap[String, Int]().withDefaultValue(0)
  def assignName[T <: Widget](m: T): (String, Int) = {
    // Assign stable widget names by using the class name and suffix using the
    // number of other instances.
    // We could let the user specify this in their bridge --> we'd need to consider:
    // 1) Name collisions for embedded bridges
    // 2) We currently rely on having fixed widget names based on the class
    //    name, in the simulation driver.
    val widgetBasename = m.getClass.getSimpleName
    val idx = widgetInstCount(widgetBasename)
    widgetInstCount(widgetBasename) = idx + 1
    (widgetBasename + "_" + idx, idx)
  }
}

object WidgetRegion {
  def apply(start: BigInt, size: BigInt) = {
    require(isPow2(size))
    MemRange(start, size, MemAttr(AddrMapProt.RW))
  }
}

trait HasWidgets {
  private var _finalized = false
  private val widgets = mutable.ArrayBuffer[Widget]()
  private val name2inst = mutable.HashMap[String, Widget]()
  private lazy val addrMap = new AddrMap({
    val (_, entries) = (sortedWidgets foldLeft (BigInt(0), Seq[AddrMapEntry]())){
      case ((start, es), w) =>
        val name = w.getWName
        val size = w.memRegionSize
        (start + size, es :+ AddrMapEntry(name, WidgetRegion(start, size)))
    }
    entries
  })

  def addWidget[T <: Widget](m: => T): T = {
    val w = LazyModule(m)
    widgets += w
    name2inst += (w.getWName -> w)
    w
  }

  private def sortedWidgets = widgets.toSeq.sortWith(_.memRegionSize > _.memRegionSize)

  def genCtrlIO(master: WidgetMMIO)(implicit p: Parameters): Unit = {

    val lastWidgetRegion = addrMap.entries.last.region
    val widgetAddressMax = lastWidgetRegion.start + lastWidgetRegion.size

    require(log2Up(widgetAddressMax) <= p(CtrlNastiKey).addrBits,
      s"""| Widgets have allocated ${widgetAddressMax >> 2} MMIO Registers, requiring
          | ${widgetAddressMax} bytes of addressible register space.  The ctrl bus
          | is configured to only have ${p(CtrlNastiKey).addrBits} bits of address,
          | not the required ${log2Up(widgetAddressMax)} bits.""".stripMargin)

    val ctrlInterconnect = Module(new NastiRecursiveInterconnect(
      nMasters = 1,
      addrMap = addrMap
    )(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
    ctrlInterconnect.io.masters(0) <> master
    sortedWidgets.zip(ctrlInterconnect.io.slaves) foreach {
      case (w: Widget, m) => w.module.io.ctrl <> m
    }
  }

  def printMemoryMapSummary(): Unit = {
    println("Simulator Memory Map:")
    for (AddrMapEntry(name, MemRange(start, size, _)) <- addrMap.flatten) {
      println(f"  [${start}%4h, ${start + size - 1}%4h]: ${name}")
    }
  }

  /**
    * Iterates through each bridge, generating the header fragment. Must be
    * called after bridge address assignment is complete.
    */
  def genWidgetHeaders(sb: StringBuilder, memoryRegions: Map[String, BigInt]): Unit = {
    widgets foreach ((w: Widget) => w.module.genHeader(addrMap(w.getWName).start, memoryRegions, sb))
  }

  def printWidgets: Unit = {
    widgets foreach ((w: Widget) => println(w.getWName))
  }

  def getCRAddr(wName: String, crName: String)(implicit channelWidth: Int): BigInt = {
    val widget = name2inst.get(wName).getOrElse(
      throw new RuntimeException("Could not find Widget: $wName"))
    getCRAddr(widget, crName)
  }

  def getCRAddr(w: Widget, crName: String)(implicit channelWidth: Int): BigInt = {
    // TODO: Deal with byte vs word addresses && don't use a name in the hash?
    val base = (addrMap(w.getWName).start >> log2Up(channelWidth/8))
    base + w.getCRAddr(crName)
  }
}
