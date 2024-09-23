package midas.passes.partition

import scala.collection.mutable
import scala.Console.println
import firrtl._
import firrtl.ir._
import firrtl.annotations.ModuleTarget
import midas.widgets._
import firesim.lib.bridgeutils.{BridgeAnnotation, PipeBridgeChannel}

trait CutBridgePass {
  protected val outputPipeChannelLatency: Int = 0
  protected val inputPipeChannelLatency: Int  = 0
  protected def flipDir(curDir: String): String = {
    if (curDir == "input") "output" else "input"
  }

  val DMA_BITWIDTH = 512
}

case class CutBridgeInfo(
  inst:         DefInstance,
  mod:          ExtModule,
  anno:         BridgeAnnotation,
  inPorts:      Seq[Expression],
  inPortsBits:  Seq[Int],
  outPorts:     Seq[Expression],
  outPortsBits: Seq[Int],
)

// Collection of functions to analayze the parition boundary and generate a CutBoundaryBridge
trait GenCutBridgePass extends CutBridgePass {

  private var idx           = 0
  private var cutBridgeName = s"CutBoundaryBridge_${idx}"

  // variables holding information about the wires going "in" to the Module that we are splitting out
  protected val inPorts              = mutable.ArrayBuffer[Expression]()
  protected val inPortsBits          = mutable.ArrayBuffer[Int]()
  protected var inPortsWidth: BigInt = 0

  // variables holding information about the wires goint "out" of the Module that we are splitting out
  protected val outPorts              = mutable.ArrayBuffer[Expression]()
  protected val outPortsBits          = mutable.ArrayBuffer[Int]()
  protected var outPortsWidth: BigInt = 0

  private def collectDirectionIO(p: Type, names: Expression, curDir: String, collectDir: String): Unit = {
    p match {
      case firrtl.ir.UIntType(w) =>
        val width = w.asInstanceOf[IntWidth].width
        if (curDir == collectDir) {
          if (collectDir == "input") {
            inPorts.append(names)
            inPortsBits.append(width.intValue)
            inPortsWidth = inPortsWidth + width
          } else {
            outPorts.append(names)
            outPortsBits.append(width.intValue)
            outPortsWidth = outPortsWidth + width
          }
        }
      case firrtl.ir.ResetType   =>
        val width = BigInt(1)
        if (curDir == collectDir) {
          if (collectDir == "input") {
            inPorts.append(names)
            inPortsBits.append(width.intValue)
            inPortsWidth = inPortsWidth + width
          } else {
            outPorts.append(names)
            outPortsBits.append(width.intValue)
            outPortsWidth = outPortsWidth + width
          }
        }
      case BundleType(fields)    =>
        fields.map { field =>
          val nextDir = field.flip match {
            case Default => curDir
            case Flip    => flipDir(curDir)
          }
          collectDirectionIO(field.tpe, WSubField(names, field.name, field.tpe), nextDir, collectDir)
        }
      case _                     => ()
    }
  }

  private def getBridgeInPortWidth(inModule: Boolean): BigInt = {
    if (inModule) outPortsWidth else inPortsWidth
  }

  private def getBridgeOutPortWidth(inModule: Boolean): BigInt = {
    if (inModule) inPortsWidth else outPortsWidth
  }

