package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, HashSet}
import scala.collection.immutable.ListMap

object transforms { 
  private val wrappers = ArrayBuffer[SimWrapper[Module]]()
  private val stallPins = HashMap[Module, Bool]()
  private val daisyPins = HashMap[Module, DaisyBundle]() 
  private val comps = HashMap[Module, List[Module]]()
  private val compsRev = HashMap[Module, List[Module]]()
  private val regs = HashMap[Module, ArrayBuffer[Node]]()
  private val srams = HashMap[Module, ArrayBuffer[Mem[Data]]]()
  private var daisyWidth = 0
  private[strober] var regSnapLen = 0
  private[strober] var sramSnapLen = 0
  private[strober] var sramMaxSize = 0
  private[strober] val inMap = LinkedHashMap[Bits, Int]()
  private[strober] val outMap = LinkedHashMap[Bits, Int]()
  private[strober] val reqMap = LinkedHashMap[Bits, Int]()
  private[strober] val respMap = LinkedHashMap[Bits, Int]()
  private[strober] val inTraceMap = LinkedHashMap[Bits, Int]()
  private[strober] val outTraceMap = LinkedHashMap[Bits, Int]()
  private[strober] val snapOutMap = LinkedHashMap[Bits, Int]()
  private[strober] val nameMap = HashMap[Node, String]()
  private[strober] var targetName = ""

  private[strober] def init[T <: Module](w: SimWrapper[T], stall: Bool) {
    // Add backend passes
    if (wrappers.isEmpty) { 
      Driver.backend.transforms ++= Seq(
        connectIOs,
        Driver.backend.inferAll,
        Driver.backend.computeMemPorts,
        Driver.backend.findConsumers,
        connectStallSignals,
        addRegChains,
        addSRAMChain,
        dumpMaps,
        dumpChains,
        dumpParams
      )
    }

    targetName = Driver.backend.extractClassName(w.target) 
    w.name = targetName + "Wrapper"
    wrappers += w
    stallPins(w.target) = stall
    daisyPins(w) = w.io.daisy
  }

