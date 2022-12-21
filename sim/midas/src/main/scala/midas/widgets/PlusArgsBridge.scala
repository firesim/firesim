package midas.widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._
import midas.targetutils._
import freechips.rocketchip.util.DecoupledHelper

/** Defines a PlusArgs interface. The signature here was copied from rocket-chip/src/main/scala/util/PlusArg.scala
  *
  * @param name
  *   string identifier, should include "name=%d"
  *
  * @param default
  *   The value of the register if no matching PlusArg is provided
  *
  * @param docstring
  *   The doctring
  *
  * @param width
  *   The width of the register, in bits
  */
case class PlusArgsBridgeParams(
  name:      String,
  default:   BigInt = 0,
  docstring: String = "",
  width:     Int    = 32,
) {
  require(width > 0, "Width must be larger than zero")
  require(
    default.bitLength <= width,
    s"The default value provided '${default}' is too large to fit in ${width} bits",
  )
  require(
    name contains "=%d",
    s"name passed to PlusArgsBridge (${name}) must contain =%d. Currently only the format %d is supported",
  )
}

/** The target IO. This drives the value (default, or overriden) that comes out of the PlusArgs bridge
  *
  * @param params
  *   Describes the name, width and default PlusArg value
  */
class PlusArgsBridgeTargetIO(params: PlusArgsBridgeParams) extends Bundle {
  val clock = Input(Clock())
  val out   = Output(UInt((params.width).W))
}

/** The host-side interface. This bridge has single channel with the PlusArg value.
  *
  * @param targetIO
  *   A reference to the bound target-side interface.
  *
  * @param params
  *   Describes the name, width and default PlusArg value
  */
class PlusArgsBridgeHostIO(
  params:               PlusArgsBridgeParams
)(private val targetIO: PlusArgsBridgeTargetIO = new PlusArgsBridgeTargetIO(params)
) extends Bundle
    with ChannelizedHostPortIO {
  def targetClockRef = targetIO.clock
  val outChannel     = OutputChannel(targetIO.out)
}

/** The target-side of the PlusArg bridge.
  *
  * @param params
  *   Describes the name, width and default PlusArg value
  */
class PlusArgsBridge(params: PlusArgsBridgeParams)
    extends BlackBox
    with Bridge[PlusArgsBridgeHostIO, PlusArgsBridgeModule] {
  val io       = IO(new PlusArgsBridgeTargetIO(params))
  val bridgeIO = new PlusArgsBridgeHostIO(params)(io)

  val constructorArg = Some(params)

  generateAnnotations()
}

object PlusArgsBridge {

  /** Helper for creating the bridge. All parameter requirements are checked here
    *
    * @param clock
    *   The clock
    *
    * @param reset
    *   The reset
    *
    * @param params
    *   Describes the name, width and default PlusArg value
    */
  private def annotatePlusArgsBridge(clock: Clock, reset: Reset, params: PlusArgsBridgeParams): PlusArgsBridge = {
    val target = Module(new PlusArgsBridge(params))
    target.io.clock := clock
    target
  }

  /** apply overload which takes the parameters without the case class The signature was copied from
    * rocket-chip/src/main/scala/util/PlusArg.scala
    *
    * @param name
    *   string identifier, should include "name=%d"
    *
    * @param default
    *   The value of the register if no matching PlusArg is provided
    *
    * @param docstring
    *   The doctring
    *
    * @param width
    *   The width of the register, in bits
    */
  // Signature copied from rocket-chip/src/main/scala/util/PlusArg.scala
  def apply(name: String, default: BigInt = 0, docstring: String = "", width: Int = 32): PlusArgsBridge = {
    val params = PlusArgsBridgeParams(name, default, docstring, width)
    annotatePlusArgsBridge(Module.clock, Module.reset, params)
  }

  /** apply overload which takes the parameters case class
    *
    * @param params
    *   Describes the name, width and default PlusArg value
    */
  def apply(params: PlusArgsBridgeParams): PlusArgsBridge = {
    annotatePlusArgsBridge(Module.clock, Module.reset, params)
  }

  /** similar to apply, but named drive. This overload returns the out value. This overload is probably the one you want
    *
    * @param params
    *   Describes the name, width and default PlusArg value
    */
  def drive(params: PlusArgsBridgeParams): UInt = {
    val target = annotatePlusArgsBridge(Module.clock, Module.reset, params)
    target.io.out
  }
}

/** The host-side implementation. Calculates widths and bundles multiple 32 bit MMIO to the exact width of
  * `params.width`. If `params.width` does not evenly divide by 32 a MMIO with the remainder bits will be created. The
  * remainders name will always be `outN` where `N` is the last MMIO
  *
  * `initDone` is used as valid for the outChannel. The C++ driver ensures that all `outN` are driven before `initDone`
  * is asserted
  *
  * @param params
  *   Describes the name, width and default PlusArg value
  */
class PlusArgsBridgeModule(params: PlusArgsBridgeParams)(implicit p: Parameters)
    extends BridgeModule[PlusArgsBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {

    val io    = IO(new WidgetIO())
    val hPort = IO(new PlusArgsBridgeHostIO(params)())

    // divide with a ceiling round, to get the total number of slices
    val sliceCount = (params.width + ctrlWidth - 1) / ctrlWidth

    // create a seq of widths of each slice
    val slicesWidths = Seq.tabulate(sliceCount)(x => math.min(params.width - (x * ctrlWidth), ctrlWidth))

    // zip/map the widths to call genWOReg
    // reg names are out0, out1, ...
    val slices = slicesWidths.zipWithIndex.map { case (width, idx) =>
      genWOReg(Wire(UInt(width.W)), s"out${idx}")
    }

    // glue the slices together to get a single wide register
    val plusArgValue = Cat(slices.reverse)

    // valid bit for the outChannel
    val initDone = genWOReg(Wire(Bool()), "initDone")

    hPort.outChannel.valid := initDone
    hPort.outChannel.bits  := plusArgValue

    val plusArgValueNext = RegNext(plusArgValue)

    // assert that the value never changes after initDone is set
    when(initDone === true.B) {
      assert(plusArgValueNext === plusArgValue)
    }

    override def genHeader(base: BigInt, sb: StringBuilder) {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, sb)
      sb.append(genStatic(s"${headerWidgetName}_name", CStrLit(params.name)))
      sb.append(genStatic(s"${headerWidgetName}_default", CStrLit(s"${params.default}")))
      sb.append(genStatic(s"${headerWidgetName}_docstring", CStrLit(params.docstring)))
      sb.append(genConstStatic(s"${headerWidgetName}_width", UInt32(params.width)))
      sb.append(genConstStatic(s"${headerWidgetName}_slice_count", UInt32(slices.length)))
      // val foo = 34 / 0

      // call getCRAddr to get the registers by name, and then build this C style array
      sb.append(
        genArray(
          s"${headerWidgetName}_slice_addrs",
          Seq.tabulate(sliceCount)(x => UInt32(base + getCRAddr(s"out${x}"))),
        )
      )
    }
    genCRFile()
  }
}
