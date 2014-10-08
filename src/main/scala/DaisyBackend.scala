package DebugMachine

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.collection.mutable.{Queue => ScalaQueue}

object DaisyBackend { 
  val stallPins = HashMap[Module, Bool]()
  val states = HashMap[Module, ArrayBuffer[Node]]()
  val srams  = HashMap[Module, ArrayBuffer[Mem[_]]]()
  val stateIns  = HashMap[Module, DecoupledIO[UInt]]()
  val stateOuts = HashMap[Module, DecoupledIO[UInt]]()
  val sramIns  = HashMap[Module, DecoupledIO[UInt]]()
  val sramOuts = HashMap[Module, DecoupledIO[UInt]]()
  val sramRestarts = HashMap[Module, Bool]()
  val cntrIns  = HashMap[Module, DecoupledIO[UInt]]()
  val cntrOuts = HashMap[Module, DecoupledIO[UInt]]()
  lazy val top = Driver.topComponent.asInstanceOf[DaisyWrapper[Module]]
  lazy val targetName = Driver.backend.extractClassName(top.target)
  var daisywidth = -1

  def addTransforms(width: Int) {
    daisywidth = width
    Driver.backend.transforms ++= Seq(
      initDaisy,
      Driver.backend.findConsumers,
      Driver.backend.inferAll,
      connectStallSignals,
      addStateChains,
      addSRAMChain,
      printOutMappings
    )
  } 

  def initDaisy(c: Module) {
    top.name = targetName + "Wrapper"
    stallPins(top.target) = top.stallPin
    stateIns(top.target) = top.stateIn
    stateOuts(top.target) = top.stateOut
    if (Driver.hasSRAM) {
      sramIns(top.target) = top.sramIn
      sramOuts(top.target) = top.sramOut
      sramRestarts(top.target) = top.sramRestart
    }
  }

  // Connect the stall signal to the register and memory writes for freezing
  def connectStallSignals(c: Module) {
    ChiselError.info("[DaisyBackend] connect stall signals")

    def connectStallPins(m: Module) {
      if (!(stallPins contains m)) {
        stallPins(m) = m.addPin(Bool(INPUT), "stall")
        if (!(stallPins contains m.parent)) connectStallPins(m.parent)
        stallPins(m) := stallPins(m.parent)
      }
    }

    for (m <- Driver.sortedComps ; if m.name != top.name) {
      states(m) = ArrayBuffer[Node]()
      srams(m) = ArrayBuffer[Mem[_]]()
      connectStallPins(m)
      // Add target's inputs
      if (m.name == top.target.name) states(m) ++= top.inputs
      m bfs { _ match {
        case reg: Reg => { 
          connectStallPins(m)
          reg.inputs(0) = Multiplex(stallPins(m), reg, reg.inputs(0))
          // Add the register for daisy chains
          states(m) += reg
        }
        case mem: Mem[_] => {
          connectStallPins(m)
          for (write <- mem.writeAccesses) {
            write cond_= write.cond.asInstanceOf[Bool] && !stallPins(m) 
          }
          if (mem.seqRead) {
            srams(m) += mem
          } else {
            for (i <- 0 until mem.size) {
              val read = new MemRead(mem, UInt(i)) 
              read.infer
              states(m) += read
            }
          }
        }
        case _ =>
      } }
    }
  }

