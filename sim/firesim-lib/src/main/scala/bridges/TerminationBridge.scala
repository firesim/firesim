
package firesim.bridges

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._
import midas.targetutils._
import freechips.rocketchip.util.{DecoupledHelper}

/**
  * Defines a condition under which the simulator should halt. 
  * @param Message A string printed by the driver to indicate why the simulator is terminating.
  * @param isError When true, instructs the driver to return a non-zero value when exiting under this condition.
**/
case class TerminationCondition(isErr: Boolean, message : String)

/** A seq of termination conditions one for each concurrently running program
 */
case class TerminationBridgeParams(
  conditionInfo: Seq[TerminationCondition]
)                

class TerminationBridgeTargetIO(params: TerminationBridgeParams) extends Bundle {
  val clock = Input(Clock())
  val errCode = Input(UInt((log2Ceil((params.conditionInfo).size)).W))
  val valid = Input(Bool()) 
}

class TerminationBridgeHostIO(params: TerminationBridgeParams)
                              (private val targetIO : TerminationBridgeTargetIO = new TerminationBridgeTargetIO(params)) 
                               extends Bundle with ChannelizedHostPortIO{
  def targetClockRef = targetIO.clock 
  //It is just inputs from the target to indicate test completion
  //The HostPort annotation would not work with aggregates so creating individual channels.
  val errCodeH = InputChannel(targetIO.errCode)
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
    val finish = conditionBools.reduce(_||_)
    val errMessageID = PriorityEncoder(conditionBools)
    //val gReset = WireInit(false.B)
    //GlobalResetCondition.sink(gReset)
    require(params.conditionInfo.size==conditionBools.size)
    val terminationBridgeTarget = Module(new TerminationBridge(params))
    terminationBridgeTarget.io.errCode := errMessageID
    terminationBridgeTarget.io.valid := finish && !reset.asBool
    terminationBridgeTarget.io.clock := clock 
    terminationBridgeTarget  
  }

/** Instatiates the target side of the Bridge, selects one of 
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

}

class TerminationBridgeModule(params: TerminationBridgeParams)(implicit p: Parameters) extends BridgeModule[TerminationBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    // This creates the interfaces for all of the host-side transport
    // AXI4-lite for the simulation control bus, =
    // AXI4 for DMA
    val io = IO(new WidgetIO())



    // This creates the host-side interface of your TargetIO
    val hPort = IO(new TerminationBridgeHostIO(params)())
    
    val statusDone = Module(new Queue(Bool(), 1))
    val errCode = Module(new Queue(hPort.errCodeH.bits.cloneType, 1))

    val cycleCountWidth = 64 //Data width is capped by nastikey 

    val tokenCounter = genWideRORegInit(0.U(cycleCountWidth.W), "out_counter")    


    statusDone.io.enq <> hPort.validH
    errCode.io.enq <> hPort.errCodeH 

    val noTermination = !statusDone.io.deq.bits

    val tFireHelper = DecoupledHelper(errCode.io.deq.valid, statusDone.io.deq.valid, noTermination)

    when(tFireHelper.fire()) {
      tokenCounter := tokenCounter + 1.U
    }

    statusDone.io.deq.ready := tFireHelper.fire(statusDone.io.deq.valid)
    errCode.io.deq.ready := tFireHelper.fire(errCode.io.deq.valid)

    genROReg(statusDone.io.deq.bits && statusDone.io.deq.valid, "out_status")
    genROReg(errCode.io.deq.bits, "out_errCode")

    override def genHeader(base: BigInt, sb: StringBuilder) {
      import CppGenerationUtils._
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, sb)
      sb.append(genConstStatic(s"${headerWidgetName}_message_count", UInt32((params.conditionInfo).size)))
      sb.append(genArray(s"${headerWidgetName}_message_type", (params.conditionInfo).map(x => UInt32(if(x.isErr) 1 else 0))))
      sb.append(genArray(s"${headerWidgetName}_message", (params.conditionInfo).map(x => CStrLit(x.message))))
    }
    genCRFile()
  }
}
