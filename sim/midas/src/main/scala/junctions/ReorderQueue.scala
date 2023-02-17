package junctions

import Chisel._

class ReorderQueueWrite[T <: Data](dType: T, tagWidth: Int) extends Bundle {
  val data = dType.cloneType
  val tag = UInt(width = tagWidth)
}

class ReorderEnqueueIO[T <: Data](dType: T, tagWidth: Int)
  extends DecoupledIO(new ReorderQueueWrite(dType, tagWidth)) {
}

class ReorderDequeueIO[T <: Data](dType: T, tagWidth: Int) extends Bundle {
  val valid = Bool(INPUT)
  val tag = UInt(INPUT, tagWidth)
  val data = dType.cloneType.asOutput
  val matches = Bool(OUTPUT)
}

class ReorderQueue[T <: Data](dType: T, tagWidth: Int,
    size: Option[Int] = None, nDeq: Int = 1)
    extends Module {
  val io = new Bundle {
    val enq = new ReorderEnqueueIO(dType, tagWidth).flip
    val deq = Vec(nDeq, new ReorderDequeueIO(dType, tagWidth))
  }

  val tagSpaceSize = 1 << tagWidth
  val actualSize = size.getOrElse(tagSpaceSize)

  if (tagSpaceSize > actualSize) {
    require(tagSpaceSize % actualSize == 0)

    val smallTagSize = log2Ceil(actualSize)

    val roq_data = Reg(Vec(actualSize, dType))
    val roq_tags = Reg(Vec(actualSize, UInt(width = tagWidth - smallTagSize)))
    val roq_free = Reg(init = Vec.fill(actualSize)(true.B))
    val roq_enq_addr = io.enq.bits.tag(smallTagSize-1, 0)

    io.enq.ready := roq_free(roq_enq_addr)

    when (io.enq.valid && io.enq.ready) {
      roq_data(roq_enq_addr) := io.enq.bits.data
      roq_tags(roq_enq_addr) := io.enq.bits.tag >> smallTagSize.U
      roq_free(roq_enq_addr) := false.B
    }

    io.deq.foreach { deq =>
      val roq_deq_addr = deq.tag(smallTagSize-1, 0)

      deq.data := roq_data(roq_deq_addr)
      deq.matches := !roq_free(roq_deq_addr) && roq_tags(roq_deq_addr) === (deq.tag >> smallTagSize.U)

      when (deq.valid) {
        roq_free(roq_deq_addr) := true.B
      }
    }
  } else if (tagSpaceSize == actualSize) {
    val roq_data = Mem(tagSpaceSize, dType)
    val roq_free = Reg(init = Vec.fill(tagSpaceSize)(true.B))

    io.enq.ready := roq_free(io.enq.bits.tag)

    when (io.enq.valid && io.enq.ready) {
      roq_data(io.enq.bits.tag) := io.enq.bits.data
      roq_free(io.enq.bits.tag) := false.B
    }

    io.deq.foreach { deq =>
      deq.data := roq_data(deq.tag)
      deq.matches := !roq_free(deq.tag)

      when (deq.valid) {
        roq_free(deq.tag) := true.B
      }
    }
  } else {
    require(actualSize % tagSpaceSize == 0)

    val qDepth = actualSize / tagSpaceSize
    val queues = Seq.fill(tagSpaceSize) {
      Module(new Queue(dType, qDepth))
    }

    io.enq.ready := false.B
    io.deq.foreach(_.matches := false.B)
    io.deq.foreach(_.data := dType.fromBits(UInt(0)))

    for ((q, i) <- queues.zipWithIndex) {
      when (io.enq.bits.tag === UInt(i)) { io.enq.ready := q.io.enq.ready }
      q.io.enq.valid := io.enq.valid && io.enq.bits.tag === UInt(i)
      q.io.enq.bits  := io.enq.bits.data

      val deqReadys = Wire(Vec(nDeq, Bool()))
      io.deq.zip(deqReadys).foreach { case (deq, rdy) =>
        when (deq.tag === UInt(i)) {
          deq.matches := q.io.deq.valid
          deq.data := q.io.deq.bits
        }
        rdy := deq.valid && deq.tag === UInt(i)
      }
      q.io.deq.ready := deqReadys.reduce(_ || _)
    }
  }
}