  def addStateChains(c: Module) {
    ChiselError.info("[DaisyBackend] add state chains")

    def insertStatePins(m: Module) {
      if (m.name != top.target.name) {
        stateIns(m)  = m.addPin(Decoupled(UInt(width=daisywidth)).flip, "state_in")
        stateOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)),      "state_out")
      }
    }

    def insertStateChain(m: Module) = {
      val datawidth = (states(m) foldLeft 0)(_ + _.needWidth)
      val chain = if (!states(m).isEmpty) m.addModule(new StateChain, {case Datawidth => datawidth}) else null
      if (chain != null) {
        if (m.name != top.name) insertStatePins(m)
        chain.io.data := UInt(Concatenate(states(m)))
        chain.io.stall := stallPins(m)
        chain.io.out <> stateOuts(m)
      } 
      chain
    }

    for (m <- Driver.sortedComps ; if m.name != top.name) {
      val stateChain = insertStateChain(m)
      // Filter children who have state chains
      var last = -1
      for ((child, cur) <- m.children.zipWithIndex; if (stateOuts contains child)) {
        if (last < 0) {
          if (stateChain != null) {
            stateChain.io.in <> stateOuts(child)
          } else {
            insertStatePins(m)
            stateOuts(m) <> stateOuts(child)
          }
        } else {
          val lastChild = m.children(last)
          stateOuts(lastChild) <> stateIns(m)
        }
        last = cur
      }

      if (last > -1) {
        stateIns(m) <> stateIns(m.children(last))
      } else if (stateChain != null) {
        stateIns(m) <> stateChain.io.in
      }
    }
  } 

  def addSRAMChain(c: Module) {
    ChiselError.info("[DaisyBackend] add sram chains")

    def connectSRAMRestarts(m: Module) {
      if (!(sramRestarts contains m)) {
        sramRestarts(m) = m.addPin(Bool(INPUT), "restart")
        if (!(sramRestarts contains m.parent)) connectSRAMRestarts(m.parent)
        sramRestarts(m) := sramRestarts(m.parent)
      }
    }

    def insertSRAMPins(m: Module) {
      if (m.name != top.target.name) {
        sramIns(m)  = m.addPin(Decoupled(UInt(width=daisywidth)).flip, "sram_in")
        sramOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)),      "sram_out")
      }
    }

    def insertSRAMChain(m: Module) = {
      var lastChain: SRAMChain = null
      for (sram <- srams(m)) {
        val read = sram.readAccesses.head
        val datawidth = sram.needWidth
        val chain = m.addModule(new SRAMChain, {
          case Datawidth => datawidth 
          case SRAMSize => sram.size})
        chain.io.data := UInt(read)
        chain.io.stall := stallPins(m)
        if (lastChain == null) {
          connectSRAMRestarts(m)
          insertSRAMPins(m)
          sramOuts(m) <> chain.io.out
        } else {
          lastChain.io.in <> chain.io.out
        }
        chain.io.restart := sramRestarts(m)
        // Connect chain addr to SRAM addr
        val readAddr = read.addr.asInstanceOf[Bits]
        chain.io.addrIn := readAddr
        when(chain.io.addrOut.valid) {
          readAddr := chain.io.addrOut.bits
        }
        lastChain = chain
      }
      lastChain
    }

    if (Driver.hasSRAM) {
      for (m <- Driver.sortedComps ; if m.name != top.name) {
        val sramChain = insertSRAMChain(m)   
        // Filter children who have sram chains
        var last = -1
        for ((child, cur) <- m.children.zipWithIndex; if (sramOuts contains child)) {
          if (last < 0) {
            if (sramChain != null) {
              sramChain.io.in <> sramOuts(child)
            } else {
              connectSRAMRestarts(m)
              insertSRAMPins(m)
              sramOuts(m) <> sramOuts(child)
            }
          } else {
            sramOuts(child) <> sramOuts(m.children(last))
          }
          last = cur
        }

        if (last > -1) {
          sramIns(m) <> sramIns(m.children(last))
        } else if (sramChain != null) {
          sramIns(m) <> sramChain.io.in
        }     
      } 
    }
  }

  def printOutMappings(c: Module) {
    ChiselError.info("[DaisyBackend] print out chain mappings")
    val prefix = top.name + "." + top.target.name
    val res = new StringBuilder

    // Print out the IO mapping for pokes and peeks
    val ioFile = Driver.createOutputFile(targetName + ".io.map")
    res append "INPUT:\n"
    for ((input, i) <- top.inputs.zipWithIndex) {
      val path = targetName + "." + (top.target.getPathName(".") stripPrefix prefix) + input.name
      res append "%s %d\n".format(path, i)
    }
    res append "OUTPUT:\n"
    for ((output, i) <- top.outputs.zipWithIndex) {
      val path = targetName + "." + (top.target.getPathName(".") stripPrefix prefix) + output.name
      res append "%s %d\n".format(path, i)
    }
    try {
      ioFile write res.result
    } finally {
      ioFile.close
      res.clear
    }

    // Print out the chain mapping
    val chainFile = Driver.createOutputFile(targetName + ".chain.map")
    // Collect states
    var stateWidth = 0
    var totalWidth = 0
    for (m <- Driver.sortedComps.reverse ; if m.name != top.name) {
      var daisyWidth = 0
      var thisWidth = 0
      for (state <- states(m)) {
        state match {
          case read: MemRead => {
            val mem = read.mem
            val addr = read.addr.litValue(0).toInt
            val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + mem.name
            val width = mem.needWidth
            res append "%s[%d] %d\n".format(path, addr, width)
            stateWidth += width
            thisWidth += width
            while (totalWidth < stateWidth) totalWidth += top.buswidth
            while (daisyWidth < thisWidth) daisyWidth += top.daisywidth
          }
          case _ => { 
            val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + state.name
            val width = state.needWidth
            res append "%s %d\n".format(path, width)
            stateWidth += width
            while (totalWidth < stateWidth) totalWidth += top.buswidth
            while (daisyWidth < thisWidth) daisyWidth += top.daisywidth
          }
        }
      }
      val daisyPadWidth = daisyWidth - thisWidth
      if (daisyPadWidth > 0) {
        res append "null %d\n".format(daisyPadWidth)  
      }
    }
    val totalPadWidth = totalWidth - stateWidth
    if (totalPadWidth > 0) {
      res append "null %d\n".format(totalPadWidth)
    }

    for (i <- 0 until Driver.sramMaxSize ; m <- Driver.sortedComps.reverse ; if m.name != top.name) {
      for (sram <- srams(m)) {
        val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + sram.name
        val width = sram.needWidth
        var daisyWidth = 0
        if (i < sram.n) 
          res append "%s[%d] %d\n".format(path, i, width)
        else 
          res append "null %d\n".format(width)
        while (daisyWidth < width) daisyWidth += top.daisywidth
        val daisyPadWidth = daisyWidth - width
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
}
