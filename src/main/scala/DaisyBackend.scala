package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.collection.mutable.{Queue => ScalaQueue}

object DaisyBackend { 
  val firePins = HashMap[Module, Bool]()
  val restartPins = HashMap[Module, Bool]()
  val states = HashMap[Module, ArrayBuffer[Node]]()
  val srams  = HashMap[Module, ArrayBuffer[Mem[_]]]()
  val stateIns  = HashMap[Module, DecoupledIO[UInt]]()
  val stateOuts = HashMap[Module, DecoupledIO[UInt]]()
  val sramIns  = HashMap[Module, DecoupledIO[UInt]]()
  val sramOuts = HashMap[Module, DecoupledIO[UInt]]()
  val cntrIns  = HashMap[Module, DecoupledIO[UInt]]()
  val cntrOuts = HashMap[Module, DecoupledIO[UInt]]()
  // val ioMap = HashMap[Bits, Bits]()
  lazy val top = Driver.topComponent.asInstanceOf[DaisyWrapper[Module]]
  lazy val targetName = Driver.backend.extractClassName(top.target)
  var daisywidth = -1
  var sramChainLength = 0

  def addTransforms(width: Int) {
    daisywidth = width
    Driver.backend.transforms ++= Seq(
      initDaisy,
      Driver.backend.findConsumers,
      Driver.backend.inferAll,
      connectFireSignals,
      addStateChains,
      // addSRAMChain,
      printOutMappings
    )
  } 

  def initDaisy(c: Module) {
    top.name = targetName + "Wrapper"
    firePins(top.target) = top.firePin
    stateIns(top.target) = top.stateIn
    stateOuts(top.target) = top.stateOut
    sramIns(top.target) = top.sramIn
    sramOuts(top.target) = top.sramOut
  }

  def connectFireSignals(c: Module) {
    ChiselError.info("[DaisyBackend] connect fire signals")

    def connectFirePins(m: Module) {
      if (!(firePins contains m)) {
        firePins(m) = m.addPin(Bool(INPUT), "fire")
        if (!(firePins contains m.parent)) connectFirePins(m.parent)
        firePins(m) := firePins(m.parent)
      }
    }

    for (m <- Driver.sortedComps ; if m.name != top.name) {
      states(m) = ArrayBuffer[Node]()
      srams(m) = ArrayBuffer[Mem[_]]()
      connectFirePins(m)
      // Add target's inputs
      if (m.name == top.target.name) states(m) ++= top.inputs
      m bfs { _ match {
        case reg: Reg => {
          connectFirePins(m)
          reg.inputs(0) = Multiplex(firePins(m), reg.inputs(0), reg)
          // Add the register for daisy chains
          states(m) += reg
        }
        case mem: Mem[_] => {
          connectFirePins(m)
          for (write <- mem.writeAccesses) {
            write.inputs(1) = write.cond.asInstanceOf[Bool] && firePins(m) 
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
        stateOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)), "state_out")
      }
    }

    def insertStateChain(m: Module) = {
      val datawidth = (states(m) foldLeft 0)(_ + _.needWidth())
      val chain = if (!states(m).isEmpty) m.addModule(new StateChain(datawidth)) else null
      if (chain != null) {
        if (m.name != top.name) insertStatePins(m)
        chain.io.data := UInt(Concatenate(states(m)))
        chain.io.stall := !firePins(m)
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

    def connectRestartPins(m: Module) {
      if (!(restartPins contains m)) {
        restartPins(m) = m.addPin(Bool(INPUT), "restart")
        if (!(restartPins contains m.parent)) connectRestartPins(m.parent)
        restartPins(m) := restartPins(m.parent)
      }
    }

    def insertSRAMPins(m: Module) {
      sramIns(m)  = m.addPin(Decoupled(UInt(width=daisywidth)).flip, "sram_in")
      sramOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)),      "sram_out")
    }

    def insertSRAMChain(m: Module) = {
      var lastChain: SRAMChain = null
      for (sram <- srams(m)) {
        val read = sram.readAccesses.head
        val datawidth = sram.needWidth()
        val chain = m.addModule(new SRAMChain(sram.size, datawidth))
        chain.io.data := UInt(Concatenate(states(m)))
        chain.io.stall := !firePins(m)
        if (lastChain == null) {
          connectRestartPins(m)
          insertSRAMPins(m)
          chain.io.out <> sramOuts(m)
        } else {
          lastChain.io.in.bits := chain.io.out.bits
          lastChain.io.in.valid := chain.io.out.valid
          chain.io.out.ready := sramOuts(m).ready
        }
        chain.io.restart := restartPins(m)
        read.addr.getNode.inputs(0) = Multiplex(
          chain.io.addr.valid, 
          chain.io.addr.bits, 
          read.addr.getNode.inputs(0))
        lastChain = chain
        sramChainLength += datawidth
      }
      lastChain
    }

    for (m <- Driver.sortedComps) {
      val sramChain = insertSRAMChain(m)
   
      // Filter children who have sram chains
      var last = -1
      for ((child, cur) <- m.children.zipWithIndex; if (sramOuts contains child)) {
        if (last < 0) {
          if (sramChain != null) {
            sramChain.io.in.bits := sramOuts(child).bits
            sramChain.io.in.valid := sramOuts(child).valid
            sramOuts(child).ready := sramOuts(m).ready
          } else {
            connectRestartPins(m)
            insertSRAMPins(m)
            sramOuts(m) <> sramOuts(child)
          }
        } else {
          sramOuts(child) <> sramOuts(m.children(last))
        }
        last = cur
      }

      if (last > -1) {
        val lastChild = m.children(last)
        if (m.name == top.name) {
          sramIns(lastChild).bits := UInt(0)
          sramIns(lastChild).valid := Bool(false)
        } else {
          sramIns(lastChild).bits := sramIns(m).bits
          sramIns(lastChild).valid := sramIns(m).valid
        }
      } else if (sramChain != null) {
        sramChain.io.in.bits := sramIns(m).bits
        sramChain.io.in.valid := sramIns(m).valid
      }     
    } 
  }

  def initSRAMChainCounter(c: Module) {
    /*
    if (sramChainLength > 0) {
      val valid = sramOuts(top.target).valid
      when (top.io.stall && !top.restart && valid) {
        top.sramChainCounter := top.sramChainCounter - UInt(1)
      }.elsewhen (!valid) {
        top.sramChainCounter := UInt(sramChainLength)
      }
    } 
    */
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
    val stateFile = Driver.createOutputFile(targetName + ".state.map")
    // Collect states
    for (m <- Driver.sortedComps.reverse ; if m.name != top.name) {
      for (state <- states(m)) {
        state match {
          case read: MemRead => {
            val mem = read.mem
            val addr = read.addr.litValue(0).toInt
            val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + mem.name
            res append "%s[%d] %d\n".format(path, addr, mem.needWidth)
          }
          case _ => { 
            val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + state.name
            res append "%s %d\n".format(path, state.needWidth)
          }
        }
      }
    }
    try {
      stateFile write res.result
    } finally {
      stateFile.close
      res.clear
    }
  }
}
