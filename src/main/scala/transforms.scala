package strober

import Chisel._
import cde.Parameters
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, HashSet}

object transforms { 
  private val wrappers  = ArrayBuffer[SimWrapper[Module]]()
  private val fires     = HashMap[Module, Bool]()
  private val resets    = HashMap[Module, Bool]() 
  private val daisyPins = HashMap[Module, DaisyBundle]() 
  private val comps     = HashMap[Module, List[Module]]()
  private val compsRev  = HashMap[Module, List[Module]]()
  private val noSnap    = HashSet[Module]()

  private val chains = Map(
    ChainType.Trs  -> HashMap[Module, ArrayBuffer[Node]](),
    ChainType.Regs -> HashMap[Module, ArrayBuffer[Node]](),
    ChainType.SRAM -> HashMap[Module, ArrayBuffer[Node]](),
    ChainType.Cntr -> HashMap[Module, ArrayBuffer[Node]]())
  private[strober] val chainLen = HashMap(
    ChainType.Trs  -> 0,
    ChainType.Regs -> 0,
    ChainType.SRAM -> 0,
    ChainType.Cntr -> 0)
  private[strober] val chainLoop = HashMap(
    ChainType.Trs  -> 0,
    ChainType.Regs -> 0,
    ChainType.SRAM -> 0,
    ChainType.Cntr -> 0)
  private[strober] val chainName = Map(
    ChainType.Trs  -> "TRACE_CHAIN",
    ChainType.Regs -> "REG_CHAIN",
    ChainType.SRAM -> "SRAM_CHAIN",
    ChainType.Cntr -> "CNTR_CHAIN")
  
  private[strober] val inMap = LinkedHashMap[Bits, Int]()
  private[strober] val outMap = LinkedHashMap[Bits, Int]()
  private[strober] val retimingMap = LinkedHashMap[Node, Bits]()
  private[strober] val nameMap = HashMap[Node, String]()
  private[strober] var targetName = ""
  private[strober] def init[T <: Module](w: SimWrapper[T], fire: Bool) {
    // Add backend passes
    if (wrappers.isEmpty) { 
      Driver.backend.transforms ++= Seq(
        Driver.backend.inferAll,
        fame1Transforms,
        addDaisyChains(ChainType.Trs),
        addDaisyChains(ChainType.Regs),
        addDaisyChains(ChainType.SRAM),
        addDaisyChains(ChainType.Cntr),
        dumpMaps,
        dumpChains,
        dumpConsts
      )
    }

    targetName = Driver.backend.extractClassName(w.target) 
    w setName s"${targetName}Wrapper"
    wrappers    += w
    fires(w)     = fire
    resets(w)    = w.reset
    daisyPins(w) = w.io.daisy
  }

  private[strober] def init[T <: Module](w: NastiShim[SimNetwork]) {
    w setName s"${targetName}NastiShim"
  }


  private def collect(c: Module): List[Module] = 
    (c.children foldLeft List[Module]())((res, x) => res ++ collect(x)) ++ List(c)
  private def collectRev(c: Module): List[Module] = 
    List(c) ++ (c.children foldLeft List[Module]())((res, x) => res ++ collectRev(x))

  def clear {
    ChainType.values.toList foreach { t =>
      chains(t).clear
      chainLen(t) = 0
      chainLoop(t) = 0
    }
    inMap.clear
    outMap.clear
    retimingMap.clear
    nameMap.clear
    daisyPins.clear
    comps.clear
    compsRev.clear
    wrappers.clear
    fires.clear
    resets.clear
    noSnap.clear
  }

  // Called from frontend
  def addRetiming(m: Module, latency: Int) {
    (m.wires ++ Array((m.reset.name, m.reset))) foreach {
      case (name, io) if io.dir == INPUT =>
        // Input trace is captured to recover internal state
        val chain = chains(ChainType.Trs) getOrElseUpdate (m, ArrayBuffer[Node]())
        val trace = List.fill(latency){Reg(io)}
        trace.zipWithIndex foreach {case (reg, i) =>
          reg := (if (i == 0) io else trace(i-1))
          reg.getNode setName s"${name}_reg_${i}"
          m.debug(reg.getNode)
        }
        chain       ++= trace map (_.getNode)
        retimingMap ++= trace map (_.getNode -> io)
      case (name, io) if io.dir == OUTPUT => 
        // TODO: Should we handle it?
    }
    chainLoop(ChainType.Trs) = 1
    noSnap ++= collect(m)
  }
 
