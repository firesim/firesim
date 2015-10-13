package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, HashSet}
import scala.util.matching.Regex

object findSRAMRead {
  def apply[T <: Data](sram: Mem[T]) = {
    val data = if (!Driver.isInlineMem) sram.readAccesses.last
      else (sram.readAccesses find (_.addr.getNode match { 
        case _: Reg => true 
        case _      => false })).get
    val addr = data match {
      case mr:  MemRead    => mr.addr.getNode match { case addrReg: Reg => addrReg }
      case msr: MemSeqRead => msr.addrReg
    }
    (addr, data)
  }
}

object transforms { 
  private val wrappers = ArrayBuffer[SimWrapper[Module]]()
  private val stallPins = HashMap[Module, Bool]()
  private val daisyPins = HashMap[Module, DaisyBundle]() 
  private val comps = HashMap[Module, List[Module]]()
  private val compsRev = HashMap[Module, List[Module]]()
  private val regs = HashMap[Module, ArrayBuffer[Node]]()
  private val traces = HashMap[Module, ArrayBuffer[Node]]()
  private val srams = HashMap[Module, ArrayBuffer[Mem[Data]]]()

  private[strober] var sampleNum = 0
  private[strober] var daisyWidth = 0
  private[strober] var channelWidth = 0
  private[strober] var traceLen = 0
  private[strober] var regSnapLen = 0
  private[strober] var traceSnapLen = 0
  private[strober] var sramSnapLen = 0
  private[strober] var hasRegs = false
  private[strober] var sramMaxSize = 0
  private[strober] var warmCycles = 0

  private[strober] val inMap = LinkedHashMap[Bits, Int]()
  private[strober] val outMap = LinkedHashMap[Bits, Int]()
  private[strober] val inTraceMap = LinkedHashMap[Bits, Int]()
  private[strober] val outTraceMap = LinkedHashMap[Bits, Int]()
  private[strober] val miscInMap = LinkedHashMap[Bits, Int]()
  private[strober] val miscOutMap = LinkedHashMap[Bits, Int]()
  private[strober] val nameMap = HashMap[Node, String]()
  private[strober] var targetName = ""
  
  private[strober] def init[T <: Module](w: SimWrapper[T], fire: Bool) {
    // Add backend passes
    if (wrappers.isEmpty) { 
      Driver.backend.transforms ++= Seq(
        probeDesign,
        Driver.backend.inferAll,
        Driver.backend.computeMemPorts,
        connectCtrlSignals,
        addRegChains,
        addTraceChains,
        addSRAMChain,
        dumpMaps,
        dumpChains,
        dumpParams
      )
    }

    sampleNum    = w.sampleNum
    daisyWidth   = w.daisyWidth
    channelWidth = w.channelWidth
    traceLen     = w.traceLen
    targetName   = Driver.backend.extractClassName(w.target) 
    w.name       = targetName + "Wrapper"
    wrappers    += w
    stallPins(w) = !fire
    daisyPins(w) = w.io.daisy
  }

  private[strober] def init[T <: Module](w: NASTIShim[SimNetwork]) {
    w.name = targetName + "NASTIShim"
  }

  private[strober] def probeDesign(c: Module) {
    def collect(c: Module): List[Module] = 
      (c.children foldLeft List[Module]())((res, x) => res ++ collect(x)) ++ List(c)
    def collectRev(c: Module): List[Module] = 
      List(c) ++ (c.children foldLeft List[Module]())((res, x) => res ++ collectRev(x))

    for (w <- wrappers) {
      val t = w.target
      comps(w) = collect(t)
      compsRev(w) = collectRev(t)
      for (m <- compsRev(w)) {
        m bfs { 
          case reg: Reg => hasRegs = true
          case mem: Mem[_] if mem.seqRead => 
            warmCycles  = math.max(1, warmCycles)
            sramMaxSize = math.max(mem.size, sramMaxSize)
          case mem: Mem[_] => hasRegs = true
          case _ =>
        }
      }
    }
  }

