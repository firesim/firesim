package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, Stack}
import addDaisyPins._

object transforms { 
  val regs = HashMap[Module, ArrayBuffer[Node]]()
  val srams  = HashMap[Module, ArrayBuffer[Mem[_]]]()
  val sramAddrs = HashMap[Mem[_], Reg]()
  lazy val top = Driver.topComponent.asInstanceOf[Strober[Module]]
  lazy val targetName = Driver.backend.extractClassName(top.target)
  lazy val (targetComps, targetCompsRev) = {
    def collect(c: Module): Vector[Module] = 
      (c.children foldLeft Vector[Module]())((res, x) => res ++ collect(x)) ++ Vector(c)
    def collectRev(c: Module): Vector[Module] = 
      Vector(c) ++ (c.children foldLeft Vector[Module]())((res, x) => res ++ collectRev(x))
    (collect(top.target), collectRev(top.target))
  }
  var daisyLen = -1

  def addTransforms(width: Int) {
    daisyLen = width
    Driver.backend.transforms ++= Seq(
      initStrober,
      Driver.backend.inferAll,
      Driver.backend.computeMemPorts,
      Driver.backend.findConsumers,
      connectStallSignals,
      addRegChains,
      addSRAMChain,
      dumpMappings,
      dumpParams
    )
  } 

  def initStrober(c: Module) {
    Driver.implicitReset setName "reset_top"
    top.reset setName "reset_top"
    top.name = targetName + "Strober"
    for (m <- targetComps ; if m.name != top.target.name) {
      addDaisyPins(m, daisyLen)
    }
  }

  // Connect the stall signal to the register and memory writes for freezing
  def connectStallSignals(c: Module) {
    ChiselError.info("[transforms] connect stall signals")

    def connectStallPins(m: Module) {
      if (m.name != top.target.name && daisyPins(m).stall.inputs.isEmpty) {
        connectStallPins(m.parent)
        daisyPins(m).stall := daisyPins(m.parent).stall
      }
    }

    for (m <- targetComps) {
      regs(m) = ArrayBuffer[Node]()
      srams(m) = ArrayBuffer[Mem[_]]()
      connectStallPins(m)
      m bfs { _ match {
        case reg: Reg => { 
          connectStallPins(m)
          reg.inputs(0) = Multiplex(daisyPins(m).stall && !m.reset, reg, reg.inputs(0))
          // Add the register for daisy chains
          regs(m) += reg
        }
        case mem: Mem[_] => {
          connectStallPins(m)
          for (write <- mem.writeAccesses) {
            write cond_= Bool().fromNode(write.cond) && !daisyPins(m).stall
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

  def addRegChains(c: Module) {
    ChiselError.info("[transforms] add reg chains")

    val hasRegChain = HashSet[Module]()
    def insertRegChain(m: Module) = {
      val dataLen = (regs(m) foldLeft 0)(_ + _.needWidth)
      val regChain = if (!regs(m).isEmpty) 
        Some(m.addModule(new RegChain(top.reset), {case DataLen => dataLen})) else None
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
        val dataLen = sram.needWidth
        val chain = m.addModule(new SRAMChain(top.reset), {
          case DataLen => dataLen 
          case SRAMSize => sram.size})
        chain.io.stall := daisyPins(m).stall
        var high = dataLen-1
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

    val ioFile = Driver.createOutputFile(targetName + ".io.map")
    // Print out the IO mapping for pokes and peeks
    if (top.inNum > 0) {
      res append "IN:\n"
      for (in <- top.ins) {
        val path = targetName + "." + (top.target.getPathName(".") stripPrefix prefix) + in.name
        val width = in.needWidth
        res append "%s %d\n".format(path, width)
      }
    }
    
    if (top.outNum > 0) {
      res append "OUT:\n"
      for (out <- top.outs) {
        val path = targetName + "." + (top.target.getPathName(".") stripPrefix prefix) + out.name
        val width = out.needWidth
        res append "%s %d\n".format(path, width)
      }
    }

    try {
      ioFile write res.result
    } finally {
      ioFile.close
      res.clear
    }

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
    while (hostWidthSum < daisyWidthSum) hostWidthSum += top.hostLen
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

  // Todo: move this path to the ChiselBackend
  def dumpParams(c: Module) {
    if (Driver.chiselConfigMode != None && 
        Driver.chiselConfigMode.get != "instance" &&
        Driver.chiselConfigDump && !Dump.dump.isEmpty) {
      val w = Driver.createOutputFile(top.name + ".prm")
      w.write(Dump.getDump)
      w.close
    }
  }
}

