package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

object Strober {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty, hasMem: Boolean = true) = {
    val params = targetParams alter StroberParams.mask
    Module(new StroberAXI4Wrapper(c, hasMem))(params)
  }
}

abstract class Strober extends Module with StroberParams {
  def target: Module
  def hostif: StroberHostIF
  val q_ins = ArrayBuffer[DecoupledIO[Data]]()
  val q_outs = ArrayBuffer[DecoupledIO[Data]]()
  val v_ins = ArrayBuffer[ValidIO[Data]]()
  val v_outs = ArrayBuffer[ValidIO[Data]]()
  val w_ins = ArrayBuffer[Bits]()
  val w_outs = ArrayBuffer[Bits]()
  def findIOs[T <: Data](io: T, name: String = "") {
    io match {
      case d_io: DecoupledIO[Data] => {
        if (d_io.valid.dir == INPUT) q_ins += d_io else q_outs += d_io
      }
      case v_io: ValidIO[data] => {
        if (v_io.valid.dir == INPUT) v_ins += v_io else v_outs += v_io
      }
      case b: Bundle => {
        for ((n, elm) <- b.elements) findIOs(elm, n)
      }
      case _ => {
        val (ins, outs) = io.flatten partition (_._2.dir == INPUT)
        w_ins ++= ins.unzip._2
        w_outs ++= outs.unzip._2
      }
    }
  }
}

class AXI4Addr extends Bundle {
  // Write / Read Address Channel Signals
  val axiAddrWidth = params(AXIAddrWidth)
  val id    = Bits(width=12)
  val addr  = Bits(width=axiAddrWidth)
  val len   = Bits(width=8)
  val size  = Bits(width=3)
  val burst = Bits(width=2)
}

class AXI4Read extends Bundle {
  // Read Data Channel Signals
  val axiDataWidth = params(AXIDataWidth)
  val data   = Bits(width=axiDataWidth)
  val last   = Bool()
  val id     = Bits(width=12)
  val resp   = Bits(width=2)
}

class AXI4Write extends Bundle {
  // Write Data Channel Signals
  val axiDataWidth = params(AXIDataWidth)
  val data   = Bits(width=axiDataWidth)
  val last   = Bool()
  val strb   = Bits(width=4)
}

class AXI4Resp extends Bundle {
  // Write Response Channel Signals
  val id     = Bits(width=12)
  val resp   = Bits(width=2) // Not used here
}

class AXI4_IO extends Bundle { // S_AXI
  val aw = Decoupled(new AXI4Addr)
  val w  = Decoupled(new AXI4Write)
  val b  = Decoupled(new AXI4Resp).flip
  val ar = Decoupled(new AXI4Addr)
  val r  = Decoupled(new AXI4Read).flip
}

class StroberAXI4WrapperIO extends Bundle {
  val M_AXI = (new AXI4_IO).flip
  val S_AXI = new AXI4_IO
}

class StroberAXI4Wrapper[+T <: Module](c: =>T, hasMem: Boolean = true) extends Strober {
  val io = new StroberAXI4WrapperIO
  val hostif = Module(new StroberHostIF)
  val target = Module(c)

