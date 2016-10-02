package midas_widgets

import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}

import scala.collection.mutable.{HashMap, ArrayBuffer}

case object CtrlNastiKey extends Field[NastiParameters]

// Just NASTI, but pointing at the right key.
class WidgetMMIO(implicit p: Parameters) extends NastiIO()(p alter Map(NastiKey -> p(CtrlNastiKey)))

// All widgets must implement this interface
abstract class WidgetIO(implicit p: Parameters) extends ParameterizedBundle()(p){
  val ctrl = Flipped(new WidgetMMIO)
}

abstract class Widget(implicit p: Parameters) extends NastiModule()(p) {
  private var _finalized = false
  private val crRegistry = new MCRFileMap()
  def numRegs = crRegistry.numRegs()

  override def io: WidgetIO

  val customSize: Option[BigInt] = None
  // Default case we set the region to be large enough to hold the CRs
  lazy val memRegionSize = customSize.getOrElse(BigInt(1 << log2Up(numRegs * (nastiXDataBits/8))))
  var wName: Option[String] = None

  private def setWidgetName(n: String) {wName = Some(n)}
  def getWName(): String = {
    wName.getOrElse(throw new  RuntimeException("Must build widgets with their companion object"))
  }

  def attach(reg: Data, name: String) {
    require(reg.getWidth <= nastiXDataBits)
    crRegistry.allocate(reg, name)
  }

  def genAndAttachReg(wire: UInt, default: Int, name: String): UInt = {
    require(wire.dir == INPUT || wire.dir == OUTPUT)
    val reg = Reg(wire.cloneType, init = UInt(default))
    if (wire.dir == OUTPUT) reg := wire else wire := reg
    attach(reg, name)
    reg
  }

  def genCRFile() {
    val crFile = Module(new MCRFile(numRegs)(p alter Map(NastiKey -> p(CtrlNastiKey))))
    crFile.io.nasti <> io.ctrl
    crRegistry.bindRegs(crFile.io.mcr)
  }

  // Returns a word addresses
  def getCRAddr(name: String): Int = {
    require(_finalized == true, "Must build Widgets with their companion object")
    crRegistry.lookupAddress(name).getOrElse(
      throw new RuntimeException(s"Could not find CR:${name} in widget: $wName"))
  }

  def genHeader(base: BigInt, sb: StringBuilder){
    require(_finalized == true, "Must build Widgets with their companion object")
    crRegistry.genHeader(wName.getOrElse(name), base, sb)
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

  def genCtrlIO(master: WidgetMMIO, baseAddress: BigInt = 0)(implicit p: Parameters) {
    val ctrlInterconnect = Module(new NastiRecursiveInterconnect(
      nMasters = 1,
      addrMap = addrMap
    )(p alter Map(NastiKey -> p(CtrlNastiKey))))

    ctrlInterconnect.io.masters(0) <> master
    sortedWidgets.zip(ctrlInterconnect.io.slaves) foreach {
      case (w: Widget, m) => w.io.ctrl <> m
    }
  }

  def genHeader(sb: StringBuilder) {
    widgets foreach ((w: Widget) => w.genHeader(addrMap(w.getWName).start, sb))
  }

  def printWidgets(){
    widgets foreach ((w: Widget) => println(w.wName))
  }

  def getCRAddr(wName: String, crName: String): BigInt = {
    val widget = name2inst.get(wName).getOrElse(
      throw new RuntimeException("Could not find Widget: $wName"))
    getCRAddr(widget, crName)
  }

  def getCRAddr(w: Widget, crName: String): BigInt = {
    // TODO: Deal with byte vs word addresses && don't use a name in the hash?
    val base = (addrMap(w.getWName).start >> 2)
    base + w.getCRAddr(crName)
  }
}
