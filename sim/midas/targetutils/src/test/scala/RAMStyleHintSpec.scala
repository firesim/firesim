// See LICENSE for license details.

package midas.targetutils

import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec

import chisel3._
import firrtl.annotations.ModuleTarget

class RAMStyleHintSpec extends AnyFlatSpec with ElaborationUtils {
  import midas.targetutils.xdc._
  class MemoryModuleIO(addrBits: Int, dataBits: Int) extends Bundle {
    val readAddress  = Input(UInt(addrBits.W))
    val readEnable   = Input(Bool())
    val readData     = Output(UInt(dataBits.W))

    val writeAddress = Input(UInt(addrBits.W))
    val writeData    = Input(UInt(dataBits.W))
    val writeEnable  = Input(Bool())
  }

  abstract class BaseMemoryModule(style: Option[RAMStyle]) extends Module {
    // Arbitrarily selected.
    val dataBits = 64
    val addrBits = 16
    val io = IO(new MemoryModuleIO(addrBits, dataBits))

    def mem: MemBase[_]
    style.foreach { RAMStyleHint(mem, _) }

    def expectedAnnotation: XDCAnnotation =
      XDCAnnotation(
        XDCFiles.Synthesis,
        RAMStyleHint.propertyTemplate(style.get),
        ModuleTarget(this.instanceName, this.instanceName).ref(mem.instanceName)
      )
  }


  class SyncReadMemModule(style: Option[RAMStyle]) extends BaseMemoryModule(style) {
    // Lazy so this is elaborated  before annotator executes
    lazy val mem = SyncReadMem(1 << addrBits, UInt(dataBits.W))
    io.readData := mem.read(io.readAddress, io.readEnable)
    when(io.writeEnable) { mem(io.writeAddress) := io.writeData }
  }

  // This uses a combinational-read chisel memory, but could be infered as a BRAM
  // due to the pipelining of the data read out. So the check annotator still accepts these.
  class CombReadMemModule(style: Option[RAMStyle]) extends BaseMemoryModule(style) {
    lazy val mem = Mem(1 << addrBits, UInt(dataBits.W))
    when(io.readEnable)  { io.readData          := RegNext(mem(io.readAddress)) }
    when(io.writeEnable) { mem(io.writeAddress) := io.writeData }
  }

  def checkSingleTargetModule(gen: =>BaseMemoryModule): Unit = {
    // Lazy, so that we may introspect on the elaborated module class
    lazy val mod = gen
    val annos = elaborate(mod)._2.collect { case a: XDCAnnotation => a }
    assert(annos.size == 1)
    assert(annos.head == mod.expectedAnnotation)
  }

  behavior of "RAMStyleHint"

  // Sanity check that stuff passes through elaboration. 
  it should "correctly annotate a chisel3.SyncReadMem as BRAM" in {
    checkSingleTargetModule(new SyncReadMemModule(Some(RAMStyles.BRAM)))
  }

  it should "correctly annotate a chisel3.SyncReadMem as URAM" in {
    checkSingleTargetModule(new SyncReadMemModule(Some(RAMStyles.ULTRA)))
  }

  it should "correctly annotate a chisel3.Mem as URAM" in {
    checkSingleTargetModule(new CombReadMemModule(Some(RAMStyles.ULTRA)))
  }

  it should "correctly annotate a chisel3.Mem as BRAM" in {
    checkSingleTargetModule(new CombReadMemModule(Some(RAMStyles.BRAM)))
  }

  class WrapperModule extends Module {
    val a = IO(new MemoryModuleIO(64, 16))
    val b = IO(new MemoryModuleIO(64, 16))

//DOC include start: RAM Hint From Parent
    val modA = Module(new SyncReadMemModule(None))
    val modB = Module(new SyncReadMemModule(None))
    RAMStyleHint(modA.mem, RAMStyles.ULTRA)
    RAMStyleHint(modB.mem, RAMStyles.BRAM)
//DOC include end: RAM Hint From Parent
    modA.io <> a
    modB.io <> b

    private def expectedAnnotation(mod: SyncReadMemModule, style: RAMStyle) = XDCAnnotation(
        XDCFiles.Synthesis,
        RAMStyleHint.propertyTemplate(style),
        ModuleTarget(this.instanceName, this.instanceName).instOf(mod.instanceName, mod.getClass().getSimpleName()).ref(mod.mem.instanceName)
    )
    def expectedAnnos = Seq(
      expectedAnnotation(modA, RAMStyles.ULTRA),
      expectedAnnotation(modB, RAMStyles.BRAM),
    )
  }

  it should "not trivially break deduplication" in {
    lazy val mod = new WrapperModule()
    val annos = elaborateAndLower(mod)

    val dedupResultAnnos = annos.collect { case a: firrtl.transforms.DedupedResult => a }
    assert(dedupResultAnnos.size == 2)

    val xdcAnnos = annos.collect { case a: XDCAnnotation => a }
    assert(xdcAnnos.size == 2)
    mod.expectedAnnos.foreach {
      anno => assert(xdcAnnos.contains(anno))
    }
  }

  behavior of "Simple RAMStyleHint Demo Module"

  it should "elaborate" in {
    class MemoryModule extends Module {
    val dataBits = 64
    val addrBits = 16
    val io = IO(new MemoryModuleIO(addrBits, dataBits))

//DOC include start: Basic RAM Hint
    import midas.targetutils.xdc._
    val mem = SyncReadMem(1 << addrBits, UInt(dataBits.W))
    RAMStyleHint(mem, RAMStyles.ULTRA)
    // Alternatively: RAMStyleHint(mem, RAMStyles.BRAM)
//DOC include end: Basic RAM Hint

    io.readData := mem.read(io.readAddress, io.readEnable)
    when(io.writeEnable) { mem(io.writeAddress) := io.writeData }
  }
  val annos = elaborateAndLower(new MemoryModule)
  assert(annos.size == 1)
  }
}