  private def connectCtrlSignals(c: Module) {
    ChiselError.info("[transforms] connect control signals")

    def connectStall(m: Module) {
      stallPins(m) = m.addPin(Bool(INPUT), "io_stall_t")
      val p = m.parent
      if (!(stallPins contains p)) connectStall(p)
      stallPins(m) := stallPins(p)
    }

    def connectSRAMRestart(m: Module): Unit = m.parent match {
      case p if daisyPins contains p =>
        if (p != c && daisyPins(p).sram.restart.inputs.isEmpty)
          connectSRAMRestart(p)
        daisyPins(m).sram.restart := daisyPins(p).sram.restart
      case _ =>
    }

    for (w <- wrappers) {
      val t = w.target
      val tName = Driver.backend.extractClassName(t) 
      val tPath = t.getPathName(".")
      def getPath(node: Node) = tName + (node.chiselName stripPrefix tPath)
      for ((_, wire) <- t.wires) { nameMap(wire) = getPath(wire) }
      // Connect the stall signal to the register and memory writes for freezing
      for (m <- compsRev(w)) {
        regs(m) = ArrayBuffer[Node]()
        traces(m) = ArrayBuffer[Node]()
        srams(m) = ArrayBuffer[Mem[Data]]()
        if (!(daisyPins contains m)) { 
          daisyPins(m) = m.addPin(new DaisyBundle(daisyWidth), "io_daisy")
        }
        val sramAddrs = HashMap[Mem[_], Vector[Reg]]()
        m bfs { 
          case reg: Reg =>  
            if (!(stallPins contains m)) connectStall(m)
            reg.inputs(0) = Multiplex(stallPins(m) && !m.reset, reg, reg.inputs(0))
            regs(m) += reg
            nameMap(reg) = getPath(reg)
          case mem: Mem[_] => 
            if (!(stallPins contains m)) connectStall(m)
            if (mem.seqRead) {
              srams(m) += mem.asInstanceOf[Mem[Data]]
              sramAddrs(mem) = Vector.fill(warmCycles)(findSRAMRead(mem)._1)
            } else (0 until mem.size) map (UInt(_)) foreach { idx =>
              val read = new MemRead(mem, idx) 
              read.infer
              regs(m) += read
            }
            for (write <- mem.writeAccesses) 
              write.cond = Bool().fromNode(write.cond) && !stallPins(m)
            nameMap(mem) = getPath(mem)
          case _ =>
        }
        if (!srams(m).isEmpty) connectSRAMRestart(m) 
        for (i <- 0 until warmCycles ; sram <- srams(m)) {
          val addr = sramAddrs(sram)(i)
          regs(m) -= addr
          traces(m) += addr
          nameMap(addr) = getPath(sram)
        }
      }
    }
  }

