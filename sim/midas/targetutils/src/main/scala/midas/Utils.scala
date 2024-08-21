// See LICENSE for license details.

package midas.targetutils

import chisel3.{chiselTypeOf, Data, Module}
import chisel3.util.{DecoupledIO, Queue, QueueIO}

import midas.targetutils.xdc._

object FireSimQueueHelper {
  def addStyleHint[T <: Data](
    queue:         Queue[T],
    queueSize:     Int,
    isFireSim:     Boolean          = false,
    overrideStyle: Option[RAMStyle] = None,
  ): Unit = {
    // URAMs are 288Kb (rough guess what fits in URAMs better if no override given)
    val estStyle   = if (queueSize > 225000) Some(RAMStyles.ULTRA) else None
    val sizeStyle  = if (overrideStyle.isDefined) overrideStyle else estStyle
    val finalStyle = if (isFireSim) sizeStyle else None
    finalStyle match {
      case Some(s) => {
        println(s"FireSimQueueHelper: $queueSize bits in queue, using $estStyle style")
        RAMStyleHint(queue.ram, s)
      }
      case None    => println("FireSimQueueHelper: Skipping style XDC annotation")
    }
  }

  def makeIO[T <: Data](
    gen:           T,
    entries:       Int,
    isFireSim:     Boolean          = false,
    overrideStyle: Option[RAMStyle] = None,
  ): QueueIO[T] = {
    val q = Module(
      new Queue(gen, entries, useSyncReadMem = isFireSim)
    ) // use SyncReadMem's to infer BRAMs better in FireSim
    addStyleHint(q, entries * gen.getWidth, isFireSim, overrideStyle)
    q.io
  }

  def makeDeqIO[T <: Data](
    enq:           DecoupledIO[T],
    entries:       Int,
    isFireSim:     Boolean          = false,
    overrideStyle: Option[RAMStyle] = None,
  ): DecoupledIO[T] = {
    val qio = makeIO(chiselTypeOf(enq.bits), entries, isFireSim, overrideStyle)
    qio.enq.valid := enq.valid
    qio.enq.bits  := enq.bits
    enq.ready     := qio.enq.ready
    qio.deq
  }
}
