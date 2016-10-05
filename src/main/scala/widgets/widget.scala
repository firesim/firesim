package midas_widgets

import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}

import scala.collection.mutable.{HashMap, ArrayBuffer}

case object CtrlNastiKey extends Field[NastiParameters]

// Just NASTI, but pointing at the right key.
class WidgetMMIO(implicit p: Parameters) extends NastiIO()(p)
  with HasNastiParameters

object WidgetMMIO {
  def apply()(implicit p: Parameters): WidgetMMIO = {
    new WidgetMMIO()(p alter Map(NastiKey -> p(CtrlNastiKey)))
  }
}

// All widgets must implement this interface
abstract class WidgetIO(implicit p: Parameters) extends ParameterizedBundle()(p){
  val ctrl = Flipped(WidgetMMIO())
}

abstract class Widget(implicit p: Parameters) extends Module {
  private var _finalized = false
  private val crRegistry = new MCRFileMap()
  def numRegs = crRegistry.numRegs()

  override def io: WidgetIO

  val customSize: Option[BigInt] = None
  // Default case we set the region to be large enough to hold the CRs
  lazy val memRegionSize = customSize.getOrElse(BigInt(1 << log2Up(numRegs * (io.ctrl.nastiXDataBits/8))))
  var wName: Option[String] = None

  private def setWidgetName(n: String) {wName = Some(n)}
  def getWName(): String = {
    wName.getOrElse(throw new  RuntimeException("Must build widgets with their companion object"))
  }

  def attach(reg: Data, name: String) {
    crRegistry.allocate(reg, name)
  }

  def genAndAttachQueue(channel: DecoupledIO[UInt], name: String, depth: Int = 2): DecoupledIO[UInt] = {
    require(channel.bits.dir == OUTPUT)
    val enq = Wire(channel.cloneType)
    channel <> Queue(enq, entries = 2)
    crRegistry.allocate(enq, name)
    channel
  }

  def genAndAttachReg[T <: Bits](wire: T, default: T, name: String, masterDriven: Boolean = true): T = {
    require(wire.getWidth <= io.ctrl.nastiXDataBits)
    // TODO: More elegant way to do this?
    val reg = RegInit({val init = Wire(wire.cloneType); init := default; init})
    if (masterDriven) wire := reg else reg := wire
    attach(reg, name)
    reg
  }

  def genWOReg[T <: Bits](wire: T, default: T, name: String): T = genAndAttachReg(wire, default, name)
  def genROReg[T <: Bits](wire: T, default: T, name: String): T = genAndAttachReg(wire, default, name, false)

  def genCRFile() {
    val crFile = Module(new MCRFile(numRegs)(p alter Map(NastiKey -> p(CtrlNastiKey))))
    crFile.io.nasti <> io.ctrl
    crRegistry.bindRegs(crFile.io.mcr)
  }

  // Returns a word addresses
  def getCRAddr(name: String): Int = {
    require(_finalized, "Must build Widgets with their companion object")
    crRegistry.lookupAddress(name).getOrElse(
      throw new RuntimeException(s"Could not find CR:${name} in widget: $wName"))
  }

  def genHeader(base: BigInt, sb: StringBuilder){
    require(_finalized, "Must build Widgets with their companion object")
    crRegistry.genHeader(wName.getOrElse(name).toUpperCase, base, sb)
  }
}

// TODO: Need to handle names better; try and stick ctrl IO elaboration in here,
// instead of relying on the widget writer
object Widget {
  def apply[T <: Widget](m: =>T, wName: String): T = {
    val w = Module(m)
    w suggestName wName
    w setWidgetName wName
    w._finalized = true
    w
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
  val widgets = ArrayBuffer[Widget]()
  val name2inst = HashMap[String, Widget]()
  private lazy val addrMap = new AddrMap({
    val (_, entries) = (sortedWidgets foldLeft (BigInt(0), Seq[AddrMapEntry]())){
      case ((start, es), w) =>
        val name = w.getWName
        val size = w.memRegionSize
        (start + size, es :+ AddrMapEntry(name, WidgetRegion(start, size)))
    }
    entries
  })

  def addWidget[T <: Widget](m: =>T, wName: String): T = {
    val w = Widget(m, wName)
    assert(!name2inst.contains(wName), "Widget name: $wName already allocated")
    widgets += w
    name2inst += (wName -> w)
    w
  }

  private def sortedWidgets = widgets.toSeq.sortWith(_.memRegionSize > _.memRegionSize)

  def genCtrlIO(master: WidgetMMIO, addrSize: BigInt)(implicit p: Parameters) {
    val ctrlInterconnect = Module(new NastiRecursiveInterconnect(
      nMasters = 1,
      addrMap = addrMap
    )(p alter Map(NastiKey -> p(CtrlNastiKey))))
    ctrlInterconnect.io.masters(0) <> master
    // We should truncate upper bits of master addresses
    // according to the size of flatform MMIO
    val addrSizeBits = log2Up(addrSize)
    ctrlInterconnect.io.masters(0).aw.bits.addr := master.aw.bits.addr(addrSizeBits, 0)
    ctrlInterconnect.io.masters(0).ar.bits.addr := master.ar.bits.addr(addrSizeBits, 0)
    sortedWidgets.zip(ctrlInterconnect.io.slaves) foreach {
      case (w: Widget, m) => w.io.ctrl <> m
    }
  }
  def genHeader(sb: StringBuilder)(implicit channelWidth: Int) {
    widgets foreach ((w: Widget) => w.genHeader(addrMap(w.getWName).start >> log2Up(channelWidth/8), sb))
  }

  def printWidgets(){
    widgets foreach ((w: Widget) => println(w.wName))
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