  private def connectIOs(c: Module) {
    c match {
      case w: SimAXI4Wrapper[_] => {
        ChiselError.info("[transforms] connect IOs to AXI busses")
        daisyWidth = w.sim.daisyWidth
        w.name = targetName + "AXI4Wrapper"
        val (memInMap, ioInMap) =
          ListMap((w.sim.io.t_ins.unzip._2 zip w.sim.io.ins):_*) partition (MemIO.ins contains _._1)
        val (memOutMap, ioOutMap) =
          ListMap((w.sim.io.t_outs.unzip._2 zip w.sim.io.outs):_*) partition (MemIO.outs contains _._1)

        def initInConv[T <: Data](gen: T, id: Int) = {
          val conv = w.addModule(new MAXI2Input(gen, id))
          conv.name = "in_conv_" + id
          conv.reset := w.reset_t
          conv.io.addr := w.waddr_r
          conv.io.in.bits := w.io.M_AXI.w.bits.data
          conv.io.in.valid := w.do_write
          w.in_ready(id) := conv.io.in.ready
          conv
        }

        def initOutConv[T <: Data](gen: T, id: Int) = {
          val conv = w.addModule(new Output2MAXI(gen, id))
          conv.name = "out_conv_" + id
          conv.reset := w.reset_t
          conv.io.addr := w.raddr_r
          conv.io.out.ready := w.do_read && w.io.M_AXI.r.ready
          w.out_data(id) := conv.io.out.bits
          w.out_valid(id) := conv.io.out.valid
          conv
        }

        // Inputs
        for (((wire, in), id) <- ioInMap.zipWithIndex) {
          val conv = initInConv(in.bits, id)
          conv.io.out <> in
          inMap(wire) = id
        }
 
        // Outputs
        for (((wire, out), id) <- ioOutMap.zipWithIndex) {
          val conv = initOutConv(out.bits, id)
          conv.io.in <> out
          outMap(wire) = id
        }

        // MemIOs
        val conv = w.addModule(new MAXI_MemIOConverter(ioInMap.size, ioOutMap.size))
        conv.reset := w.reset_t
        conv.io.in_addr := w.waddr_r
        conv.io.out_addr := w.raddr_r

        for ((in, i) <- conv.io.ins.zipWithIndex) {
          val id = ioInMap.size + i
          in.bits := w.io.M_AXI.w.bits.data
          in.valid := w.do_write
          w.in_ready(id) := in.ready
        }
        reqMap(w.memReq.addr) = ioInMap.size
        reqMap(w.memReq.tag) = ioInMap.size + 1
        reqMap(w.memReq.data) = ioInMap.size + 2

        for ((out, i) <- conv.io.outs.zipWithIndex) {
          val id = ioOutMap.size + i
          w.out_data(id) := out.bits
          w.out_valid(id) := out.valid
          out.ready := w.do_read && w.io.M_AXI.r.ready
        }
        respMap(w.memResp.data) = ioOutMap.size
        respMap(w.memResp.tag) = ioOutMap.size + 1

        val arb = w.addModule(new MemArbiter(MemIO.count+1))
        for (i <- 0 until MemIO.count) {
          val conv = w.addModule(new ChannelMemIOConverter)
          conv.name = "mem_conv_" + i
          conv.reset := w.reset_t
          conv.io.req_cmd_ready <> memInMap(MemReqCmd(i)(0))
          conv.io.req_cmd_valid <> memOutMap(MemReqCmd(i)(1))
          conv.io.req_cmd_addr <> memOutMap(MemReqCmd(i)(2))
          conv.io.req_cmd_tag <> memOutMap(MemReqCmd(i)(3))
          conv.io.req_cmd_rw <> memOutMap(MemReqCmd(i)(4))

          conv.io.req_data_ready <> memInMap(MemData(i)(0))
          conv.io.req_data_valid <> memOutMap(MemData(i)(1))
          conv.io.req_data_bits <> memOutMap(MemData(i)(2))

          conv.io.resp_ready <> memOutMap(MemResp(i)(0))
          conv.io.resp_valid <> memInMap(MemResp(i)(1))
          conv.io.resp_data <> memInMap(MemResp(i)(2))
          conv.io.resp_tag <> memInMap(MemResp(i)(3))
          conv.io.mem <> arb.io.ins(i)
        }
        conv.io.mem <> arb.io.ins(MemIO.count)
        arb.io.out <> w.mem

        // Traces
        val inTraces = w.sim.io.t_ins.unzip._2.zipWithIndex filter (MemIO.ins contains _._1) map { 
          case (wire, i) => {
            val channel = w.sim.in_channels(i)
            val trace = w.sim.addPin(Decoupled(wire), wire.name + "_trace")
            trace <> channel.initTrace
            (trace, wire)
          }
        }
        val outTraces = w.sim.io.t_outs.unzip._2.zipWithIndex filter (MemIO.outs contains _._1) map {
          case (wire, i) => {
            val channel = w.sim.out_channels(i)
            val trace = w.sim.addPin(Decoupled(wire), wire.name + "_trace")
            trace <> channel.initTrace
            (trace, wire)
          }
        } 
        for (((trace, wire), i) <- (inTraces ++ outTraces).zipWithIndex) {
          val id = w.sim.io.outs.size - MemIO.outs.size + w.memResp.flatten.size + i
          val conv = initOutConv(trace.bits, id)
          conv.io.in <> trace
          if (MemIO.ins contains wire) inTraceMap(wire) = id else outTraceMap(wire) = id
        }

        // Snapshots
        val snapOut = List(w.sim.io.daisy.regs.out, w.sim.io.daisy.sram.out, w.sim.io.daisy.cntr.out)
        val snapOutBase = w.sim.io.outs.size + w.memResp.flatten.size + MemIO.ins.size
        for ((out, i) <- snapOut.zipWithIndex) {
          val id = snapOutBase + i
          val conv = initOutConv(out.bits, id)
          // conv.io.in <> out
          conv.io.in.bits := out.bits
          conv.io.in.valid := out.valid
          out.ready := conv.io.in.ready
        }
        snapOutMap(w.snapOut.regs) = snapOutBase
        snapOutMap(w.snapOut.sram) = snapOutBase + 1 
        snapOutMap(w.snapOut.sram) = snapOutBase + 2
      }
      case w: SimWrapper[Module] => {
        daisyWidth = w.daisyWidth
        inMap ++= w.ins.unzip._2.zipWithIndex
        outMap ++= w.outs.unzip._2.zipWithIndex
      }
    }
  }

