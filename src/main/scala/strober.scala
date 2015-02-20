package strober

import Chisel._
import scala.collection.mutable.ArrayBuffer

object Strober {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty, hasMem: Boolean = true, hasHTIF: Boolean = true) = {
    val params = targetParams alter StroberParams.mask
    Module(new Strober(c, hasMem, hasHTIF))(params)
  }
}

class HTIFIO extends Bundle {
  val htifLen = params(HTIFLen)
  val in = Decoupled(UInt(width=htifLen)).flip
  val out = Decoupled(UInt(width=htifLen))
}

class HostIO extends Bundle {
  val hostLen = params(HostLen)
  val in = Decoupled(UInt(width=hostLen)).flip
  val out = Decoupled(UInt(width=hostLen))
}

trait HasMemData extends Bundle {
  val memLen = params(MemLen) 
  val data = UInt(width=memLen)
}

trait HasMemAddr extends Bundle {
  val addrLen = params(AddrLen)
  val addr = UInt(width=addrLen)
}

trait HasMemTag extends Bundle {
  val tagLen = params(TagLen)
  val tag = UInt(width=tagLen)
}

class MemReqCmd extends HasMemAddr with HasMemTag {
  val rw = Bool()
}
class MemResp extends HasMemData with HasMemTag
class MemData extends HasMemData
class MemAddr extends HasMemAddr

class MemIO extends Bundle {
  val req_cmd  = Decoupled(new MemReqCmd)
  val req_data = Decoupled(new MemData)
  val resp     = Decoupled(new MemResp).flip
}

class StroberIO extends Bundle {
  val htif = new HTIFIO
  val host = new HostIO
  val mem = new MemIO 
}

