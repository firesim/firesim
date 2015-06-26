package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, HashSet}
import addDaisyPins._

object transforms { 
  val sramAddrs = HashMap[Mem[_], Reg]()
  // var daisyLen = -1

  private val wrappers = ArrayBuffer[SimWrapper[Module]]()
  private val stallPins = HashMap[Module, Bool]()
  private val comps = HashMap[Module, Vector[Module]]()
  private val compsRev = HashMap[Module, Vector[Module]]()
  private val regs = HashMap[Module, ArrayBuffer[Node]]()
  private val srams = HashMap[Module, ArrayBuffer[Mem[_]]]()
  private[strober] val inMap = LinkedHashMap[Bits, Int]()
  private[strober] val outMap = LinkedHashMap[Bits, Int]()
  private[strober] val reqMap = LinkedHashMap[Bits, Int]()
  private[strober] val respMap = LinkedHashMap[Bits, Int]()
  private lazy val targetName = wrappers.last.name stripSuffix "Wrapper"

  private[strober] def init[T <: Module](w: SimWrapper[T], stall: Bool) {
    // Add backend passes
    if (wrappers.isEmpty) { 
      Driver.backend.transforms ++= Seq(
        Driver.backend.inferAll,
        Driver.backend.computeMemPorts,
        Driver.backend.findConsumers,
        initSimWrappers,
        dumpMaps,
        dumpParams
        // addRegChains,
        // addSRAMChain,
      )
    }

    w.name = Driver.backend.extractClassName(w.target) + "Wrapper"
    wrappers += w
    stallPins(w.target) = stall
  } 

  private[strober] def init[T <: SimNetwork](w: SimAXI4Wrapper[T]) {
    w.name = targetName + "AXI4Wrapper"
    inMap ++= w.ioInMap.unzip._1.zipWithIndex
    outMap ++= w.ioOutMap.unzip._1.zipWithIndex
    reqMap(w.mem_req_cmd_addr) = w.ioInMap.size
    reqMap(w.mem_req_cmd_tag) = w.ioInMap.size + 1
    reqMap(w.mem_req_data) = w.ioInMap.size + 2
    respMap(w.mem_resp_data) = w.ioOutMap.size
    respMap(w.mem_resp_tag) = w.ioOutMap.size + 1
  }

  private def initSimWrappers(c: Module) {
    ChiselError.info("[transforms] initiate simulation modules")
    // This pass initiate simulation modules
    def collect(c: Module): Vector[Module] = 
      (c.children foldLeft Vector[Module]())((res, x) => res ++ collect(x)) ++ Vector(c)
    def collectRev(c: Module): Vector[Module] = 
      Vector(c) ++ (c.children foldLeft Vector[Module]())((res, x) => res ++ collectRev(x))

    for (w <- wrappers) {
      val t = w.target
      comps(t) = collect(t)
      compsRev(t) = collectRev(t)

      // Connect the stall signal to the register and memory writes for freezing
      for (m <- compsRev(t)) {
        if (!(stallPins contains m)) { 
          stallPins(m) = m.addPin(Bool(INPUT), "io_stall_t")
          stallPins(m) := stallPins(m.parent)
        }
        regs(m) = ArrayBuffer[Node]()
        srams(m) = ArrayBuffer[Mem[_]]()
        m bfs { _ match {
          case reg: Reg => { 
            reg.inputs(0) = Multiplex(stallPins(m) && !m.reset, reg, reg.inputs(0))
            // Add the register for daisy chains
            regs(m) += reg
          }
          case mem: Mem[_] => {
            for (write <- mem.writeAccesses) {
              write cond_= Bool().fromNode(write.cond) && !stallPins(m)
            }
            if (mem.seqRead) {
              srams(m) += mem
            } else {
              for (i <- 0 until mem.size) {
                val read = new MemRead(mem, UInt(i)) 
                read.infer
                regs(m) += read
              }
            }
          }
          case _ =>
        } }
      }
    }
  }