  private def connectStallSignals(c: Module) {
    ChiselError.info("[transforms] connect stall signals")
    // This pass initiate simulation modules
    def collect(c: Module): List[Module] = 
      (c.children foldLeft List[Module]())((res, x) => res ++ collect(x)) ++ List(c)
    def collectRev(c: Module): List[Module] = 
      List(c) ++ (c.children foldLeft List[Module]())((res, x) => res ++ collectRev(x))

    for (w <- wrappers) {
      val t = w.target
      val tName = Driver.backend.extractClassName(t) 
      val tPath = t.getPathName(".")
      comps(w) = collect(t)
      compsRev(w) = collectRev(t)

      def getPath(node: Node) = tName + (node.chiselName stripPrefix tPath)

      for ((_, wire) <- t.wires) { nameMap(wire) = getPath(wire)}

      // Connect the stall signal to the register and memory writes for freezing
      for (m <- compsRev(w)) {
        if (!(stallPins contains m)) { 
          stallPins(m) = m.addPin(Bool(INPUT), "io_stall_t")
          stallPins(m) := stallPins(m.parent)
        }
        if (!(daisyPins contains m)) { 
          daisyPins(m) = m.addPin(new DaisyBundle(daisyWidth), "io_daisy")
          daisyPins(m).sram.restart := daisyPins(m.parent).sram.restart
        }
        regs(m) = ArrayBuffer[Node]()
        srams(m) = ArrayBuffer[Mem[Data]]()
        m bfs { 
          case reg: Reg => { 
            reg.inputs(0) = Multiplex(stallPins(m) && !m.reset, reg, reg.inputs(0))
            // Add the register for daisy chains
            regs(m) += reg
            nameMap(reg) = getPath(reg)
          }
          case mem: Mem[Data] => {
            for (write <- mem.writeAccesses) {
              write cond_= Bool().fromNode(write.cond) && !stallPins(m)
            }
            if (mem.seqRead) {
              srams(m) += mem
              if (sramMaxSize < mem.size) sramMaxSize = mem.size
            } else {
              for (i <- 0 until mem.size) {
                val read = new MemRead(mem, UInt(i)) 
                read.infer
                regs(m) += read
              }
            }
            nameMap(mem) = getPath(mem)
          }
          case _ =>
        } 
      }
    }
  }

  private def addRegChains(c: Module) {
    ChiselError.info("[transforms] add daisy chains for registers")

    val hasRegChain = HashSet[Module]()
    def insertRegChain(m: Module) = {
      val dataWidth = (regs(m) foldLeft 0)(_ + _.needWidth)
      val regChain = if (!regs(m).isEmpty) 
        Some(m.addModule(new RegChain, {case DataWidth => dataWidth})) else None
      regChain match {
        case None =>
        case Some(chain) => {
          var index = 0
          var offset = 0
          for (i <- (0 until chain.daisyLen).reverse) {
            val wires = ArrayBuffer[UInt]()
            var totalWidth = 0
            while (totalWidth < daisyWidth) {
              val totalMargin = daisyWidth - totalWidth
              if (index < regs(m).size) {
                val reg = regs(m)(index)
                val width = reg.needWidth
                val margin = width - offset
                if (margin <= totalMargin) {
                  wires += UInt(reg)(margin-1, 0)
                  totalWidth += margin
                  offset = 0
                  index += 1
                } else {
                  wires += UInt(reg)(margin-1, margin-totalMargin)
                  totalWidth += totalMargin
                  offset += totalMargin
                }
              } else {
                wires += UInt(0, totalMargin)
                totalWidth += totalMargin 
              }
              chain.io.dataIo.data(i) := Cat(wires) 
            }
          }
          chain.io.dataIo.out <> daisyPins(m).regs.out
          chain.io.stall := stallPins(m)
          hasRegChain += m
          regSnapLen += chain.daisyLen
        }
      }      
      regChain
    }

    for (w <- wrappers ; m <- comps(w)) {
      val regChain = insertRegChain(m)
      // Filter children who have reg chains
      var prev: Option[Module] = None
      for (child <- m.children ; if hasRegChain contains child) {
        prev match {
          case None => regChain match {
            case None => daisyPins(m).regs.out <> daisyPins(child).regs.out
            case Some(chain) => chain.io.dataIo.in <> daisyPins(child).regs.out
          }
          case Some(p) => daisyPins(p).regs.in <> daisyPins(child).regs.out
        }
        prev = Some(child)
      }
      prev match {
        case None => regChain match {
          case None => 
          case Some(chain) => chain.io.dataIo.in <> daisyPins(m).regs.in
        }
        case Some(p) => {
          hasRegChain += m
          daisyPins(p).regs.in <> daisyPins(m).regs.in
        }
      }
    }
    for (w <- wrappers) {
      w.io.daisy.regs <> daisyPins(w.target).regs
    }
  }

