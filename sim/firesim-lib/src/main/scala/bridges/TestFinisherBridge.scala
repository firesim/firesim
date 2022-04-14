
package firesim.bridges

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._
import midas.widgets.PeekPokeBridge

case class TestIndicatorParams(
  address:          BigInt  = BigInt(0x4000),
  nConcurrentTests: Int     = 1,
  allowScratchReg:  Boolean = false,
) {
  def size: Int        = 0x1000
  def regBytes: Int    = 4
  def codeBits: Int    = 16
  def successCode: Int = 0x5555 
  def failCode: Int    = 0x3333 
}                 

class TestFinisherPortIO(val params: TestIndicatorParams) extends Bundle {
  //A completion Indication vector for parameterised number of concurrent programs
  val testDoneVec = UInt((params.nConcurrentTests).W)
  //Error code in case program terminates unsuccessfully
  val errCode = UInt((params.codeBits).W)
}

class TestFinisherBridgeTargetIO(params: TestIndicatorParams) extends Bundle {
  val clock = Input(Clock())
  val testDone = Input(new TestFinisherPortIO(params))
}

class TestFinisherBridgeHostIO(params: TestIndicatorParams)
                              (private val targetIO : TestFinisherBridgeTargetIO = new TestFinisherBridgeTargetIO(params)) 
                               extends Bundle with ChannelizedHostPortIO{
  def targetClockRef = targetIO.clock 
  //It is just inputs from the target to indicate test completion
  //The HostPort annotation would not work with aggregates so creating individual channels.
  val testDoneH = InputChannel(targetIO.testDone.testDoneVec)
  val errCodeH = InputChannel(targetIO.testDone.errCode)
}

class TestFinisherBridge(params: TestIndicatorParams)(implicit p: Parameters) extends BlackBox
    with Bridge[TestFinisherBridgeHostIO, TestFinisherBridgeModule] {
  val io = IO(new TestFinisherBridgeTargetIO(params))
  val bridgeIO = new TestFinisherBridgeHostIO(params)(io)

  val constructorArg = Some(params)

  generateAnnotations()
}

object TestFinisherBridge {
  def apply(clock: Clock, finisher: TestFinisherPortIO)(implicit p: Parameters): TestFinisherBridge = {
    val tfm = Module(new TestFinisherBridge(finisher.params))
    tfm.io.testDone := finisher
    tfm.io.clock := clock 
    tfm  
  }
}

class TestFinisherBridgeModule(params: TestIndicatorParams)(implicit p: Parameters) extends BridgeModule[TestFinisherBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    // This creates the interfaces for all of the host-side transport
    // AXI4-lite for the simulation control bus, =
    // AXI4 for DMA
    val io = IO(new WidgetIO())



    // This creates the host-side interface of your TargetIO
    val hPort = IO(new TestFinisherBridgeHostIO(params)())
    
    val statusDone = RegInit(Bool(), false.B)
    
    
    statusDone := ((hPort.testDoneH.bits).asBools).reduce(_&&_);
    val errCode = RegInit(0.U((params.codeBits).W))
    errCode := hPort.errCodeH.bits
    //Condition needed for to signal HostPort Channel can send the messages.
    //So host is ready until the status is Done.
    hPort.testDoneH.ready := !statusDone
    hPort.errCodeH.ready := !statusDone
    
    genROReg(statusDone, "out_status")
    genROReg(errCode, "out_errCode")

    // This method invocation is required to wire up all of the MMIO registers to
    // the simulation control bus (AXI4-lite)
    genCRFile()
  }
}