  findIOs(target.io)
  // Find the target's MemIO
  (q_outs find { wires =>
    val hostNames = hostif.io.fmem.req_cmd.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case Some(q) if hasMem => {
      q <> hostif.io.tmem.req_cmd
      q_outs -= q
    }
    case _ =>
  }

  (q_outs find { wires =>
    val hostNames = hostif.io.fmem.req_data.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case Some(q) if hasMem => {
      q <> hostif.io.tmem.req_data
      q_outs -= q
    }
    case _ => 
  }

  (q_ins find { wires =>
    val hostNames = hostif.io.fmem.resp.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case Some(q) if hasMem => {
      q <> hostif.io.tmem.resp
      q_ins -= q
    }
    case _ =>
  }

  /*** M_AXI INPUTS ***/
  // ADDR 0 => HOST IN
  // ADDR 1 => TARGET RESET
  // Input addresses start from 2
  var waddr_id = 2
  val waddr_r = RegInit(UInt(0))
  val awid_r  = RegInit(UInt(0))
  val st_wr_idle::st_wr_write::st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val w_in_fifos = ListBuffer[Queue[UInt]]()
  val q_in_fifos = ListBuffer[Queue[UInt]]()
  val do_write = st_wr === st_wr_write

  hostif.io.host.in.bits  := io.M_AXI.w.bits.data
  hostif.io.host.in.valid := do_write && (waddr_r === UInt(0))
  // TODO: reset target

  // Address mapping for wire inputs
  for (in <- w_ins) {
    val width = in.needWidth
    val qs = ListBuffer[Queue[UInt]]()
    for (i <- 0 until width by axiDataWidth) {
      val high = math.min(i+axiDataWidth-1, width-1)  
      val q = Module(new Queue(UInt(width=high-i+1), axiFifoLen))
      q.io.enq.bits  := io.M_AXI.w.bits.data
      q.io.enq.valid := do_write && (waddr_r === UInt(waddr_id)) 
      q.io.deq.ready := !hostif.io.fire && hostif.io.fireNext // pop values only at the edge of the steps
      qs += q
      waddr_id += 1
    }
    in := Cat(qs map (_.io.deq.bits))
    w_in_fifos ++= qs
    qs.clear
  }

  // Address mapping for decoupled inputs
  for (in <- q_ins) {
    var valid = Bool(true)
    for ((_, in_io) <- in.bits.flatten) {
      val width = io.needWidth
      val qs = ArrayBuffer[Queue[UInt]]()
      for (i <- 0 until width by axiDataWidth) {
        val high = math.min(i+axiDataWidth-1, width-1)  
        val q = Module(new Queue(UInt(width=high-i+1), axiFifoLen))
        q.io.enq.bits  := io.M_AXI.w.bits.data
        q.io.enq.valid := do_write && (waddr_r === UInt(waddr_id))
        q.io.deq.ready := in.ready && hostif.io.fire 
        qs += q
        waddr_id += 1
      }
      in_io := Cat(qs map (_.io.deq.bits))
      valid = valid && (qs.tail foldLeft qs.head.io.deq.valid)(_ && _.io.deq.valid)
      q_in_fifos ++= qs
      qs.clear
    }
    in.valid := hostif.io.fire && valid
  }

  val in_ready = Vec.fill(waddr_id){Bool()}
  val in_fifos = w_in_fifos.toList ::: q_in_fifos.toList
  in_ready(0) := hostif.io.host.in.ready // HOST INPUT
  in_ready(1) := Bool(true)              // TARGET RESET
  in_fifos.zipWithIndex foreach { case (q, id) => in_ready(id+2) := q.io.enq.ready }  

  // M_AXI Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.M_AXI.aw.valid && io.M_AXI.w.valid) {
        st_wr   := st_wr_write
        waddr_r := io.M_AXI.aw.bits.addr >> UInt(2)
        awid_r  := io.M_AXI.aw.bits.id
      }
    }
    is(st_wr_write) {
      when(in_ready(waddr_r)) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.M_AXI.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  io.M_AXI.aw.ready  := do_write
  io.M_AXI.w.ready   := do_write
  io.M_AXI.b.valid   := st_wr === st_wr_ack
  io.M_AXI.b.bits.id := awid_r

  /*** M_AXI OUTPUS ***/
  // ADDR 0 => HOST OUT DATA
  // ADDR 1 => HOST OUT COUNT
  // EVEN ADDRESSES(2, 4, ...) => OUTPUT / TRACE DATA
  // ODD  ADDRESSES(3, 5, ...) => OUTPUT / TRACE COUNT
  // TODO: assign addresses to memory trace
  var raddr_id = 2
  val raddr_r = RegInit(UInt(0))
  val arid_r  = RegInit(UInt(0))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val w_out_fifos = ListBuffer[Queue[UInt]]()
  val q_out_fifos = ListBuffer[Queue[UInt]]()
  
  hostif.io.host.out.ready := io.M_AXI.r.fire() && raddr_r === UInt(0)

  // Address mapping for wire outputs
  for (out <- w_outs) {
    val width = out.needWidth
    val qs = ListBuffer[Queue[UInt]]()
    for (i <- 0 until width by axiFifoLen) {
      val high = math.min(i+axiFifoLen-1, width-1)  
      val q = Module(new Queue(UInt(width=high-i+1), axiFifoLen))
      q.io.enq.bits  := out(high, i)
      q.io.enq.valid := !hostif.io.fire && hostif.io.fireNext // push values only at the edge of the steps
      q.io.deq.ready := io.M_AXI.r.fire() && (raddr_r === UInt(2*raddr_id))
      qs += q
      raddr_id += 1
    }
    w_out_fifos ++= qs
    qs.clear
  }
  // Address mapping for decoupled outputs
  for (out <- q_outs) {
    var ready = Bool(true)
    for ((_, out_io) <- out.bits.flatten) {
      val width = io.needWidth
      val qs = ListBuffer[Queue[UInt]]()
      for (i <- 0 until width by axiDataWidth) {
        val high = math.min(i+axiDataWidth-1, width-1)  
        val q = Module(new Queue(UInt(width=high-i+1), axiFifoLen))
        q.io.enq.bits  := out_io(high, i)
        q.io.enq.valid := out.valid && hostif.io.fireNext 
        q.io.deq.ready := io.M_AXI.r.fire() && (raddr_r === UInt(2*raddr_id)) 
        qs += q
        raddr_id += 1
      }
      ready = ready && (qs.tail foldLeft qs.head.io.enq.ready)(_ && _.io.enq.ready)
      q_out_fifos ++= qs
      qs.clear
    }
    out.ready := hostif.io.fire && ready
  }

  val out_fifos = w_out_fifos.toList ::: q_out_fifos.toList
  val out_data  = Vec.fill(out_fifos.size+1){UInt()}
  val out_count = Vec.fill(out_fifos.size+1){UInt()}
  out_data(0)  := hostif.io.host.out.bits
  out_count(0) := hostif.io.host.count
  out_fifos.zipWithIndex foreach { case (q, id) => out_data(id+1) := q.io.deq.bits }  
  out_fifos.zipWithIndex foreach { case (q, id) => out_count(id+1) := q.io.count }

  // M_AXI Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.M_AXI.ar.valid) {
        st_rd   := st_rd_read
        raddr_r := io.M_AXI.ar.bits.addr >> UInt(2)
        arid_r  := io.M_AXI.ar.bits.id
      }
    }
    is(st_rd_read) {
      when(io.M_AXI.r.ready) {
        st_rd   := st_rd_idle
      }
    }
  }
  io.M_AXI.ar.ready    := st_rd === st_rd_idle
  io.M_AXI.r.valid     := st_rd === st_rd_read
  io.M_AXI.r.bits.last := st_rd === st_rd_read
  io.M_AXI.r.bits.id   := arid_r
  val raddr_rshift_1 = raddr_r >> UInt(1)
  io.M_AXI.r.bits.data := Mux(raddr_r(0), out_count(raddr_rshift_1), out_data(raddr_rshift_1))

  // add daisy pins to the target
  hostif.io.daisy <> addDaisyPins(target, daisyWidth)
  // 
  hostif.io.fifoReady := 
    (w_in_fifos foldLeft Bool(true))(_ && _.io.enq.ready) &&
    (q_in_fifos foldLeft Bool(true))(_ && _.io.enq.ready)
}
