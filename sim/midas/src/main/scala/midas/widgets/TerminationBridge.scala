
package midas.widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.targetutils._
import freechips.rocketchip.util.{DecoupledHelper}

/**
  * Defines a condition under which the simulator should halt. 
  * @param Message A string printed by the driver to indicate why the simulator is terminating.
  * @param isError When true, instructs the driver to return a non-zero value when exiting under this condition.
**/
case class TerminationCondition(isErr: Boolean, message : String)

/** Sugar for constructing instances of [[TerminationCondition]]s */
object TerminationCondition {
  def error(message: String) = TerminationCondition(true,  message)
  def pass(message: String)  = TerminationCondition(false, message)

  val dummyCondition = TerminationCondition(true, "DUMMY CONDITION: THIS MESSAGE SHOULD NOT PRINT.")
}

/** A seq of termination conditions one for each concurrently running program
 */
case class TerminationBridgeParams(
  conditionInfo: Seq[TerminationCondition]
)                

class TerminationBridgeTargetIO(params: TerminationBridgeParams) extends Bundle {
  val clock = Input(Clock())
  val terminationCode = Input(UInt((log2Ceil((params.conditionInfo).size)).W))
  val valid = Input(Bool()) 
}

class TerminationBridgeHostIO(params: TerminationBridgeParams)
                              (private val targetIO : TerminationBridgeTargetIO = new TerminationBridgeTargetIO(params))
                               extends Bundle with ChannelizedHostPortIO{
  def targetClockRef = targetIO.clock
  //It is just inputs from the target to indicate test completion
  //The HostPort annotation would not work with aggregates so creating individual channels.
  val terminationCodeH = InputChannel(targetIO.terminationCode)
  val validH = InputChannel(targetIO.valid)
}

class TerminationBridge(params: TerminationBridgeParams) extends BlackBox
    with Bridge[TerminationBridgeHostIO, TerminationBridgeModule] {
  val io = IO(new TerminationBridgeTargetIO(params))
  val bridgeIO = new TerminationBridgeHostIO(params)(io)

  val constructorArg = Some(params)

  generateAnnotations()
}

object TerminationBridge {

  private def annotateTerminationBridge(clock: Clock, reset: Reset, conditionBools: Seq[Bool], params: TerminationBridgeParams): TerminationBridge = {
    require(conditionBools.nonEmpty, "Termination Bridge must be instantiated with at least one exit condition.")
    require(params.conditionInfo.size == conditionBools.size,
      "Must provide exactly as many TerminationConditions as Bool predicates. Got ${conditionBools.size} bools and ${params.conditionInfo.size} conditions."
    )

    // Hack: ensuring at least two conditions prevents GG from optimizing away the
    // index (const-propping a zero-into the blackbox), breaking ReferenceTarget renaming
    // I tried DontTouch-ing multiple different things to no effect.
    val (paddedBools, paddedParams) = if (conditionBools.size == 1) {
      (conditionBools :+ false.B, params.copy(conditionInfo = params.conditionInfo :+ TerminationCondition.dummyCondition))
    } else {
      (conditionBools, params)
    }

    val terminationBridgeTarget = Module(new TerminationBridge(paddedParams))
    val finish = paddedBools.reduce(_||_)
    terminationBridgeTarget.io.valid := finish && !reset.asBool && !GlobalResetCondition.produceSink()
    terminationBridgeTarget.io.clock := clock
    terminationBridgeTarget.io.terminationCode := PriorityEncoder(paddedBools)
    terminationBridgeTarget
  }

/** Instantiates the target side of the Bridge, selects one of
 *  the available finish conditions and passes on the corresponding
 *  error message.
 *  @param clock: Clock to the bridge which it must run on.
 *  @param reset: local reset for the bridge.
 *  @param conditionBools: Seq of finished conditions indicated by running programs
 *  @param params: Possible list of message info to be returned by simulator
 */

  def apply(clock: Clock, reset: Reset, conditionBools: Seq[Bool], params: TerminationBridgeParams): TerminationBridge = {
    annotateTerminationBridge(clock, reset, conditionBools, params)
  }

/** Simpler way to instantiate target side of the bridge by using implicit clock and reset
 */
  def apply(conditionBools: Seq[Bool], params: TerminationBridgeParams): TerminationBridge = {
    annotateTerminationBridge(Module.clock, Module.reset, conditionBools, params)
  }

  /** A stand-in for a single synthesized assertion.
    *
    * In the future this may be subsumed by annotations on existing Assert statements.
    *
    * @param condition
    *   The invariant that should remain true (except possibly under reset).
    *   Mirrors what you'd pass to chisel3.assert
    *
    * @param message
    *   The message to print when the assertion fails to hold.
    *   Mirrors what you'd pass to chisel3.assert
    */
  def assert(condition: Bool, message: String): TerminationBridge = {
    val params = TerminationBridgeParams(Seq(TerminationCondition.error(message)))
    annotateTerminationBridge(Module.clock, Module.reset, Seq(!condition), params)
  }
}

class TerminationBridgeModule(params: TerminationBridgeParams)(implicit p: Parameters) extends BridgeModule[TerminationBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {

    val io = IO(new WidgetIO())
    val hPort = IO(new TerminationBridgeHostIO(params)())

    val statusDone = Queue(hPort.validH)
    val terminationCode = Queue(hPort.terminationCodeH)

    val cycleCountWidth = 64

    val tokenCounter = genWideRORegInit(0.U(cycleCountWidth.W), "out_counter")

    val noTermination = !statusDone.bits

    val tFireHelper = DecoupledHelper(terminationCode.valid, statusDone.valid, noTermination)

    when(tFireHelper.fire()) {
      tokenCounter := tokenCounter + 1.U
    }

    statusDone.ready := tFireHelper.fire(statusDone.valid)
    terminationCode.ready := tFireHelper.fire(terminationCode.valid)

    //MMIO to indicate if the simulation has to be terminated
    genROReg(statusDone.bits && statusDone.valid, "out_status")
    //MMIO to indicate one of the target defined termination messages
    genROReg(terminationCode.bits, "out_terminationCode")

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, memoryRegions, sb)
      sb.append(genConstStatic(s"${headerWidgetName}_message_count", UInt32((params.conditionInfo).size)))
      sb.append(genArray(s"${headerWidgetName}_message_type", (params.conditionInfo).map(x => UInt32(if(x.isErr) 1 else 0))))
      sb.append(genArray(s"${headerWidgetName}_message", (params.conditionInfo).map(x => CStrLit(x.message))))
    }
    genCRFile()
  }
}