  def addCounter(m: Module, cond: Bool, name: String) {
    val chain = chains(ChainType.Cntr) getOrElseUpdate (m, ArrayBuffer[Node]())
    val cntr = RegInit(UInt(0, 32))
    when (cond) { cntr := cntr + UInt(1) }
    cntr.getNode setName name
    m.debug(cntr.getNode)
    chain += cntr.getNode
    chainLoop(ChainType.Cntr) = 1
    noSnap += m
  }

  private def findSRAMRead(sram: Mem[_]) = {
    require(sram.seqRead)
    val read = sram.readAccesses.last
    val addr = read match {
      case mr:  MemRead => mr.addr.getNode match {case r: Reg => r}
      case msr: MemSeqRead => msr.addrReg
    }
    (addr, read)
  }

  private def connectFires(m: Module): Bool = {
    val fire = m.addPin(Bool(INPUT), "fire_pin")
    fire := fires getOrElseUpdate(m.parent, connectFires(m.parent))
    fire
  }

  private def connectResets(m: Module): Bool = {
    val reset = m.addPin(Bool(INPUT), "daisy__rst")
    reset := resets getOrElseUpdate(m.parent, connectResets(m.parent))
    reset
  }

  private def fame1Transforms(c: Module) {
    ChiselError.info("[Strober Transforms] connect control signals")

    def connectSRAMRestart(m: Module): Unit = m.parent match {
      case p if daisyPins contains p =>
        if (p != c && daisyPins(p).sram.restart.inputs.isEmpty)
          connectSRAMRestart(p)
        daisyPins(m).sram.restart := daisyPins(p).sram.restart
      case _ =>
    }

    wrappers foreach { w =>
      val t = w.target
      val tName = Driver.backend.extractClassName(t) 
      val tPath = t.getPathName(".")
      comps(w)    = collect(t)
      compsRev(w) = collectRev(t)
      def getPath(node: Node) = s"${tName}${node.chiselName stripPrefix tPath}"
      // Connect the fire signal to the register and memory writes for freezing
      compsRev(w) foreach { m =>
        lazy val fire = fires getOrElseUpdate (m, connectFires(m))
        ChainType.values foreach (chains(_) getOrElseUpdate (m, ArrayBuffer[Node]()))
        daisyPins getOrElseUpdate (m, m.addPin(new DaisyBundle(w.daisyWidth), "io_daisy"))
        nameMap(m.reset) = s"""${tName}${m getPathName "." stripPrefix tPath}.${m.reset.name}"""
        m.reset setName s"host__${m.reset.name}"
        m.wires foreach {case (_, wire) => nameMap(wire) = getPath(wire)}
        m bfs {
          case reg: Reg =>
            reg assignReset m.reset
            reg assignClock Driver.implicitClock
            reg.inputs(0) = Multiplex(Bool(reg.enableSignal) && fire, reg.updateValue, reg)
            if (!noSnap(m)) {
              chains(ChainType.Regs)(m) += reg
              chainLoop(ChainType.Regs) = 1
            }
            nameMap(reg) = getPath(reg)
          case mem: Mem[_] =>
            mem assignClock Driver.implicitClock
            mem.writeAccesses foreach (w => w.cond = Bool(w.cond) && fire)
            if (!noSnap(m)) {
              if (mem.seqRead) {
                val read = findSRAMRead(mem)._2
                chains(ChainType.Regs)(m) += read
                chains(ChainType.SRAM)(m) += mem
                chainLoop(ChainType.SRAM) = math.max(chainLoop(ChainType.SRAM), mem.size)
                nameMap(read) = getPath(mem) 
              } else if (mem.size > 16 && mem.needWidth > 32) { 
                // handle big regfiles like srams
                chains(ChainType.SRAM)(m) += mem
                chainLoop(ChainType.SRAM) = math.max(chainLoop(ChainType.SRAM), mem.size)             
              } else (0 until mem.size) map (UInt(_)) foreach {idx =>
                val read = new MemRead(mem, idx) 
                chains(ChainType.Regs)(m) += read
                read.infer
              }
            }
            nameMap(mem) = getPath(mem)
          case assert: Assert =>
            assert assignClock Driver.implicitClock
            assert.cond = Bool(assert.cond) || !fire
            m.debug(assert.cond)
          case printf: Printf =>
            printf assignClock Driver.implicitClock
            printf.cond = Bool(printf.cond) && fire
            m.debug(printf.cond)
          case delay: Delay =>
            delay assignClock Driver.implicitClock
          case _ =>
        }

        if (!chains(ChainType.SRAM)(m).isEmpty) connectSRAMRestart(m)
      }
    }
  }

