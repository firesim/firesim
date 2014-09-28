package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.collection.mutable.{Queue => ScalaQueue}

object DaisyBackend { 
  val firePins = HashMap[Module, Bool]()
  val restartPins = HashMap[Module, Bool]()
  val states = HashMap[Module, ArrayBuffer[Node]]()
  val srams = HashMap[Module, ArrayBuffer[Mem[_]]]()
  val stateIns = HashMap[Module, ValidIO[UInt]]()
  val stateOuts = HashMap[Module, DecoupledIO[UInt]]()
  val sramIns = HashMap[Module, ValidIO[UInt]]()
  val sramOuts = HashMap[Module, DecoupledIO[UInt]]()
  val cntrIns = HashMap[Module, ValidIO[UInt]]()
  val cntrOuts = HashMap[Module, DecoupledIO[UInt]]()
  val ioMap = HashMap[Bits, Bits]()
  lazy val top = Driver.topComponent.asInstanceOf[DaisyWrapper[Module]]
  lazy val targetName = Driver.backend.extractClassName(top.target)
  var daisywidth = -1
  var sramCount = 0

  def addTransforms(width: Int = 1) {
    daisywidth = width
    Driver.backend.transforms ++= Seq(
      setTopModuleName,
      addTopLevelPins,
      Driver.backend.findConsumers,
      addIOBuffers,
      Driver.backend.inferAll,
      connectFireSignals,
      addStateChains,
      addSRAMChain,
      Driver.backend.findConsumers,
      printOutMappings
    )
  } 

  def setTopModuleName(c: Module) {
    top.name = targetName + "Wrapper"
  }

  def addTopLevelPins(c: Module) {
    firePins(top) = top.stepCounter.orR
    restartPins(top) = Bool(false)// !top.sramChainCounter.orR
    stateOuts(top) = top.io.stateOut
    sramOuts(top) = top.io.sramOut
  }

  def addIOBuffers(c: Module) {
    ChiselError.info("[DaisyBackend] add io buffers")
    states(top) = ArrayBuffer[Node]()
    srams(top) = ArrayBuffer[Mem[_]]()
    for ((n, pin) <- top.io.targetIO.flatten) {
      val buffer = Reg(UInt())
      buffer.comp.setName(pin.name + "_buf")
      buffer.comp.component = top
      if (pin.dir == INPUT) {
        // build ioMap for testers
        ioMap(pin.consumers.head.asInstanceOf[Bits]) = pin
        // insert and connect the io buffer
        when(top.io.stepsIn.valid || top.fire) {
          buffer := pin
        }
        for (consumer <- pin.consumers) 
          consumer.inputs(0) = buffer
        // inputs are the state of the design
        states(top) += buffer
      }
      else if (pin.dir == OUTPUT) {
        // build ioMap for testers
        ioMap(pin.inputs.head.asInstanceOf[Bits]) = pin
        // insert and connect the io buffer
        when(top.fireDelay) {
          buffer := pin.inputs.head.asInstanceOf[Bits]
        }
        pin.inputs(0) = buffer
      }
    }
  }

