
//See LICENSE for license details
package firesim.midasexamples

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

import midas.widgets._

class TerminationModuleIO(val params: TerminationBridgeParams) extends Bundle {
  val msgInCycle = Input(UInt(16.W))  //Cycle when error is valid
  val validInCycle = Input(UInt(16.W)) //Cycle when the program finishes
  val doneErrCode = Input(UInt(((params.conditionInfo).size).W)) //Error code supplied by program
}

class TerminationModuleDUT extends Module {

  val endMessage = Seq(TerminationCondition(false, "success 1"), TerminationCondition(false, "success 2"), TerminationCondition(true, "failure 3"))
  val params = TerminationBridgeParams(endMessage)
  val io = IO(new TerminationModuleIO(params))
  val counter = RegInit(0.U(16.W))

  counter := counter + 1.U 
  val valid = (io.doneErrCode.asBools).map { _ && (counter >= io.validInCycle)}

  TerminationBridge(valid, params) 

}

class TerminationModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new TerminationModuleDUT)