  private def addDaisyChains(chainType: ChainType.Value) = (c: Module) => if (chainLoop(chainType) > 0) {
    ChiselError.info(s"""[Strober Transforms] add ${chainName(chainType).toLowerCase replace ("_", " ")}""")
  
    val hasChain = HashSet[Module]()

    def insertRegChain(m: Module, daisyWidth: Int, p: Parameters) = if (chains(chainType)(m).isEmpty) None else {
      val width = (chains(chainType)(m) foldLeft 0)(_ + _.needWidth)
      val daisy = m.addModule(new RegChain()(p alter Map(DataWidth -> width)))
      ((0 until daisy.daisyLen) foldRight (0, 0)){case (i, (index, offset)) =>
        def loop(total: Int, index: Int, offset: Int, wires: Seq[UInt]): (Int, Int, Seq[UInt]) = {
          val margin = daisyWidth - total
          if (margin == 0) {
            (index, offset, wires)
          } else if (index < chains(chainType)(m).size) {
            val reg = chains(chainType)(m)(index)
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
      daisy.reset    := resets getOrElseUpdate (m, connectResets(m)) 
      daisy.io.stall := !fires(m)
      daisy.io.dataIo.out <> daisyPins(m)(chainType).out
      hasChain += m
      chainLen(chainType) += daisy.daisyLen
      Some(daisy)
    }

    def insertSRAMChain(m: Module, daisyWidth: Int, p: Parameters) = {
      val chain = (chains(ChainType.SRAM)(m) foldLeft (None: Option[SRAMChain])){case (lastChain, sram: Mem[_]) =>
        val (addr, read) = if (sram.seqRead) findSRAMRead(sram) else {
          val addr = m.addNode(Reg(UInt(width=log2Up(sram.size))))
          val read = m.addNode(new MemRead(sram, addr))
          (addr.getNode match {case r: Reg => r}, read)
        }
        val width = sram.needWidth
        val daisy = m.addModule(new SRAMChain()(
          p alter Map(DataWidth -> width, SRAMSize -> sram.size)))
        ((0 until daisy.daisyLen) foldRight (width-1)){case (i, high) =>
          val low = math.max(high-daisyWidth+1, 0)
          val margin = daisyWidth-(high-low+1)
          val daisyIn = UInt(read)(high, low)
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
        daisy.reset    := resets getOrElseUpdate (m, connectResets(m)) 
        daisy.io.stall := !fires(m)
        daisy.io.restart := daisyPins(m).sram.restart
        daisy.io.addrIo.in := UInt(addr)
        // Connect daisy addr to SRAM addr
        if (sram.seqRead) {
          // need to keep enable signals...
          addr.inputs(0) = Multiplex(daisy.io.addrIo.out.valid || Bool(addr.enableSignal),
                           Multiplex(daisy.io.addrIo.out.valid, daisy.io.addrIo.out.bits, addr.updateValue), addr)
          assert(addr.isEnable)
        } else {
          addr.inputs(0) = Multiplex(daisy.io.addrIo.out.valid, daisy.io.addrIo.out.bits, addr)
        }
        chainLen(chainType) += daisy.daisyLen
        Some(daisy)
        case _ => throwException("[sram chain] This can't happen...")
      }
      if (chain != None) hasChain += m
      chain
    }

    for (w <- wrappers ; m <- comps(w)) {
      val daisy = chainType match {
        case ChainType.SRAM => insertSRAMChain(m, w.daisyWidth, w.p)
        case _              => insertRegChain(m, w.daisyWidth, w.p)
      }
      // Filter children who have daisy chains
      (m.children filter (hasChain(_)) foldLeft (None: Option[Module])){case (prev, child) =>
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
    case w: NastiShim[SimNetwork] if Driver.chiselConfigDump => 
      object MapType extends Enumeration { val IoIn, IoOut, InTr, OutTr = Value }
      ChiselError.info("[Strober Transforms] dump io & mem mapping")
      def dump(map_t: MapType.Value, arg: (Bits, Int)) = arg match { 
        case (wire, id) => s"${map_t.id} ${nameMap(wire)} ${id} ${w.sim.io.chunk(wire)}\n"} 

      val res = new StringBuilder
      res append (w.master.inMap    map {dump(MapType.IoIn,  _)} mkString "")
      res append (w.master.outMap   map {dump(MapType.IoOut, _)} mkString "")
      res append (w.master.inTrMap  map {dump(MapType.InTr,  _)} mkString "")
      res append (w.master.outTrMap map {dump(MapType.OutTr, _)} mkString "")

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
    ChiselError.info("[Strober Transforms] dump chain mapping")
    val res = new StringBuilder
    def dump(chain_t: ChainType.Value, elem: Option[Node], width: Int, off: Option[Int]) = {
      val path = elem match { case Some(p) => nameMap(p) case None => "null" }
      s"${chain_t.id} ${path} ${width} ${off getOrElse -1}\n" 
    } 

    def addPad(t: ChainType.Value, cw: Int, dw: Int) {
      val pad = cw - dw
      if (pad > 0) {
        Sample.addToChain(t, None, pad, None)
        res   append dump(t, None, pad, None)
      }
    }

    ChainType.values.toList foreach { t =>
      for (w <- wrappers ; m <- compsRev(w)) {
        val (cw, dw) = (chains(t)(m) foldLeft (0, 0)){case ((chainWidth, dataWidth), elem) =>
          val width = elem.needWidth
          val dw = dataWidth + width
          val cw = (Stream.from(0) map (chainWidth + _ * w.daisyWidth) dropWhile (_ < dw)).head
          val (node, off) = elem match {
            case sram: Mem[_] => 
              (Some(sram), Some(sram.size))
            case read: MemRead if !read.mem.seqRead => 
              (Some(read.mem.asInstanceOf[Mem[Data]]), Some(read.addr.litValue(0).toInt))
            case _ => 
              (Some(elem), None)
          }
          Sample.addToChain(t, node, width, off) // for tester
          res append dump(t, node map (x => retimingMap getOrElse (x, x)), width, off)
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
      case _: NastiShim[SimNetwork] if Driver.chiselConfigDump =>
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
    case w: NastiShim[SimNetwork] if Driver.chiselConfigDump => 
      ChiselError.info("[Strober Transforms] dump constant header")
      def dump(arg: (String, Int)) = s"#define ${arg._1} ${arg._2}\n"
      val sb = new StringBuilder
      val consts = List(
        "SAMPLE_NUM"        -> w.sim.sampleNum,
        "TRACE_MAX_LEN"     -> w.sim.traceMaxLen,
        "DAISY_WIDTH"       -> w.sim.daisyWidth,
        "CHANNEL_OFFSET"    -> log2Up(w.sim.channelWidth),
        "LINE_OFFSET"       -> log2Up(w.slave.lineSize),
        "POKE_SIZE"         -> w.master.io.ins.size,
        "PEEK_SIZE"         -> w.master.io.outs.size,
        "RESET_ADDR"        -> w.master.resetAddr,
        "SRAM_RESTART_ADDR" -> w.master.sramRestartAddr,
        "TRACE_LEN_ADDR"    -> w.master.traceLenAddr,
        "MEM_DATA_BITS"     -> w.mifDataBits,
        "MEM_DATA_BEATS"    -> w.mifDataBeats,
        "MEM_DATA_CHUNK"    -> w.sim.io.chunk(w.mem.resp.bits.data),
        "MEM_CYCLE_ADDR"    -> w.master.memCycleAddr,
        "MEM_REQ_ADDR"      -> w.master.reqMap(w.mem.req_cmd.bits.addr),
        "MEM_REQ_TAG"       -> w.master.reqMap(w.mem.req_cmd.bits.tag),
        "MEM_REQ_RW"        -> w.master.reqMap(w.mem.req_cmd.bits.rw),
        "MEM_REQ_DATA"      -> w.master.reqMap(w.mem.req_data.bits.data),
        "MEM_RESP_DATA"     -> w.master.respMap(w.mem.resp.bits.data),
        "MEM_RESP_TAG"      -> w.master.respMap(w.mem.resp.bits.tag)
      )
      val chain_name = ChainType.values.toList map chainName mkString ","
      val chain_addr = ChainType.values.toList map w.master.snapOutMap mkString ","
      val chain_loop = ChainType.values.toList map chainLoop mkString ","
      val chain_len  = ChainType.values.toList map chainLen  mkString ","
      sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
      sb append "#define __%s_H\n".format(targetName.toUpperCase)
      consts foreach (sb append dump(_))
      sb append s"""enum CHAIN_TYPE {${chain_name},CHAIN_NUM};\n"""
      sb append s"""const unsigned CHAIN_ADDR[CHAIN_NUM] = {${chain_addr}};\n"""
      sb append s"""const unsigned CHAIN_LOOP[CHAIN_NUM] = {${chain_loop}};\n"""
      sb append s"""const unsigned CHAIN_LEN[CHAIN_NUM]  = {${chain_len}};\n"""
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
