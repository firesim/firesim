package midas.widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._
import midas.targetutils._
import freechips.rocketchip.util.DecoupledHelper

/** Defines a plusargs interface. The signature here was copied from
  * chipyard/generators/rocket-chip/src/main/scala/util/PlusArg.scala
  *
  * @param name
  *   string identifier, should include "name=%d"
  *
  * @param default
  *   The value of the register if no matching plusarg is provided
  *
  * @param docstring
  *   The doctring
  *
  * @param width
  *   The width of the register, in bits
  */
case class PlusargsBridgeParams(
  name:      String,
  default:   BigInt = 0,
  docstring: String = "",
  width:     Int    = 32,
)

/** The target IO. This drives the value (default, or overriden) that comes out of the plusarg bridge
  *
  * @param params
  *   Describes the name, width and default plusarg value
  */
class PlusargsBridgeTargetIO(params: PlusargsBridgeParams) extends Bundle {
  val clock = Input(Clock())
  val out   = Output(UInt((params.width).W))
}

/** The host-side interface. This bridge has single channel with the plusarg value.
  *
  * @param targetIO
  *   A reference to the bound target-side interface.
  *
  * @param params
  *   Describes the name, width and default plusarg value
  */
class PlusargsBridgeHostIO(
  params:               PlusargsBridgeParams
)(private val targetIO: PlusargsBridgeTargetIO = new PlusargsBridgeTargetIO(params)
) extends Bundle
    with ChannelizedHostPortIO {
  def targetClockRef = targetIO.clock
  val outChannel     = OutputChannel(targetIO.out)
}

/** The plusarg bridge.
  *
  * @param params
  *   Describes the name, width and default plusarg value
  */
class PlusargsBridge(params: PlusargsBridgeParams)
    extends BlackBox
    with Bridge[PlusargsBridgeHostIO, PlusargsBridgeModule] {
  val io       = IO(new PlusargsBridgeTargetIO(params))
  val bridgeIO = new PlusargsBridgeHostIO(params)(io)

  val constructorArg = Some(params)

  generateAnnotations()
}

object PlusargsBridge {

  /** Helper for creating the bridge. All parameter requirements are checked here
    *
    * @param clock
    *   The clock
    *
    * @param reset
    *   The reset
    *
    * @param params
    *   Describes the name, width and default plusarg value
    */
  private def annotatePlusargsBridge(clock: Clock, reset: Reset, params: PlusargsBridgeParams): PlusargsBridge = {
    require(params.width > 0, "Width must be larger than zero")
    require(
      params.default.bitLength <= params.width,
      s"The default value provided '${params.default}' is too large to fit in ${params.width} bits",
    )
    require(
      params.name contains "=%d",
      s"name passed to PlusargsBridge (${params.name}) must contain =%d. Currently only the format %d is supported",
    )

    val target = Module(new PlusargsBridge(params))
    target.io.clock := clock
    target
  }

  /** apply overload which takes the parameters without the case class The signature was copied from
    * chipyard/generators/rocket-chip/src/main/scala/util/PlusArg.scala
    *
    * @param name
    *   string identifier, should include "name=%d"
    *
    * @param default
    *   The value of the register if no matching plusarg is provided
    *
    * @param docstring
    *   The doctring
    *
    * @param width
    *   The width of the register, in bits
    */
  // Signature copied from chipyard/generators/rocket-chip/src/main/scala/util/PlusArg.scala
  def apply(name: String, default: BigInt = 0, docstring: String = "", width: Int = 32): PlusargsBridge = {
    val params = PlusargsBridgeParams(name, default, docstring, width)
    annotatePlusargsBridge(Module.clock, Module.reset, params)
  }

  /** apply overload which takes the parameters case class
    *
    * @param params
    *   Describes the name, width and default plusarg value
    */
  def apply(params: PlusargsBridgeParams): PlusargsBridge = {
    annotatePlusargsBridge(Module.clock, Module.reset, params)
  }

  /** similar to apply, but named drive. This overload returns the out value. This overload is probably the one you want
    *
    * @param params
    *   Describes the name, width and default plusarg value
    */
  def drive(params: PlusargsBridgeParams): UInt = {
    val target = annotatePlusargsBridge(Module.clock, Module.reset, params)
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
  *   Describes the name, width and default plusarg value
  */
class PlusargsBridgeModule(params: PlusargsBridgeParams)(implicit p: Parameters)
    extends BridgeModule[PlusargsBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {

    val io    = IO(new WidgetIO())
    val hPort = IO(new PlusargsBridgeHostIO(params)())

    // divide with a ceiling round, to get the total number of slices
    val sliceCount = (params.width + ctrlWidth - 1) / ctrlWidth;

    // create a seq of widths of each slice
    val slicesWidths = (0 until sliceCount).map(x => math.min(params.width - (x * ctrlWidth), ctrlWidth))

    // zip/map the widths to call genWOReg
    // reg names are out0, out1, ...
    val slices = slicesWidths.zipWithIndex.map({ case (width, idx) =>
      genWOReg(Wire(UInt(width.W)), s"out${idx}")
    })

    // glue the slices together to get a single wide register
    val plusargValue = Cat(slices.reverse)

    // valid bit for the outChannel
    val initDone = genWOReg(Wire(Bool()), "initDone")

    hPort.outChannel.valid := initDone
    hPort.outChannel.bits  := plusargValue

    val plusargValueNext = RegNext(plusargValue)

    // assert that the value never changes after initDone is set
    when(initDone === true.B) {
      assert(plusargValueNext === plusargValue)
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

      // call getCRAddr to get the registers by name, and then build this C style array
      sb.append(
        genArray(
          s"${headerWidgetName}_slice_addrs",
          (0 until sliceCount).map(x => UInt32(base + getCRAddr(s"out${x}"))),
        )
      )
    }
    genCRFile()
  }
}