  private def generateCutBridgeInstanceAndModule(
    bridgeInstanceName: String,
    ports:              Seq[Port],
    inModule:           Boolean = true,
  ): (DefInstance, ExtModule) = {
    // Collect all the input and output ports of the cut
    // Exclude the implicit clock signal
    val inoutPorts = ports.filter { p =>
      p.name != "clock"
    }

    inoutPorts.foreach { p => collectDirectionIO(p.tpe, WRef(p.name), p.direction.serialize, "input") }
    inoutPorts.foreach { p => collectDirectionIO(p.tpe, WRef(p.name), p.direction.serialize, "output") }

// println("inoutPorts")
// inoutPorts.foreach { println(_) }

    println(s"inPorts ${inPortsWidth}")
// inPorts.foreach { println(_) }

    println(s"outPorts ${outPortsWidth}")
// outPorts.foreach { println(_) }

    // instantiate the CutBridge
    val bridgePortClock = Port(NoInfo, "clock", Input, ClockType)
    val bridgePortReset = Port(NoInfo, "reset", Input, ResetType)
    val bridgePortIn    = Port(NoInfo, "io_in", Output, firrtl.ir.UIntType(IntWidth(getBridgeInPortWidth(inModule))))
    val bridgePortOut   = Port(NoInfo, "io_out", Input, firrtl.ir.UIntType(IntWidth(getBridgeOutPortWidth(inModule))))
    val bridgePorts     = Seq(bridgePortClock, bridgePortReset, bridgePortIn, bridgePortOut)

    val bridgeModule         =
      ExtModule(info = NoInfo, name = cutBridgeName, ports = bridgePorts, defname = cutBridgeName, params = Seq())
    val bridgeModuleInstance = DefInstance(name = bridgeInstanceName, module = cutBridgeName)
    (bridgeModuleInstance, bridgeModule)
  }

  private def generateCutBridgeAnnotation(
    circuitMain:     String,
    cutBridgeModule: String,
    DMA_BITWIDTH:    Int,
    inModule:        Boolean = true,
  ): BridgeAnnotation = {
    val cutBridgeModuleTarget = ModuleTarget(circuitMain, cutBridgeName)
    val bridgePortInWidth     = getBridgeInPortWidth(inModule).intValue
    val dmaInWidth            = bridgePortInWidth.min(DMA_BITWIDTH)
    val bridgePortOutWidth    = getBridgeOutPortWidth(inModule).intValue
    val dmaOutWidth           = bridgePortOutWidth.min(DMA_BITWIDTH)

    val cutBridgeAnno = BridgeAnnotation(
      target               = cutBridgeModuleTarget,
      bridgeChannels       = Seq(
        PipeBridgeChannel(
          name    = "in",
          clock   = cutBridgeModuleTarget.ref("clock"),
          sinks   = Seq(cutBridgeModuleTarget.ref("io_in")),
          sources = Seq(),
          latency = inputPipeChannelLatency,
        ),
        PipeBridgeChannel(
          name    = "out",
          clock   = cutBridgeModuleTarget.ref("clock"),
          sinks   = Seq(),
          sources = Seq(cutBridgeModuleTarget.ref("io_out")),
          latency = outputPipeChannelLatency,
        ),
        PipeBridgeChannel(
          name    = "reset",
          clock   = cutBridgeModuleTarget.ref("clock"),
          sinks   = Seq(),
          sources = Seq(cutBridgeModuleTarget.ref("reset")),
          latency = outputPipeChannelLatency,
        ),
      ),
      widgetClass          = cutBridgeModule,
      widgetConstructorKey =
        Some(CutBoundaryKey(CutBoundaryParams(bridgePortInWidth, dmaInWidth, bridgePortOutWidth, dmaOutWidth))),
    )
    cutBridgeAnno
  }

  private def initGenCutBridge(): Unit = {
    inPorts.clear()
    inPortsBits.clear()
    inPortsWidth  = 0
    outPorts.clear()
    outPortsBits.clear()
    outPortsWidth = 0
    idx += 1
    cutBridgeName = s"CutBoundaryBridge_${idx}"
  }

  def generateCutBridge(
    circuitMain:        String,
    bridgeInstanceName: String,
    ports:              Seq[Port],
    inModule:           Boolean = true,
    bridgeType:         String  = "PCIS",
  ): CutBridgeInfo = {

    val cutBridgeModule = s"midas.widgets.${bridgeType}CutBoundaryBridgeModule"

    val (inst, module) = generateCutBridgeInstanceAndModule(bridgeInstanceName, ports, inModule)
    val anno           = generateCutBridgeAnnotation(circuitMain, cutBridgeModule, DMA_BITWIDTH, inModule)
    val ret            = CutBridgeInfo(inst, module, anno, inPorts.toSeq, inPortsBits.toSeq, outPorts.toSeq, outPortsBits.toSeq)
    initGenCutBridge()
    ret
  }
}
