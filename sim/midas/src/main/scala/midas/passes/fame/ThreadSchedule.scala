// See LICENSE for license details

package midas.passes.fame

import firrtl._
import firrtl.ir._

trait ThreadSchedule {
  def nThreads: Int
  def construct(hostClock: WRef, hostReset: WRef, ns: Namespace): ThreadSchedulerInstance
}

case class SimpleSchedule(nThreads: Int) extends ThreadSchedule {
  def construct(hostClock: WRef, hostReset: WRef, ns: Namespace): ThreadSchedulerInstance = {
    new SimpleSchedulerInstance(nThreads)
  }
}

trait ThreadSchedulerInstance {
  def nThreads: Int
  def impl: Statement
  def startReads(mem: DefMemory, tid: Int): Expression
  def readsVisible(mem: DefMemory, tid: Int): Expression
  def startWrites(mem: DefMemory, tid: Int): Expression
}

class SimpleSchedulerInstance(val nThreads: Int) extends ThreadSchedulerInstance {
  def impl: Statement = ???
  def startReads(mem: DefMemory, tid: Int): Expression = ???
  def readsVisible(mem: DefMemory, tid: Int): Expression = ???
  def startWrites(mem: DefMemory, tid: Int): Expression = ???
}
