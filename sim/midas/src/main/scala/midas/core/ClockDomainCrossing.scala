// See LICENSE for license details.

package midas.core

import freechips.rocketchip.tilelink.LFSR64 // Better than chisel's

import chisel3._
import chisel3.util._

trait ClockUtils {
  // Assume time is measured in ps
  val timeStepBits = 32

}

class GenericClockCrossing[T <: Data](gen: T) extends MultiIOModule with ClockUtils {
  val enq  = IO(Flipped(Decoupled(gen)))
  val deq = IO(Decoupled(gen))
  val enqDomainTimeStep = IO(Input(UInt(timeStepBits.W)))
  val deqDomainTimeStep = IO(Input(UInt(timeStepBits.W)))

  val enqTokens = Queue(enq, 2)

  // Deq Domain handling
  val residualTime      = Reg(UInt(timeStepBits.W))
  val hasResidualTime   = RegInit(false.B)
  val timeToNextEnqEdge = Mux(hasResidualTime, residualTime, enqDomainTimeStep)
  val timeToNextDeqEdge = RegInit(0.U(timeStepBits.W))

  val enqTokenVisible   = timeToNextEnqEdge > timeToNextDeqEdge
  val tokenWouldExpire  = timeToNextEnqEdge < timeToNextDeqEdge + deqDomainTimeStep

  deq.valid := enqTokens.valid && enqTokenVisible
  deq.bits  := enqTokens.bits
  enqTokens.ready := !enqTokenVisible || deq.ready && tokenWouldExpire

  val enqTokenExpiring = enqTokens.fire
  val deqTokenReleased = deq.fire

  // CASE 1: This ENQ token is visible in the current deq token, but not visible in future DEQ tokens
  //  ENQ N | ENQ N1 |
  // ... | DEQ M | DEQ M1 |
  when (enqTokenExpiring && deqTokenReleased) {
    hasResidualTime := false.B
    timeToNextDeqEdge := timeToNextDeqEdge + deqDomainTimeStep - timeToNextEnqEdge
  // Case 2: This ENQ token is no longer visible, generally Fast -> Slow)
  //  ENQ N  | ENQ N+1 | ...
  //           DEQ M           | DEQ M+1...
  }.elsewhen(enqTokenExpiring) {
    hasResidualTime := false.B
    timeToNextDeqEdge := timeToNextDeqEdge - timeToNextEnqEdge
  // Case 3: This ENQ token is visible in the current and possibly future output tokens
  //        ENQ M           | ...
  //  ENQ N  | ENQ N+1 | ...
  }.elsewhen(deqTokenReleased) {
    hasResidualTime := true.B
    timeToNextDeqEdge := deqDomainTimeStep
    residualTime := timeToNextEnqEdge - deqDomainTimeStep
  }
}