  def connectFireSignals(c: Module) {
    ChiselError.info("[DaisyBackend] connect fire signals")

    def connectFirePins(m: Module) {
      firePins(m) = m.addPin(Bool(INPUT), "fire")
      if (!(firePins contains m.parent)) connectFirePins(m.parent)
      firePins(m) := firePins(m.parent)
    }

    for (m <- Driver.sortedComps ; if m.name != top.name) {
      states(m) = ArrayBuffer[Node]()
      srams(m) = ArrayBuffer[Mem[_]]()
      connectFirePins(m)
      m bfs { _ match {
        case reg: Reg => {
          if (!(firePins contains m)) connectFirePins(m)
          reg.inputs(0) = Multiplex(firePins(m), reg.inputs(0), reg)
          // Add the register for daisy chains
          states(m) += reg
        }
        case mem: Mem[_] => {
          if (!(firePins contains m)) connectFirePins(m)
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
      stateIns(m)  = m.addPin(Valid(UInt(width=daisywidth)).flip, "state_in")
      stateOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)), "state_out")
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

    for (m <- Driver.sortedComps) {
      val stateChain = insertStateChain(m)

      // Filter children who have state chains
      var last = -1
      for ((child, cur) <- m.children.zipWithIndex; if (stateOuts contains child)) {
        if (last < 0) {
          if (stateChain != null) {
            stateChain.io.in.bits := stateOuts(child).bits
            stateChain.io.in.valid := stateOuts(child).valid
            stateOuts(child).ready := stateOuts(m).ready
          } else {
            insertStatePins(m)
            stateOuts(m) <> stateOuts(child)
          }
        } else {
          val lastChild = m.children(last)
          stateIns(child).bits := stateOuts(lastChild).bits
          stateIns(child).valid := stateOuts(lastChild).valid
          stateOuts(lastChild).ready := stateOuts(child).ready
        }
        last = cur
      }

      if (last > -1) {
        val lastChild = m.children(last)
        if (m.name == top.name) {
          stateIns(lastChild).bits := UInt(0)
          stateIns(lastChild).valid := Bool(false)
        } else {
          stateIns(lastChild).bits := stateIns(m).bits
          stateIns(lastChild).valid := stateIns(m).valid
        }
      } else if (stateChain != null) {
        stateChain.io.in.bits := stateIns(m).bits
        stateChain.io.in.valid := stateIns(m).valid
      }
    }
  } 

  def addSRAMChain(c: Module) {
    ChiselError.info("[DaisyBackend] add sram chains")

    def connectRestartPins(m: Module) {
      restartPins(m) = m.addPin(Bool(INPUT), "restart")
      if (!(restartPins contains m.parent)) connectRestartPins(m.parent)
      restartPins(m) := restartPins(m.parent)
    }

    def insertSRAMPins(m: Module) {
      sramIns(m)  = m.addPin(Valid(UInt(width=daisywidth)).flip, "sram_in")
      sramOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)), "sram_out")
    }

    def insertSRAMChain(m: Module) = {
      var lastChain: SRAMChain = null
      for (sram <- srams(m)) {
        val datawidth = sram.needWidth()
        val chain = m.addModule(new SRAMChain(sram.size, datawidth))
        chain.io.data := UInt(Concatenate(states(m)))
        chain.io.stall := !firePins(m)
        if (lastChain == null) {
          // connectRestartPins(m)
          insertSRAMPins(m)
          chain.io.out <> sramOuts(m)
        } else {
          chain.io.in.bits := lastChain.io.out.bits
          chain.io.in.valid := lastChain.io.out.valid
          // lastChain.io.out.ready := chain.io.out.ready
        }
        lastChain = chain
      }
      lastChain
    }

    for (m <- Driver.sortedComps) {
      val sramChain  = insertSRAMChain(m)
   
      // Filter children who have sram chains
      var last = -1
      for ((child, cur) <- m.children.zipWithIndex; if (sramOuts contains child)) {
        if (last < 0) {
          if (sramChain != null) {
            sramChain.io.in.bits := sramOuts(child).bits
            sramChain.io.in.valid := sramOuts(child).valid
            sramOuts(child).ready := sramOuts(m).ready
          } else {
            // connectRestartPins(m)
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

  def printOutMappings(c: Module) {
    ChiselError.info("[DaisyBackend] print out chain mappings")
    val prefix = top.name + "." + top.target.name

    // Print out the chain mapping
    val stateOut = new StringBuilder
    val stateFile = Driver.createOutputFile(targetName + ".state.chain")
    // Collect states
    for (m <- Driver.sortedComps.reverse ; state <- states(m)) {
      if (m.name == top.name) {
        // Add input pins of the target instead of their io buffers
        val pin = state.consumers.head
        val path = targetName + "." + pin.name
        stateOut append "%s %d\n".format(path, pin.needWidth)
      } else {
        state match {
          case read: MemRead => {
            val mem = read.mem
            val addr = read.addr.litValue(0).toInt
            val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + mem.name
            stateOut append "%s[%d] %d\n".format(path, addr, mem.needWidth)
          }
          case _ => { 
            val path = targetName + "." + (m.getPathName(".") stripPrefix prefix) + state.name
            stateOut append "%s %d\n".format(path, state.needWidth)
          }
        }
      }
    }
    try {
      stateFile write stateOut.result
    } finally {
      stateFile.close
    }
  }
}
