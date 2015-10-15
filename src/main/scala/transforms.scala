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

  private val regs  = HashMap[Module, ArrayBuffer[Node]]()
  private val trs   = HashMap[Module, ArrayBuffer[Node]]()
  private val srams = HashMap[Module, ArrayBuffer[Mem[Data]]]()
  private val cntrs = HashMap[Module, ArrayBuffer[Node]]()
  private def chain(t: ChainType.Value) = t match {
    case ChainType.Regs => regs
    case ChainType.Trs  => trs
    case ChainType.SRAM => srams
    case ChainType.Cntr => cntrs
  }
  private[strober] val chainLen = HashMap(
    ChainType.Regs -> 0,
    ChainType.Trs  -> 0,
    ChainType.SRAM -> 0,
    ChainType.Cntr -> 0)
  private[strober] val chainSize = HashMap(
    ChainType.Regs -> 0,
    ChainType.Trs  -> 0,
    ChainType.SRAM -> 0,
    ChainType.Cntr -> 0)
  
  private[strober] var sampleNum = 0
  private[strober] var daisyWidth = 0
  private[strober] var channelWidth = 0
  private[strober] var traceLen = 0

  private[strober] val inMap = LinkedHashMap[Bits, Int]()
  private[strober] val outMap = LinkedHashMap[Bits, Int]()
  private[strober] val inTraceMap = LinkedHashMap[Bits, Int]()
  private[strober] val outTraceMap = LinkedHashMap[Bits, Int]()
  private[strober] val nameMap = HashMap[Node, String]()
  private[strober] var targetName = ""
  
  private[strober] def init[T <: Module](w: SimWrapper[T], fire: Bool) {
    // Add backend passes
    if (wrappers.isEmpty) { 
      Driver.backend.transforms ++= Seq(
        Driver.backend.inferAll,
        Driver.backend.computeMemPorts,
        connectCtrlSignals,
        addDaisyChains(ChainType.Regs),
        addDaisyChains(ChainType.Trs),
        addDaisyChains(ChainType.SRAM),
        dumpMaps,
        dumpChains,
        dumpConsts
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

  private def connectCtrlSignals(c: Module) {
    ChiselError.info("[transforms] connect control signals")
    def collect(c: Module): List[Module] = 
      (c.children foldLeft List[Module]())((res, x) => res ++ collect(x)) ++ List(c)
    def collectRev(c: Module): List[Module] = 
      List(c) ++ (c.children foldLeft List[Module]())((res, x) => res ++ collectRev(x))

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
      comps(w) = collect(t)
      compsRev(w) = collectRev(t)
      def getPath(node: Node) = tName + (node.chiselName stripPrefix tPath)
      for ((_, wire) <- t.wires) { nameMap(wire) = getPath(wire) }
      // Connect the stall signal to the register and memory writes for freezing
      for (m <- compsRev(w)) {
        regs(m)  = ArrayBuffer[Node]()
        trs(m)   = ArrayBuffer[Node]()
        srams(m) = ArrayBuffer[Mem[Data]]()
        cntrs(m) = ArrayBuffer[Node]()

        if (!(daisyPins contains m)) { 
          daisyPins(m) = m.addPin(new DaisyBundle(daisyWidth), "io_daisy")
        }

        m bfs { 
          case mem: Mem[_] if mem.seqRead => 
            if (!(stallPins contains m)) connectStall(m)
            chainSize(ChainType.Trs)  = math.max(chainSize(ChainType.Trs), 1)
            chainSize(ChainType.SRAM) = math.max(chainSize(ChainType.SRAM), mem.size)
          case _: Delay =>
            if (!(stallPins contains m)) connectStall(m)
            chainSize(ChainType.Regs) = 1 
          case _ =>
        }

        val sramAddrs = HashMap[Mem[_], Vector[Reg]]()
        m bfs { 
          case reg: Reg =>  
            reg.inputs(0) = Multiplex(stallPins(m) && !m.reset, reg, reg.inputs(0))
            regs(m) += reg
            nameMap(reg) = getPath(reg)
          case mem: Mem[_] => 
            if (mem.seqRead) {
              srams(m) += mem.asInstanceOf[Mem[Data]]
              sramAddrs(mem) = Vector.fill(chainSize(ChainType.Trs))(findSRAMRead(mem)._1)
            } else (0 until mem.size) map (UInt(_)) foreach { idx =>
              val read = new MemRead(mem, idx) 
              read.infer
              regs(m) += read
            }
            for (write <- mem.writeAccesses) 
              write.cond = Bool().fromNode(write.cond) && !stallPins(m)
            nameMap(mem) = getPath(mem)
          case assert: Assert =>
            assert.cond = Bool().fromNode(assert.cond) && !stallPins(m)
            m.debug(assert.cond)
          case printf: Printf =>
            printf.cond = Bool().fromNode(printf.cond) && !stallPins(m)
            m.debug(printf.cond)
          case _ =>
        }
        if (!srams(m).isEmpty) connectSRAMRestart(m) 
        for (i <- 0 until chainSize(ChainType.Trs) ; sram <- srams(m)) {
          val addr = sramAddrs(sram)(i)
          regs(m) -= addr
          trs(m) += addr
          nameMap(addr) = getPath(sram)
        }
      }
    }
  }

  private def addDaisyChains(chainType: ChainType.Value) = (c: Module) => if (chainSize(chainType) > 0) {
    ChiselError.info("[transforms] add daisy chains for " + (chainType match {
      case ChainType.Regs => "registers"
      case ChainType.Trs  => "traces"
      case ChainType.SRAM => "SRAMs"
      case ChainType.Cntr => "counters"
    }))
  
    val hasChain = HashSet[Module]()

    def insertRegChain(m: Module) = if (chain(chainType)(m).isEmpty) None else {
      val width = (chain(chainType)(m) foldLeft 0)(_ + _.needWidth)
      val daisy = m.addModule(new RegChain,  {case DataWidth => width})
      ((0 until daisy.daisyLen) foldRight (0, 0)){ case (i, (index, offset)) =>
        def loop(total: Int, index: Int, offset: Int, wires: Seq[UInt]): (Int, Int, Seq[UInt]) = {
          val margin = daisyWidth - total
          if (margin == 0) {
            (index, offset, wires)
          } else if (index < chain(chainType)(m).size) {
            val reg = chain(chainType)(m)(index)
            val width = reg.needWidth - offset
            if (width <= margin) {
              loop(total + width, index + 1, 0, wires :+ UInt(reg)(width-1,0))
            } else {
              loop(total + margin, index, offset + margin, wires :+ UInt(reg)(width-1, width-margin)) 
            }
          } else {
            loop(total + margin, index, offset, wires :+ UInt(0, margin))
          }
        }
        val (idx, off, wires) = loop(0, index, offset, Seq())
        daisy.io.dataIo.data(i) := Cat(wires)
        (idx, off)
      } 
      daisy.io.dataIo.out <> daisyPins(m)(chainType).out
      daisy.io.stall := stallPins(m)
      hasChain += m
      chainLen(chainType) += daisy.daisyLen
      Some(daisy)
    }

    def insertSRAMChain(m: Module) = {
      val sramChain = (srams(m) foldLeft (None: Option[SRAMChain])){ case (lastChain, sram) =>
        val (addr, data) = findSRAMRead(sram)
        val width = sram.needWidth
        val daisy = m.addModule(new SRAMChain, { case DataWidth => width case SRAMSize => sram.size })
        daisy.io.stall := stallPins(m)
        ((0 until daisy.daisyLen) foldRight (width-1)){ case (i, high) =>
          val low = math.max(high-daisyWidth+1, 0)
          val margin = daisyWidth-(high-low+1)
          val daisyIn = UInt(data)(high, low)
          if (margin == 0) {
            daisy.io.dataIo.data(i) := daisyIn
          } else {
            daisy.io.dataIo.data(i) := Cat(daisyIn, UInt(0, margin))
          }
          high - daisyWidth
        }
        lastChain match {
          case None => daisyPins(m).sram.out <> daisy.io.dataIo.out
          case Some(last) => last.io.dataIo.in <> daisy.io.dataIo.out
        }
        daisy.io.restart := daisyPins(m).sram.restart
        // Connect daisy addr to SRAM addr
        daisy.io.addrIo.in := UInt(addr)
        addr.inputs(0) = Multiplex(daisy.io.addrIo.out.valid, daisy.io.addrIo.out.bits, addr.inputs(0))
        chainLen(chainType) += daisy.daisyLen
        Some(daisy)
      }
      if (sramChain != None) hasChain += m
      sramChain
    }

    for (w <- wrappers ; m <- comps(w)) {
      val daisy = chainType match {
        case ChainType.SRAM => insertSRAMChain(m)
        case _              => insertRegChain(m)
      }
      // Filter children who have daisy chains
      (m.children filter (hasChain(_)) foldLeft (None: Option[Module])){ case (prev, child) =>
        prev match {
          case None => daisy match {
            case None => daisyPins(m)(chainType).out <> daisyPins(child)(chainType).out
            case Some(chain) => chain.io.dataIo.in <> daisyPins(child)(chainType).out
          }
          case Some(p) => daisyPins(p)(chainType).in <> daisyPins(child)(chainType).out
        }
        Some(child)
      } match {
        case None => daisy match {
          case None => 
          case Some(chain) => chain.io.dataIo.in <> daisyPins(m)(chainType).in
        }
        case Some(p) => {
          hasChain += m
          daisyPins(p)(chainType).in <> daisyPins(m)(chainType).in
        }
      }
    }
    for (w <- wrappers) {
      w.io.daisy(chainType) <> daisyPins(w.target)(chainType)
    }
  }

  private val dumpMaps: Module => Unit = {
    case w: NASTIShim[SimNetwork] if Driver.chiselConfigDump => 
      object MapType extends Enumeration { val IoIn, IoOut, InTrace, OutTrace = Value }
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
    val res = new StringBuilder
    def dump(chain_t: ChainType.Value, state: Option[Node], width: Int, off: Option[Int]) = {
      val path = state match { case Some(p) => nameMap(p) case None => "null" }
      s"${chain_t.id} ${path} ${width} ${off getOrElse -1}\n" 
    } 

    def addPad(t: ChainType.Value, cw: Int, dw: Int) {
      val pad = cw - dw
      if (pad > 0) {
        Sample.addToChain(t, None, pad, None)
        res   append dump(t, None, pad, None)
      }
    }

    List(ChainType.SRAM, ChainType.Trs, ChainType.Regs) foreach { t =>
      for (i <- 0 until chainSize(t) ; w <- wrappers ; m <- compsRev(w)) {
        val (cw, dw) = (chain(t)(m) foldLeft (0, 0)){case ((chainWidth, dataWidth), state) =>
          val width = state.needWidth
          val dw = dataWidth + width
          val cw = (Stream.from(0) map (chainWidth + _ * daisyWidth) dropWhile (_ < dw)).head
          val (node, off) = state match {
            case sram: Mem[_] if sram.seqRead && sram.size > i =>
              (Some(sram), Some(i))
            case sram: Mem[_] if sram.seqRead =>
              (None, None)
            case read: MemRead => 
              (Some(read.mem.asInstanceOf[Mem[Data]]), Some(read.addr.litValue(0).toInt))
            case _ => 
              (Some(state), None)
          }
          Sample.addToChain(t, node, width, off) // for tester
          res   append dump(t, node, width, off)
          if (t == ChainType.SRAM) {
            addPad(t, cw, dw)
            (0, 0)
          } else {
            (cw, dw)
          }
        }
        if (t != ChainType.SRAM) addPad(t, cw, dw)
      } 
    }

    c match { 
      case _: NASTIShim[SimNetwork] if Driver.chiselConfigDump =>
        val file = Driver.createOutputFile(targetName + ".chain")
        try {
          file write res.result
        } finally {
          file.close
          res.clear
        }
      case _ => 
    }
  }

  private val dumpConsts: Module => Unit = {
    case w: NASTIShim[SimNetwork] if Driver.chiselConfigDump => 
      ChiselError.info("[transforms] dump constant header")
      def dump(arg: (String, Int)) = s"#define ${arg._1} ${arg._2}\n"
      val consts = List(
        "MEM_BLOCK_OFFSET"  -> w.memBlockOffset,
        "MEM_DATA_CHUNK"    -> w.sim.io.chunk(w.mem.resp.bits.data),
        "CHANNEL_OFFSET"    -> log2Up(channelWidth),
        "REG_SNAP_LEN"      -> chainLen(ChainType.Regs),
        "TRACE_SNAP_LEN"    -> chainLen(ChainType.Trs),
        "SRAM_SNAP_LEN"     -> chainLen(ChainType.SRAM),
        "SRAM_SNAP_SIZE"    -> chainSize(ChainType.SRAM),
        "WARM_CYCLES"       -> chainSize(ChainType.Trs),
        
        "POKE_SIZE"         -> w.master.io.ins.size,
        "PEEK_SIZE"         -> w.master.io.outs.size,

        "RESET_ADDR"        -> w.master.resetAddr,
        "SRAM_RESTART_ADDR" -> w.master.sramRestartAddr,
        "SNAP_OUT_REGS"     -> w.master.snapOutMap(w.sim.io.daisy.regs.out),
        "SNAP_OUT_TRACE"    -> w.master.snapOutMap(w.sim.io.daisy.trace.out),
        "SNAP_OUT_SRAM"     -> w.master.snapOutMap(w.sim.io.daisy.sram.out),
        // "SNAP_OUT_CNTR"     -> w.master.snapOutMap(w.sim.io.daisy.cntr.out),

        "MEM_REQ_ADDR"      -> w.master.reqMap(w.mem.req_cmd.bits.addr),
        "MEM_REQ_TAG"       -> w.master.reqMap(w.mem.req_cmd.bits.tag),
        "MEM_REQ_RW"        -> w.master.reqMap(w.mem.req_cmd.bits.rw),
        "MEM_REQ_DATA"      -> w.master.reqMap(w.mem.req_data.bits.data),
        "MEM_RESP_DATA"     -> w.master.respMap(w.mem.resp.bits.data),
        "MEM_RESP_TAG"      -> w.master.respMap(w.mem.resp.bits.tag)
      )

      val sb = new StringBuilder
      val Param = """\(([\w_]+),([\w_]+)\)""".r
      sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
      sb append "#define __%s_H\n".format(targetName.toUpperCase)
      (Dump.getDump split '\n') foreach {
        case Param(p, v) => sb append dump(p, v.toInt)
        case _ =>
      }
      consts foreach (sb append dump(_)) 
      sb append "#endif  // __%s_H\n".format(targetName.toUpperCase)

      val file = Driver.createOutputFile(targetName + "-const.h")
      try {
        file.write(sb.result)
      } finally {
        file.close
        sb.clear
      }
    case _ =>
  }
}