  private def dumpMaps(c: Module) {
    object MapType extends Enumeration { val IoIn, IoOut, MemIn, MemOut = Value }
    ChiselError.info("[transforms] dump the io & mem maps")
    val res = new StringBuilder
    for ((in, id) <- inMap) {
      val path = Driver.backend.extractClassName(in.component) + "." + in.name
      val width = in.needWidth
      res append "%d %s %d %d\n".format(MapType.IoIn.id, path, id, width)
    }
    for ((out, id) <- outMap) {
      val path = Driver.backend.extractClassName(out.component) + "." + out.name
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

    val file = Driver.createOutputFile(targetName + ".map")
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

  /*
  def addRegChains(c: Module) {
    ChiselError.info("[transforms] add reg chains")

    val hasRegChain = HashSet[Module]()
    def insertRegChain(m: Module) = {
      val dataWidth = (regs(m) foldLeft 0)(_ + _.needWidth)
      val regChain = if (!regs(m).isEmpty) 
        Some(m.addModule(new RegChain(top.reset), {case DataWidth => dataWidth})) else None
      regChain match {
        case None =>
        case Some(chain) => {
          var regIdx = 0
          var regOff = 0
          for (i <- (0 until chain.daisySize).reverse) {
            val wires = ArrayBuffer[UInt]()
            var totalWidth = 0
            while (totalWidth < daisyLen) {
              val totalMargin = daisyLen - totalWidth
              if (regIdx < regs(m).size) {
                val reg = regs(m)(regIdx)
                val regWidth = reg.needWidth
                val regMargin = regWidth - regOff
                if (regMargin <= totalMargin) {
                  wires += UInt(reg)(regMargin-1, 0)
                  totalWidth += regMargin
                  regOff = 0
                  regIdx += 1
                } else {
                  wires += UInt(reg)(regMargin-1, regMargin-totalMargin)
                  totalWidth += totalMargin
                  regOff += totalMargin
                }
              } else {
                wires += UInt(0, totalMargin)
                totalWidth += totalMargin 
              }
              chain.io.dataIo.data(i) := Cat(wires) 
            }
          }
          chain.io.dataIo.out <> daisyPins(m).regs.out
          chain.io.stall := daisyPins(m).stall
          hasRegChain += m
        }
      }      
      regChain
    }

    for (m <- targetComps) {
      val regChain = insertRegChain(m)
      // Filter children who have reg chains
      var last = -1
      for ((child, cur) <- m.children.zipWithIndex ; if hasRegChain contains child) {
        if (last < 0) {
          regChain match {
            case None => daisyPins(m).regs.out <> daisyPins(child).regs.out
            case Some(chain) => chain.io.dataIo.in <> daisyPins(child).regs.out
          }
        } else {
          daisyPins(m.children(last)).regs.in <> daisyPins(child).regs.out
        }
        last = cur
      }

      if (last > -1) {
        hasRegChain += m
        daisyPins(m.children(last)).regs.in <> daisyPins(m).regs.in
      } else {
        regChain match {
          case None =>
          case Some(chain) => chain.io.dataIo.in <> daisyPins(m).regs.in
        }
      }
    }
  } 

  def addSRAMChain(c: Module) {
    ChiselError.info("[transforms] add sram chains")

    def connectSRAMRestarts(m: Module) {
      if (m.name != top.target.name && daisyPins(m).sram.restart.inputs.isEmpty) {
        connectSRAMRestarts(m.parent)
        daisyPins(m).sram.restart := daisyPins(m.parent).sram.restart
      }
    }

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
        val chain = m.addModule(new SRAMChain(top.reset), {
          case DataWidth => dataWidth 
          case SRAMSize => sram.size})
        chain.io.stall := daisyPins(m).stall
        var high = dataWidth-1
        for (i <- (0 until chain.daisySize).reverse) {
          val low = math.max(high-daisyLen+1, 0)
          val widthMargin = daisyLen-(high-low+1)
          val thisData = UInt(data)(high, low)
          if (widthMargin == 0) {
            chain.io.dataIo.data(i) := thisData
          } else {
            chain.io.dataIo.data(i) := Cat(thisData, UInt(0, widthMargin))
          }
          high -= daisyLen
        }
        lastChain match {
          case None => {
            connectSRAMRestarts(m)
            daisyPins(m).sram.out <> chain.io.dataIo.out
          }
          case Some(last) => {
            last.io.dataIo.in <> chain.io.dataIo.out
          }
        }
        chain.io.restart := daisyPins(m).sram.restart
        // Connect chain addr to SRAM addr
        chain.io.addrIo.in := UInt(addr)
        addr.inputs(0) = Multiplex(chain.io.addrIo.out.valid, 
                                   chain.io.addrIo.out.bits, addr.inputs(0))
        lastChain = Some(chain)
      }
      if (lastChain != None) hasSRAMChain += m
      lastChain
    }

    if (Driver.hasSRAM) {
      for (m <- targetComps) {
        val sramChain = insertSRAMChain(m)   
        // Filter children who have sram chains
        var last = -1
        for ((child, cur) <- m.children.zipWithIndex; if hasSRAMChain contains child) {
          if (last < 0) {
            sramChain match {
              case None => {
                connectSRAMRestarts(m)
                daisyPins(m).sram.out <> daisyPins(child).sram.out
              }
              case Some(chain) => {
                chain.io.dataIo.in <> daisyPins(child).sram.out
              }
            }
          } else {
            daisyPins(m.children(last)).sram.in <> daisyPins(child).sram.out
          }
          last = cur
        }

        if (last > -1) {
          hasSRAMChain += m
          daisyPins(m).sram.in <> daisyPins(m.children(last)).sram.in
        } else {
          sramChain match {
            case None =>
            case Some(chain) => daisyPins(m).sram.in <> chain.io.dataIo.in
          }
        }     
      } 
    }
  }

  def dumpMappings(c: Module) {
    ChiselError.info("[transforms] print out chain mappings")
    val prefix = top.name + "." + top.target.name
    val res = new StringBuilder

    // Print out the chain mapping
    val chainFile = Driver.createOutputFile(targetName + ".chain.map")
    // Collect regs
    var daisyWidthSum = 0
    for (m <- targetCompsRev) {
      var daisyWidth = 0
      var dataWidth = 0
      for (reg <- regs(m)) {
        reg match {
          case read: MemRead => {
            val mem = read.mem
            val addr = read.addr.litValue(0).toInt
            val path = targetName + (m.getPathName(".") stripPrefix prefix) + "." + mem.name
            val width = mem.needWidth
            res append "%s[%d] %d\n".format(path, addr, width)
            dataWidth += width
            while (daisyWidth < dataWidth) daisyWidth += daisyLen
          }
          case _ => { 
            val path = targetName + (m.getPathName(".") stripPrefix prefix) + "." + reg.name
            val width = reg.needWidth
            res append "%s %d\n".format(path, width)
            dataWidth += width
            while (daisyWidth < dataWidth) daisyWidth += daisyLen
          }
        }
      }
      val daisyPadWidth = daisyWidth - dataWidth
      if (daisyPadWidth > 0) {
        res append "null %d\n".format(daisyPadWidth)
      }
      daisyWidthSum += daisyWidth
    }
    var hostWidthSum = 0
    while (hostWidthSum < daisyWidthSum) hostWidthSum += top.axiDataWidth
    val padWidth = hostWidthSum - daisyWidthSum
    if (padWidth > 0) {
      res append "null %d\n".format(padWidth)
    }

    for (i <- 0 until Driver.sramMaxSize ; m <- targetCompsRev) {
      for (sram <- srams(m)) {
        val path = targetName + (m.getPathName(".") stripPrefix prefix) + "." + sram.name
        val dataWidth = sram.needWidth
        var daisyWidth = 0
        if (i < sram.n) 
          res append "%s[%d] %d\n".format(path, i, dataWidth)
        else 
          res append "null %d\n".format(dataWidth)
        while (daisyWidth < dataWidth) daisyWidth += daisyLen
        val daisyPadWidth = daisyWidth - dataWidth
        if (daisyPadWidth > 0) {
          res append "null %d\n".format(daisyPadWidth)  
        }
      }
    }
    try {
      chainFile write res.result
    } finally {
      chainFile.close
      res.clear
    }
  }
  */
}