  private def addRegChains(c: Module) = if (hasRegs) {
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

  private def addTraceChains(c: Module) = if (warmCycles > 0) {
    ChiselError.info("[transforms] add daisy chains for input traces")

    val hasTraceChain = HashSet[Module]()
    def insertTraceChain(m: Module) = {
      val dataWidth = (traces(m) foldLeft 0)(_ + _.needWidth)
      val traceChain = if (!traces(m).isEmpty) 
        Some(m.addModule(new RegChain, {case DataWidth => dataWidth})) else None
      traceChain match {
        case None =>
        case Some(chain) => {
          var index = 0
          var offset = 0
          for (i <- (0 until chain.daisyLen).reverse) {
            val wires = ArrayBuffer[UInt]()
            var totalWidth = 0
            while (totalWidth < daisyWidth) {
              val totalMargin = daisyWidth - totalWidth
              if (index < traces(m).size) {
                val trace = traces(m)(index)
                val width = trace.needWidth
                val margin = width - offset
                if (margin <= totalMargin) {
                  wires += UInt(trace)(margin-1, 0)
                  totalWidth += margin
                  offset = 0
                  index += 1
                } else {
                  wires += UInt(trace)(margin-1, margin-totalMargin)
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
          chain.io.dataIo.out <> daisyPins(m).trace.out
          chain.io.stall := stallPins(m)
          hasTraceChain += m
          traceSnapLen += chain.daisyLen
        }
      }      
     traceChain
    }

    for (w <- wrappers ; m <- comps(w)) {
      val regChain = insertTraceChain(m)
      // Filter children who have reg chains
      var prev: Option[Module] = None
      for (child <- m.children ; if hasTraceChain contains child) {
        prev match {
          case None => regChain match {
            case None => daisyPins(m).trace.out <> daisyPins(child).trace.out
            case Some(chain) => chain.io.dataIo.in <> daisyPins(child).trace.out
          }
          case Some(p) => daisyPins(p).trace.in <> daisyPins(child).trace.out
        }
        prev = Some(child)
      }
      prev match {
        case None => regChain match {
          case None => 
          case Some(chain) => chain.io.dataIo.in <> daisyPins(m).trace.in
        }
        case Some(p) => {
          hasTraceChain += m
          daisyPins(p).trace.in <> daisyPins(m).trace.in
        }
      }
    }
    for (w <- wrappers) {
      w.io.daisy.trace <> daisyPins(w.target).trace
    } 
  }

  def addSRAMChain(c: Module) = if (sramMaxSize > 0) {
    ChiselError.info("[transforms] add sram chains")
    val hasSRAMChain = HashSet[Module]()
    def insertSRAMChain(m: Module) = {
      var lastChain: Option[SRAMChain] = None 
      for (sram <- srams(m)) {
        val (addr, data) = findSRAMRead(sram)
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

  object MapType extends Enumeration { val IoIn, IoOut, InTrace, OutTrace = Value }
  private val dumpMaps: Module => Unit = {
    case w: NASTIShim[SimNetwork] if Driver.chiselConfigDump => 
      ChiselError.info("[transforms] dump io & mem mapping")
      def dump(map_t: MapType.Value, arg: (Bits, Int)) = arg match { 
        case (wire, id) => s"${map_t.id} ${nameMap(wire)} ${id} ${w.sim.io.chunk(wire)}\n"} 

      val res = new StringBuilder
      res append (w.master.inMap    map {dump(MapType.IoIn,     _)} mkString "")
      res append (w.master.outMap   map {dump(MapType.IoOut,    _)} mkString "")
      res append (w.master.inTrMap  map {dump(MapType.InTrace,  _)} mkString "")
      res append (w.master.outTrMap map {dump(MapType.OutTrace, _)} mkString "")

      val file = Driver.createOutputFile(targetName + ".map")
      try {
        file write res.result
      } finally {
        file.close
        res.clear
      }
    case _ =>
  }

  private def dumpChains(c: Module) {
    ChiselError.info("[transforms] dump chain mapping")
    object ChainType extends Enumeration { val Regs, Traces, SRAM, Cntr = Value }
    val res = new StringBuilder

    for (i <- 0 until sramMaxSize ; w <- wrappers ; m <- compsRev(w)) {
      var chainWidth = 0
      for (sram <- srams(m)) {
        val path = nameMap(sram)
        val dataWidth = sram.needWidth
        var chainWidth = 0
        if (i < sram.size) { 
          Sample.addToSRAMChain(Some(sram), dataWidth, Some(i)) // for testers
          res append "%d %s %d %d\n".format(ChainType.SRAM.id, path, dataWidth, i)
        } else { 
          Sample.addToSRAMChain(None, dataWidth) // for testers
          res append "%d null %d -1\n".format(ChainType.SRAM.id, dataWidth)
        }
        while (chainWidth < dataWidth) chainWidth += daisyWidth
        val padWidth = chainWidth - dataWidth
        if (padWidth > 0) {
          Sample.addToSRAMChain(None, padWidth) // for testers
          res append "%d null %d -1\n".format(ChainType.SRAM.id, padWidth)
        }
      }
    }

    for (w <- wrappers ; m <- compsRev(w)) {
      var chainWidth = 0
      var dataWidth = 0
      for (trace <- traces(m)) {
        val (node, width) = (trace, trace.needWidth)
        Sample.addToTraceChain(Some(node), width) // for testers
        res append "%d %s %d -1\n".format(ChainType.Traces.id, nameMap(node), width)
        dataWidth += width
        while (chainWidth < dataWidth) chainWidth += daisyWidth
      }
      val padWidth = chainWidth - dataWidth
      if (padWidth > 0) {
        Sample.addToTraceChain(None, padWidth) // for testers
        res append "%d null %d -1\n".format(ChainType.Traces.id, padWidth)
      }
    }

    for (w <- wrappers ; m <- compsRev(w)) {
      var chainWidth = 0
      var dataWidth = 0
      for (reg <- regs(m)) {
        val (node, width, off) = reg match {
          case read: MemRead => 
            (read.mem.asInstanceOf[Mem[Data]], read.needWidth, Some(read.addr.litValue(0).toInt))
          case _ => 
            (reg, reg.needWidth, None)
        }
        Sample.addToRegChain(Some(node), width, off) // for testers
        res append "%d %s %d %d\n".format(ChainType.Regs.id, nameMap(node), width, off.getOrElse(-1))
        dataWidth += width
        while (chainWidth < dataWidth) chainWidth += daisyWidth
      }
      val padWidth = chainWidth - dataWidth
      if (padWidth > 0) {
        Sample.addToRegChain(None, padWidth) // for testers
        res append "%d null %d -1\n".format(ChainType.Regs.id, padWidth)
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

  private val dumpParams: Module => Unit = {
    case w: NASTIShim[SimNetwork] if Driver.chiselConfigDump => 
      ChiselError.info("[transforms] dump param header")
      val Param = """\(([\w_]+),([\w_]+)\)""".r
      val sb = new StringBuilder
      sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
      sb append "#define __%s_H\n".format(targetName.toUpperCase)
      (Dump.getDump split '\n') foreach {
        case Param(p, v) => sb append "#define %s %s\n".format(p, v)
        case _ =>
      }
      sb append "#define MEM_BLOCK_OFFSET %d\n".format(w.memBlockOffset)
      sb append "#define CHANNEL_OFFSET %d\n".format(log2Up(channelWidth))
      sb append "#define REG_SNAP_LEN %d\n".format(regSnapLen) 
      sb append "#define TRACE_SNAP_LEN %d\n".format(traceSnapLen) 
      sb append "#define SRAM_SNAP_LEN %d\n".format(sramSnapLen) 
      sb append "#define SRAM_MAX_SIZE %d\n".format(sramMaxSize) 
      // addrs
      sb append "#define RESET_ADDR %d\n".format(w.master.resetAddr)
      sb append "#define SRAM_RESTART_ADDR %d\n".format(w.master.sramRestartAddr)
      sb append "#define SNAP_OUT_REGS %d\n".format(w.master.snapOutMap(w.sim.io.daisy.regs.out)) 
      sb append "#define SNAP_OUT_TRACE %d\n".format(w.master.snapOutMap(w.sim.io.daisy.trace.out))
      sb append "#define SNAP_OUT_SRAM %d\n".format(w.master.snapOutMap(w.sim.io.daisy.sram.out)) 
      // sb append "#define SNAP_OUT_CNTR %d\n".format(miscOutMap(w.snap_out.cntr)) 
      sb append "#define MEM_REQ_ADDR %d\n".format(w.master.reqMap(w.mem.req_cmd.bits.addr))
      sb append "#define MEM_REQ_TAG %d\n".format(w.master.reqMap(w.mem.req_cmd.bits.tag))
      sb append "#define MEM_REQ_RW %d\n".format(w.master.reqMap(w.mem.req_cmd.bits.rw))
      sb append "#define MEM_REQ_DATA %d\n".format(w.master.reqMap(w.mem.req_data.bits.data))
      sb append "#define MEM_RESP_DATA %d\n".format(w.master.respMap(w.mem.resp.bits.data))
      sb append "#define MEM_RESP_TAG %d\n".format(w.master.respMap(w.mem.resp.bits.tag))
      sb append "#define MEM_DATA_CHUNK %d\n".format(w.sim.io.chunk(w.mem.resp.bits.data))
      
      // snapshot info
      if (warmCycles > 0) sb append "#define WARM_CYCLES %d\n".format(warmCycles)
      sb append "#endif  // __%s_H\n".format(targetName.toUpperCase)

      val file = Driver.createOutputFile(targetName + "-param.h")
      try {
        file.write(sb.result)
      } finally {
        file.close
        sb.clear
      }
    case _ =>
  }
}