  def addSRAMChain(c: Module) {
    ChiselError.info("[transforms] add sram chains")
    val hasSRAMChain = HashSet[Module]()
    def insertSRAMChain(m: Module) = {
      var lastChain: Option[SRAMChain] = None 
      for (sram <- srams(m)) {
        val data = sram.readAccesses.last 
        val addr = data match {
          case mr: MemRead => mr.addr.getNode match { case addrReg: Reg => addrReg }
          case msr: MemSeqRead => msr.addrReg
        }
        val dataWidth = sram.needWidth
        val chain = m.addModule(new SRAMChain, {
          case DataWidth => dataWidth 
          case SRAMSize => sram.size }
        )
        chain.io.stall := stallPins(m)
        var high = dataWidth-1
        for (i <- (0 until chain.daisyLen).reverse) {
          val low = math.max(high-daisyWidth+1, 0)
          val widthMargin = daisyWidth-(high-low+1)
          val thisData = UInt(data)(high, low)
          if (widthMargin == 0) {
            chain.io.dataIo.data(i) := thisData
          } else {
            chain.io.dataIo.data(i) := Cat(thisData, UInt(0, widthMargin))
          }
          high -= daisyWidth
        }
        lastChain match {
          case None => daisyPins(m).sram.out <> chain.io.dataIo.out
          case Some(last) => last.io.dataIo.in <> chain.io.dataIo.out
        }
        chain.io.restart := daisyPins(m).sram.restart
        // Connect chain addr to SRAM addr
        chain.io.addrIo.in := UInt(addr)
        addr.inputs(0) = Multiplex(chain.io.addrIo.out.valid, 
                                   chain.io.addrIo.out.bits, addr.inputs(0))
        lastChain = Some(chain)
        sramSnapLen += chain.daisyLen
      }
      if (lastChain != None) hasSRAMChain += m
      lastChain
    }

    for (w <- wrappers ; m <- comps(w)) {
      val sramChain = insertSRAMChain(m)   
      // Filter children who have sram chains
      var prev: Option[Module] = None
      for (child <- m.children ; if hasSRAMChain contains child) {
        prev match {
          case None => sramChain match {
            case None => daisyPins(m).sram.out <> daisyPins(child).sram.out
            case Some(chain) => chain.io.dataIo.in <> daisyPins(child).sram.out
          }
          case Some(p) => daisyPins(p).sram.in <> daisyPins(child).sram.out
        }
        prev = Some(child)
      }
      prev match {
        case None => sramChain match {
          case None =>
          case Some(chain) => daisyPins(m).sram.in <> chain.io.dataIo.in
        }
        case Some(p) => {
          hasSRAMChain += m
          daisyPins(m).sram.in <> daisyPins(p).sram.in
        }
      }
    } 
    for (w <- wrappers) {
      w.io.daisy.sram <> daisyPins(w.target).sram
    }
  }

 
  private def dumpMaps(c: Module) {
    object MapType extends Enumeration { val IoIn, IoOut, MemIn, MemOut, InTrace, OutTrace = Value }
    ChiselError.info("[transforms] dump the io & mem mapping")
    val res = new StringBuilder
    for ((in, id) <- inMap) {
      val path = nameMap(in)
      val width = in.needWidth
      res append "%d %s %d %d\n".format(MapType.IoIn.id, path, id, width)
    }
    for ((out, id) <- outMap) {
      val path = nameMap(out)
      val width = out.needWidth
      res append "%d %s %d %d\n".format(MapType.IoOut.id, path, id, width)
    }
    for ((req, id) <- reqMap) {
      val width = req.needWidth
      res append "%d %s %d %d\n".format(MapType.MemIn.id, req.name, id, width)
    }
    for ((resp, id) <- respMap) {
      val width = resp.needWidth
      res append "%d %s %d %d\n".format(MapType.MemOut.id, resp.name, id, width)
    }
    for ((in, id) <- inTraceMap) {
      val path = nameMap(in)
      val width = in.needWidth
      res append "%d %s %d %d\n".format(MapType.InTrace.id, path, id, width)
    }
    for ((out, id) <- outTraceMap) {
      val path = nameMap(out)
      val width = out.needWidth
      res append "%d %s %d %d\n".format(MapType.OutTrace.id, path, id, width)
    }

    val file = Driver.createOutputFile(targetName + ".map")
    try {
      file write res.result
    } finally {
      file.close
      res.clear
    }
  }