class Strober[+T <: Module](c: =>T, hasMem: Boolean = true, hasHTIF: Boolean = true) 
  extends Module with StroberParams with Commands {
  val io = new StroberIO
  val target = Module(c)
  val qIns = ArrayBuffer[DecoupledIO[Data]]()
  val qOuts = ArrayBuffer[DecoupledIO[Data]]()
  val wIns = ArrayBuffer[Bits]()
  val wOuts = ArrayBuffer[Bits]()
  def findIOs[T <: Data](io: T, name: String = "") {
    io match {
      case dIO: DecoupledIO[Data] => {
        if (dIO.valid.dir == INPUT) qIns += dIO else qOuts += dIO
      }
      case b: Bundle => {
        for ((n, elm) <- b.elements) {
          findIOs(elm, n)
        }
      }
      case _ => {
        val (ins, outs) = io.flatten partition (_._2.dir == INPUT)
        wIns ++= ins.unzip._2
        wOuts ++= outs.unzip._2
      }
    }
  }
  findIOs(target.io)

  val fire = Bool()
  val fireNext = RegNext(fire)
  val readNext = RegInit(Bool(false)) // Todo: incorperate this signal to IO readNextording

  // For memory commands
  val memReqCmd   = Reg(new MemReqCmd)
  val memReqData  = Reg(new MemData)
  val memResp     = Reg(new MemResp)
  val memTag      = RegInit(UInt(0, tagLen))
  val memReqCmdQ  = Module(new Queue(io.mem.req_cmd.bits.clone, traceLen))
  val memReqDataQ = Module(new Queue(io.mem.req_data.bits.clone, traceLen))
  val memRespQ    = Module(new Queue(io.mem.resp.bits.clone, traceLen))
  val wAddrTrace  = Module(new Queue(new MemAddr, traceLen))
  val wDataTrace  = Module(new Queue(new MemData, traceLen))
  val rAddrTrace  = Vec.fill(tagNum)(Reg(new MemAddr))
  val rAddrValid  = Vec.fill(tagNum)(RegInit(Bool(false)))

  // Find the target's MemIO
  // todo: extend it for multi mem ports
  (qOuts find { wires =>
    val hostNames = io.mem.req_cmd.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case Some(q) if hasMem => {
      val tMemReqCmd = new MemReqCmd
      tMemReqCmd := q.bits // to avoid type error......
      memReqCmdQ.io.enq.bits := Mux(fire, tMemReqCmd, memReqCmd)
      memReqCmdQ.io.enq.valid := q.valid && fire
      q.ready := memReqCmdQ.io.enq.ready && fire
      // Trace write addr
      wAddrTrace.io.enq.bits.addr := tMemReqCmd.addr
      wAddrTrace.io.enq.valid := tMemReqCmd.rw && q.valid && fireNext 
      when(!tMemReqCmd.rw && memReqCmdQ.io.enq.valid) {
        // Turn on rAddrTrace
        rAddrTrace(tMemReqCmd.tag).addr := tMemReqCmd.addr
        rAddrValid(tMemReqCmd.tag) := Bool(true)
        // Set memTag's value
        memTag := tMemReqCmd.tag + UInt(1)
      }
      qOutNum -= 1
      qOuts -= q
    }
    case _ => {
      memReqCmdQ.io.enq.bits := memReqCmd
      memReqCmdQ.io.enq.valid := Bool(false)
      wAddrTrace.io.enq.valid := Bool(false)
    }
  }
  wAddrTrace.io.deq.ready := Bool(false)

  (qOuts find { wires =>
    val hostNames = io.mem.req_data.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case Some(q) if hasMem => {
      val tMemReqData = new MemData
      tMemReqData := q.bits // to avoid type error ......!
      memReqDataQ.io.enq.bits := Mux(fire, tMemReqData, memReqData)
      memReqDataQ.io.enq.valid := q.valid && fire
      q.ready := memReqDataQ.io.enq.ready && fire
      // Trace write data
      wDataTrace.io.enq.bits.data := tMemReqData.data
      wDataTrace.io.enq.valid := q.valid && fireNext
      qOutNum -= 1
      qOuts -= q
    }
    case _ => {
      memReqDataQ.io.enq.bits := memReqData
      memReqDataQ.io.enq.valid := Bool(false)
      wDataTrace.io.enq.valid := Bool(false)
    }
  }
  wDataTrace.io.deq.ready := Bool(false)

  (qIns find { wires =>
    val hostNames = io.mem.resp.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case Some(q) if hasMem => {
      q.bits := memRespQ.io.deq.bits
      q.valid := memRespQ.io.deq.valid && fire
      memRespQ.io.deq.ready := q.ready && fire
      // Turn off rAddrTrace
      when(RegEnable(memRespQ.io.deq.valid, fire) && fire) {
        rAddrValid(RegEnable(memRespQ.io.deq.bits.tag, fire)) := Bool(false)
      }
      qInNum -= 1
      qIns -= q
    }
    case _ => {
      memRespQ.io.deq.ready := Bool(true)
    }
  }

  // Find the tqget's HTIF
  // TODO: it's a hack for referencechip, it shouldn't be exposed though...
  (target.io match {
    case b: Bundle => b.elements find { case (name, wires) => {
      (name == "host") && (io.htif.flatten forall {case (n0, io0) =>
        wires.flatten exists {case (n1, io1) =>
          n0 == n1 && io0.needWidth == io1.needWidth
        }
      })
    } }
    case _ => None
  }) match {
    case Some((n, q)) if hasHTIF => {
       // io.htif <> q
       q match {
         case b: Bundle => for ((n, i) <- b.elements) {
           i match {
             case dio: DecoupledIO[Data] if dio.valid.dir == INPUT => {
               // host_in
               dio.bits <> io.htif.in.bits
               dio.valid := io.htif.in.valid && fire
               io.htif.in.ready := dio.ready && fire
               qIns -= dio
             }
             case dio: DecoupledIO[Data] if dio.valid.dir == OUTPUT => {
               // host_out
               io.htif.out.bits <> dio.bits
               io.htif.out.valid := dio.valid && fire
               dio.ready := io.htif.out.ready && fire
               qOuts -= dio
             }
             case _ => // This shouldn't occur
           }
         }
         case _ => 
       }
    }
    case _ =>
  } 

  /*** IO Recording ***/
  // For decoupled IOs, insert FIFOs
  var id = 0
  var qInNum = 0
  val qInQs = ArrayBuffer[Queue[UInt]]() 
  for (in <- qIns) {
    var valid = Bool(true)
    for ((_, io) <- in.bits.flatten) {
      val width = io.needWidth
      val n = (width-1) / hostLen + 1
      val qs = ArrayBuffer[Queue[UInt]]()
      for (i <- 0 until n) {
        val low = i * hostLen
        val high = math.min(hostLen-1+low, width-1)  
        val q = Module(new Queue(UInt(width=high-low+1), traceLen))
        q.io.deq.ready := in.ready && fire 
        qs += q
      }
      io := Cat(qs map (_.io.deq.bits))
      valid = valid && (qs.tail foldLeft qs.head.io.deq.valid)(_ && _.io.deq.valid)
      id += n
      qInNum += n
      qInQs ++= qs
      qs.clear
    }
    in.valid := fire && valid
  }
  val qInEnqs = Vec(qInQs map (_.io.enq.clone.flip))
  qInQs.zipWithIndex foreach { case (q, id) => qInEnqs(id) <> q.io.enq }
  qInEnqs foreach (_.bits := Bits(0))
  qInEnqs foreach (_.valid := Bool(false))

  id = 0
  var qOutNum = 0
  val qOutQs = ArrayBuffer[Queue[UInt]]() 
  for (out <- qOuts) {
    var ready = Bool(true)
    for ((_, io) <- out.bits.flatten) {
      val width = io.needWidth
      val n = (width-1) / hostLen + 1
      val qs = ArrayBuffer[Queue[UInt]]()
      for (i <- 0 until n) {
        val low = i * hostLen
        val high = math.min(hostLen-1+low, width-1)  
        val q = Module(new Queue(UInt(width=high-low+1), traceLen))
        q.io.enq.bits := io(high, low)
        q.io.enq.valid := out.valid && fire
        qs += q
      }
      ready = ready && (qs.tail foldLeft qs.head.io.enq.ready)(_ && _.io.enq.ready)
      id += n
      qOutNum += n
      qOutQs ++= qs
      qs.clear
    }
    out.ready := fire && ready
  }
  val qOutDeqs = Vec(qOutQs map (_.io.deq.clone))
  val qOutCnts = Vec(qOutQs map (_.io.count.clone))
  qOutQs.zipWithIndex foreach { case (q, id) => q.io.deq <> qOutDeqs(id) }
  qOutQs.zipWithIndex foreach { case (q, id) => q.io.count <> qOutCnts(id) }
  qOutDeqs foreach (_.ready := Bool(false))

  // For wire IOs, insert FFs
  id = 0
  val wInNum = (wIns foldLeft 0)((res, in) => res + (in.needWidth-1)/hostLen + 1)
  val wInFFs = Vec.fill(wInNum) { Reg(UInt()) }
  for (in <- wIns) {
    // Resove width error
    in match {
      case _: Bool => wInFFs(id).init("", 1)
      case _ => 
    }
    val width = in.needWidth
    val n = (width-1) / hostLen + 1
    val ffs = (0 until n) map { x => wInFFs(id+x) }
    in := Mux(fire, Cat(ffs), UInt(0))
    id += n
  }

  id = 0
  val wOutNum = (wOuts foldLeft 0)((res, out) => res + (out.needWidth-1)/hostLen + 1)
  val wOutFFs = Vec.fill(wOutNum) { Reg(UInt()) }
  for (out <- wOuts) {
    out match {
      case _: Bool => wOutFFs(id).init("", 1)
      case _ => 
    }
    val width = out.needWidth
    val n = (width-1) / hostLen + 1
    val ffs = (0 until n) map { x => wOutFFs(id+x) }
    for ((ff, x) <- ffs.zipWithIndex) {
      val low = x * hostLen
      val high = math.min(hostLen-1+low, width-1)
      ff := Mux(fireNext, out(high, low), ff)
    }
    id += n
  }

  // Host pin connection
  io.host.in.ready  := Bool(false)
  io.host.out.valid := Bool(false)
  io.host.out.bits  := UInt(0)

  // Memory pin connection
  io.mem.req_cmd <> memReqCmdQ.io.deq
  io.mem.req_data <> memReqDataQ.io.deq
  memRespQ.io.enq.bits := io.mem.resp.bits
  memRespQ.io.enq.valid := io.mem.resp.valid
  io.mem.resp.ready := memRespQ.io.enq.ready

  // Daisy pin connection
  val daisy = addDaisyPins(target, daisyLen)
  daisy.stall := !fire 
  daisy.regs.in.bits := UInt(0)
  daisy.regs.in.valid := Bool(false)
  daisy.regs.out.ready := Bool(false)
  if (Driver.hasSRAM) {
    daisy.sram.in.bits := UInt(0)
    daisy.sram.in.valid := Bool(false)
    daisy.sram.out.ready := Bool(false)
    daisy.sram.restart := Bool(false)
  }

  // Machine states
  val (debug_IDLE :: debug_STEP :: debug_SNAP1 :: debug_SNAP2 :: debug_TRACE :: 
       debug_POKE :: debug_PEEK :: debug_POKEQ :: debug_PEEKQ :: debug_MEM :: Nil) = Enum(UInt(), 10)
  val debugState = RegInit(debug_IDLE)

  val stepcount = RegInit(UInt(0)) // Step Counter
  // Define the fire signal
  fire := (qOutQs foldLeft stepcount.orR)(_ && _.io.enq.ready) &&
          wAddrTrace.io.enq.ready && wDataTrace.io.enq.ready &&
          debugState === debug_STEP

  val snapbuf   = Reg(UInt(width=hostLen+daisyLen))
  val snapcount = Reg(UInt(width=log2Up(hostLen+1)))
  val sramcount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = RegInit(snap_IDLE)

  val pokecount = Reg(UInt())
  val peekcount = Reg(UInt())

  val pokeqlen = Reg(UInt(width=log2Up(traceLen+1)))
  val peekqlen = Reg(UInt(width=log2Up(traceLen+1)))
  val pokeqcount = Reg(UInt())
  val pokeq_COUNT :: pokeq_DATA :: Nil = Enum(UInt(), 2)
  val pokeqState = RegInit(pokeq_COUNT)

  val peekqcount = Reg(UInt())
  val peekq_COUNT :: peekq_DATA :: Nil = Enum(UInt(), 2)
  val peekqState = RegInit(peekq_COUNT)

  val waddrcount = Reg(UInt(width=log2Up(addrLen+1)))
  val wdatacount = Reg(UInt(width=log2Up(memLen+1)))
  val raddrcount = Reg(UInt())
  val (trace_WCOUNT :: trace_WADDR :: trace_WDATA :: 
       trace_RCOUNT :: trace_RADDR :: trace_RTAG :: Nil) = Enum(UInt(), 6)
  val traceState = RegInit(trace_WCOUNT)

  val addrcount = Reg(UInt())
  val datacount = Reg(UInt())
  val mem_REQ_CMD :: mem_REQ_DATA :: mem_WAIT :: mem_RESP :: Nil = Enum(UInt(), 4)
  val memState = RegInit(mem_REQ_CMD)

  switch(debugState) {
    is(debug_IDLE) {
      io.host.in.ready := Bool(true)
      when(io.host.in.fire()) {
        val cmd = io.host.in.bits(cmdLen-1, 0)
        when(cmd === STEP) {
          readNext := io.host.in.bits(cmdLen)
          stepcount := io.host.in.bits(hostLen-1, cmdLen+1)
          debugState := debug_STEP
        }.elsewhen(cmd === POKE) {
          pokecount := UInt(wInNum)
          debugState := debug_POKE
        }.elsewhen(cmd === PEEK) {
          peekcount := UInt(wOutNum)
          debugState := debug_PEEK
        }.elsewhen(cmd === POKEQ) {
          pokeqcount := UInt(qInNum)
          debugState := debug_POKEQ
        }.elsewhen(cmd === PEEKQ) {
          peekqcount := UInt(qOutNum)
          debugState := debug_PEEKQ
        }.elsewhen(cmd === TRACE) {
          debugState := debug_TRACE
        }.elsewhen(cmd === MEM) {
          memReqCmd.rw := io.host.in.bits(cmdLen) 
          memReqCmd.tag := memTag
          addrcount := UInt((addrLen-1)/hostLen + 1)
          datacount := UInt((memLen-1)/hostLen + 1)
          debugState := debug_MEM
        }
      }
    }

    is(debug_STEP) {
      when(fire) {
        stepcount := stepcount - UInt(1)
      }.otherwise {
        when(stepcount.orR) {
          when(!(wAddrTrace.io.enq.ready && wDataTrace.io.enq.ready)) {
            io.host.out.bits := step_TRACE
            io.host.out.valid := Bool(true)
            when(io.host.out.fire()) {
              debugState := debug_TRACE 
            } 
          }.otherwise {
            io.host.out.bits := step_PEEKQ
            io.host.out.valid := Bool(true)
            peekqcount := UInt(qOutNum)
            when(io.host.out.fire()) {
              debugState := debug_PEEKQ
            }
          }
        }.elsewhen(!fireNext) {
          snapcount := UInt(0)
          io.host.out.bits := step_FIN
          io.host.out.valid := Bool(true) 
          when(io.host.out.fire()) {
            debugState := Mux(readNext, debug_SNAP1, debug_IDLE)
            readNext := Bool(false)
          }
          if (Driver.hasSRAM) sramcount := UInt(Driver.sramMaxSize-1)
        }
      }
    }

    // Snapshoting inputs and registers
    is(debug_SNAP1) {
      switch(snapState) {
        is(snap_IDLE) {
          snapState := Mux(daisy.regs.out.valid, snap_READ, snap_IDLE)
        }
        is(snap_READ) {
          when(snapcount < UInt(hostLen)) {
            daisy.regs.out.ready := Bool(true)
            snapbuf := Cat(snapbuf, daisy.regs.out.bits)
            snapcount := snapcount + UInt(daisyLen)
          }.otherwise {
            snapcount := snapcount - UInt(hostLen)
            snapState := snap_SEND
          }
        }
        is(snap_SEND) {
          when(io.host.out.ready) {
            io.host.out.bits  := snapbuf >> snapcount
            io.host.out.valid := Bool(true)
            when (daisy.regs.out.valid) {
              snapState := snap_READ
            }.otherwise {
              snapState := snap_IDLE
              if (Driver.hasSRAM) {
                daisy.sram.restart := Bool(true)
                debugState := debug_SNAP2
              } else {
                debugState := debug_IDLE
              }
            }
          }
        }
      }
    }
    // Snapshotring SRAMs
    if (Driver.hasSRAM) {
      is(debug_SNAP2) {
        switch(snapState) {
          is(snap_IDLE) {
            snapState := Mux(daisy.sram.out.valid, snap_READ, snap_IDLE)
          }
          is(snap_READ) {
            when(snapcount < UInt(hostLen)) {
              daisy.sram.out.ready := Bool(true)
              snapbuf := Cat(snapbuf, daisy.sram.out.bits)
              snapcount := snapcount + UInt(daisyLen)
            }.otherwise {
              snapcount := snapcount - UInt(hostLen)
              snapState := snap_SEND
            }
          }
          is(snap_SEND) {
            when(io.host.out.ready) {
              io.host.out.bits  := snapbuf >> snapcount
              io.host.out.valid := Bool(true)
              when (daisy.sram.out.valid) {
                snapState := snap_READ
              }.elsewhen (sramcount.orR) {
                sramcount := sramcount - UInt(1)
                daisy.sram.restart := Bool(true)
                snapState := snap_IDLE
              }.otherwise {
                snapState := snap_IDLE
                debugState := debug_IDLE
              }
            }
          }
        }
      }
    }

    is(debug_POKE) {
      val id = UInt(wInNum) - pokecount
      io.host.in.ready := pokecount.orR
      when(io.host.in.fire()) {
        wInFFs(id) := io.host.in.bits 
        pokecount := pokecount - UInt(1)
      }.elsewhen(!io.host.in.ready) {
        debugState := debug_IDLE
      }
    }
    is(debug_PEEK) {
      val id = UInt(wOutNum) - peekcount
      io.host.out.bits  := wOutFFs(id)
      io.host.out.valid := peekcount.orR
      when(io.host.out.fire()) {
        peekcount := peekcount - UInt(1)
      }.elsewhen(!io.host.out.valid) {
        debugState := debug_IDLE
      }
    }

    if (qInNum > 0) {
      is(debug_POKEQ) {
        val id = UInt(qInNum) - pokeqcount
        switch(pokeqState) {
          is(pokeq_COUNT) {
            io.host.in.ready := pokeqcount.orR
            when(io.host.in.fire()) {
              pokeqlen := io.host.in.bits
              pokeqState := pokeq_DATA
            }.elsewhen(!io.host.in.ready) {
              debugState := debug_IDLE
            }
          }
          is(pokeq_DATA) {
            qInEnqs(id).bits := io.host.in.bits
            qInEnqs(id).valid := io.host.in.valid && pokeqlen.orR
            io.host.in.ready := qInEnqs(id).ready && pokeqlen.orR
            when(io.host.in.fire()) {
              pokeqlen := pokeqlen - UInt(1)
            }.elsewhen(!io.host.in.ready) {
              pokeqcount := pokeqcount - UInt(1)
              pokeqState := pokeq_COUNT
            }
          } 
        }
      }
    }

    if (qOutNum > 0) {
      // IO trace stage when any trace Q is full
      // Todo: this will be very slow...
      is(debug_PEEKQ) {
        val id = UInt(qOutNum) - peekqcount
        switch(peekqState) {
          is(peekq_COUNT) {
            io.host.out.bits := qOutCnts(id)
            io.host.out.valid := peekqcount.orR
            when(io.host.out.fire()) {
              peekqlen := io.host.out.bits
              peekqState := peekq_DATA
            }.elsewhen(!io.host.out.valid) {
              debugState := Mux(stepcount.orR, debug_STEP, debug_IDLE)
            }
          }
          is(peekq_DATA) {
            io.host.out.bits := qOutDeqs(id).bits
            io.host.out.valid := qOutDeqs(id).valid && peekqlen.orR
            qOutDeqs(id).ready := io.host.out.ready && peekqlen.orR
            when(io.host.out.fire()) {
              peekqlen := peekqlen - UInt(1)
            }.elsewhen(!io.host.out.valid) {
              peekqcount := peekqcount - UInt(1)
              peekqState := peekq_COUNT
            }
          } 
        }
      }
    }

    is(debug_TRACE) {
      switch(traceState) {
        is(trace_WCOUNT) {
          io.host.out.bits := wDataTrace.io.count
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_WADDR
            waddrcount := UInt(0)
            wdatacount := UInt(0)
          }          
        }
        is(trace_WADDR) {
          val toNext = if (addrLen > hostLen) waddrcount >= UInt(addrLen-hostLen) else Bool(true)
          io.host.out.bits := wAddrTrace.io.deq.bits.addr >> waddrcount
          io.host.out.valid := wAddrTrace.io.deq.valid
          wAddrTrace.io.deq.ready := io.host.out.ready && toNext
          when(io.host.out.fire()) {
            waddrcount := Mux(toNext, UInt(0), waddrcount + UInt(hostLen))
          }.elsewhen(!io.host.out.valid) {
            traceState := trace_WDATA
          }
        }
        is(trace_WDATA) {
          val toNext = if (memLen > hostLen) wdatacount >= UInt(memLen-hostLen) else Bool(true)
          io.host.out.bits := wDataTrace.io.deq.bits.data >> wdatacount
          io.host.out.valid := wDataTrace.io.deq.valid
          wDataTrace.io.deq.ready := io.host.out.ready && toNext
          when(io.host.out.fire()) {
            wdatacount := Mux(toNext, UInt(0), wdatacount + UInt(hostLen))
          }.elsewhen(!io.host.out.valid) {
            traceState := Mux(stepcount.orR, trace_WCOUNT, trace_RCOUNT)
            debugState := Mux(stepcount.orR, debug_STEP, debug_TRACE)    
          }
        }
        is(trace_RCOUNT) {
          io.host.out.bits := PopCount(rAddrValid)
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_RADDR
            raddrcount := UInt(tagNum)
          }
        }
        val id = UInt(tagNum) - raddrcount
        is(trace_RADDR) {
          io.host.out.bits := rAddrTrace(id).addr
          io.host.out.valid := rAddrValid(id) && raddrcount.orR
          when(!raddrcount.orR) {
            traceState := trace_WCOUNT
            debugState := debug_IDLE
          }.elsewhen(io.host.out.fire()) {
            traceState := trace_RTAG
          }.elsewhen(!io.host.out.valid) {
            raddrcount := raddrcount - UInt(1)
          }
        }
        is(trace_RTAG) {
          io.host.out.bits := id
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_RADDR
            raddrcount := raddrcount - UInt(1)
          }
        }
      }
    }

    is(debug_MEM) {
      switch(memState) {
        is(mem_REQ_CMD) {
          io.host.in.ready := addrcount.orR
          memReqCmdQ.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqCmd.addr := (memReqCmd.addr << UInt(hostLen)) | io.host.in.bits
            addrcount := addrcount - UInt(1)
          }
          when(memReqCmdQ.io.enq.fire()) {
            memState := Mux(memReqCmd.rw, mem_REQ_DATA, mem_WAIT)
          }
        }
        is(mem_REQ_DATA) {
          io.host.in.ready := datacount.orR
          memReqDataQ.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqData.data := (memReqData.data << UInt(hostLen)) | io.host.in.bits
            datacount := datacount - UInt(1)
          }
          when(memReqDataQ.io.enq.fire()) {
            memState := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
        is(mem_WAIT) {
          when(io.mem.resp.fire() && io.mem.resp.bits.tag === memReqCmd.tag) {
            memRespQ.io.enq.valid := Bool(false)
            memResp.data := io.mem.resp.bits.data
            memResp.tag := io.mem.resp.bits.tag
            memState := mem_RESP
          }
        }
        is(mem_RESP) {
          io.host.out.valid := datacount.orR
          when(io.host.out.fire()) {
            io.host.out.bits := memResp.data(hostLen-1, 0)
            memResp.data := memResp.data >> UInt(hostLen)
            datacount := datacount - UInt(1)
          }.elsewhen(!io.host.out.valid) {
            memTag := memTag + UInt(1)
            memState := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
      }
    }
  }

  // add custom transforms for daisy chains
  transforms.addTransforms(daisyLen)
}
