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
  override def module: WidgetImp
  val (wName, wId) = Widget.assignName(this)
  this.suggestName(wName)

  def getWName = wName

  // Returns widget-relative word address
  def getCRAddr(name: String): Int = {
    module.crRegistry.lookupAddress(name).getOrElse(
      throw new RuntimeException(s"Could not find CR:${name} in widget: $wName"))
  }

  def headerComment(sb: StringBuilder) {
    val name = getWName.toUpperCase
    sb.append("\n// Widget: %s\n".format(getWName))
    sb.append(CppGenerationUtils.genMacro(s"${name}(x)", s"${name}_ ## x"))
  }

  val customSize: Option[BigInt] = None
  def memRegionSize = customSize.getOrElse(BigInt(1 << log2Up(module.numRegs * (module.io.ctrl.nastiXDataBits/8))))

  def printCRs = module.crRegistry.printCRs
}

abstract class WidgetImp(wrapper: Widget) extends LazyModuleImp(wrapper) {
  val crRegistry = new MCRFileMap()
  def numRegs = crRegistry.numRegs

  def io: WidgetIO

  // Default case we set the region to be large enough to hold the CRs
  lazy val ctrlWidth = io.ctrl.nastiXDataBits
  def numChunks(e: Bits): Int = ((e.getWidth + ctrlWidth - 1) / ctrlWidth)

  def attach(reg: Data, name: String, permissions: Permissions = ReadWrite): Int = {
    crRegistry.allocate(RegisterEntry(reg, name, permissions))
  }

  // Recursively binds the IO of a module:
  //   For inputs, generates a registers and binds that to the map
  //   For outputs, direct binds the wire to the map
  def attachIO(io: Record, prefix: String = ""): Unit = {
    def innerAttachIO(node: Data, name: String): Unit = node match {
      case (b: Bits) => (DataMirror.directionOf(b): @unchecked) match {
        case ActualDirection.Output => attach(b, s"${name}", ReadOnly)
        case ActualDirection.Input => genWOReg(b, name)
      }
      case (v: Vec[_]) => {
        (v.zipWithIndex).foreach({ case (elm, idx) => innerAttachIO(elm, s"${name}_$idx")})
      }
      case (r: Record) => {
        r.elements.foreach({ case (subName, elm) => innerAttachIO(elm, s"${name}_${subName}")})
      }
      case _ => new RuntimeException("Cannot bind to this sort of node...")
    }
    io.elements.foreach({ case (name, elm) => innerAttachIO(elm, s"${prefix}${name}")})
  }

  def attachDecoupledSink(channel: DecoupledIO[UInt], name: String): Int = {
    crRegistry.allocate(DecoupledSinkEntry(channel, name))
  }

  def attachDecoupledSource(channel: DecoupledIO[UInt], name: String): Int = {
    crRegistry.allocate(DecoupledSourceEntry(channel, name))
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
      masterDriven: Boolean = true): T = {
    require(wire.getWidth <= io.ctrl.nastiXDataBits)
    val reg = default match {
      case None => Reg(wire.cloneType)
      case Some(init) => RegInit(init)
    }
    if (masterDriven) wire := reg else reg := wire
    attach(reg, name)
    reg suggestName name
    reg
  }

  def genWOReg[T <: Data](wire: T, name: String): T = genAndAttachReg(wire, name)
  def genROReg[T <: Data](wire: T, name: String): T = genAndAttachReg(wire, name, masterDriven = false)

  def genWORegInit[T <: Data](wire: T, name: String, default: T): T =
    genAndAttachReg(wire, name, Some(default))
  def genRORegInit[T <: Data](wire: T, name: String, default: T): T =
    genAndAttachReg(wire, name, Some(default), false)


  def genWideRORegInit[T <: Bits](default: T, name: String): T = {
    val cW = io.ctrl.nastiXDataBits
    val reg = RegInit(default)
    val shadowReg = Reg(default.cloneType)
    shadowReg.suggestName(s"${name}_mmreg")
    val baseAddr = Seq.tabulate((default.getWidth + cW - 1) / cW)({ i =>
      val msb = math.min(cW * (i + 1) - 1, default.getWidth - 1)
      val slice = shadowReg(msb, cW * i)
      attach(slice, s"${name}_$i", ReadOnly)
    }).head
    // When a read request is made of the low order address snapshot the entire register
    val latchEnable = WireInit(false.B).suggestName(s"${name}_latchEnable")
    attach(latchEnable, s"${name}_latch", WriteOnly)
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

  def genHeader(base: BigInt, sb: StringBuilder){
    wrapper.headerComment(sb)
    crRegistry.genHeader(wrapper.getWName.toUpperCase, base, sb)
    crRegistry.genArrayHeader(wrapper.getWName.toUpperCase, base, sb)
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

  def genCtrlIO(master: WidgetMMIO)(implicit p: Parameters) {

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

  def genHeader(sb: StringBuilder) {
    // Converts byte addresses to AXI4-lite (i.e., 32-bit wide) word addresses
    widgets foreach ((w: Widget) => w.module.genHeader(addrMap(w.getWName).start >> 2, sb))
  }

  def printWidgets {
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