  private def dumpChains(c: Module) {
    ChiselError.info("[transforms] dump the chain mapping")
    object ChainType extends Enumeration { val Regs, SRAM, Counter = Value }
    val res = new StringBuilder
    for (w <- wrappers ; m <- compsRev(w)) {
      var chainWidth = 0
      var dataWidth = 0
      for (reg <- regs(m)) {
        val (node, width, off) = reg match {
          case read: MemRead => 
            (read.mem, read.needWidth, Some(read.addr.litValue(0).toInt))
          case _ => 
            (reg, reg.needWidth, None)
        }
        Sample.addToChains(Some(node), width, off) // for testers
        res append "%d %s %d %d\n".format(ChainType.Regs.id, nameMap(node), off.getOrElse(-1), width)
        dataWidth += width
        while (chainWidth < dataWidth) chainWidth += daisyWidth
      }
      val padWidth = chainWidth - dataWidth
      if (padWidth > 0) {
        Sample.addToChains(None, padWidth) // for testers
        res append "%d null %d\n".format(ChainType.Regs.id, padWidth)
      }
    }
    for (i <- 0 until sramMaxSize ; w <- wrappers ; m <- compsRev(w)) {
      var chainWidth = 0
      for (sram <- srams(m)) {
        val path = nameMap(sram)
        val dataWidth = sram.needWidth
        var chainWidth = 0
        if (i < sram.size) { 
          Sample.addToChains(Some(sram), dataWidth, Some(i)) // for testers
          res append "%d %s[%d] %d\n".format(ChainType.SRAM.id, path, i, dataWidth)
        } else { 
          Sample.addToChains(None, dataWidth) // for testers
          res append "%d null %d\n".format(ChainType.SRAM.id, dataWidth)
        }
        while (chainWidth < dataWidth) chainWidth += daisyWidth
        val padWidth = chainWidth - dataWidth
        if (padWidth > 0) {
          Sample.addToChains(None, padWidth) // for testers
          res append "%d null %d\n".format(ChainType.SRAM.id, padWidth)
        }
      }
    }

    val file = Driver.createOutputFile(targetName + ".chain")
    try {
      file write res.result
    } finally {
      file.close
      res.clear
    }
  }

  // Todo: move this path to the ChiselBackend
  private def dumpParams(c: Module) {
    if (Driver.chiselConfigMode != None && 
        Driver.chiselConfigMode.get != "instance" &&
        Driver.chiselConfigDump && !Dump.dump.isEmpty) {
      val w = Driver.createOutputFile(targetName + ".prm")
      w.write(Dump.getDump)
      w.close
    }
  }
}

