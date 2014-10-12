package DebugMachine

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import addDaisyPins._

object DaisyBackend { 
  val states = HashMap[Module, ArrayBuffer[Node]]()
  val srams  = HashMap[Module, ArrayBuffer[Mem[_]]]()
  lazy val top = Driver.topComponent.asInstanceOf[DaisyShim[Module]]
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
    for (m <- Driver.sortedComps ; 
    if m.name != top.name && m.name != top.target.name) {
      addDaisyPins(m, daisywidth)
    }
  }

  // Connect the stall signal to the register and memory writes for freezing
  def connectStallSignals(c: Module) {
    ChiselError.info("[DaisyBackend] connect stall signals")

    def connectStallPins(m: Module) {
      if (daisyPins(m).stall.inputs.isEmpty && m.name != top.target.name) {
        connectStallPins(m.parent)
        daisyPins(m).stall := daisyPins(m.parent).stall
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
          reg.inputs(0) = Multiplex(daisyPins(m).stall, reg, reg.inputs(0))
          // Add the register for daisy chains
          states(m) += reg
        }
        case mem: Mem[_] => {
          connectStallPins(m)
          for (write <- mem.writeAccesses) {
            write cond_= write.cond.asInstanceOf[Bool] && !daisyPins(m).stall
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

    val hasStateChain = HashSet[Module]()
    def insertStateChain(m: Module) = {
      val datawidth = (states(m) foldLeft 0)(_ + _.needWidth)
      val chain = if (!states(m).isEmpty) m.addModule(new StateChain, {case DataWidth => datawidth}) else null
      if (chain != null) {
        chain.io.dataIo.data := UInt(Concatenate(states(m)))
        chain.io.dataIo.out <> daisyPins(m).state.out
        chain.io.stall := daisyPins(m).stall
        hasStateChain += m
      } 
      chain
    }

    for (m <- Driver.sortedComps ; if m.name != top.name) {
      val stateChain = insertStateChain(m)
      // Filter children who have state chains
      var last = -1
      for ((child, cur) <- m.children.zipWithIndex ; if hasStateChain contains child) {
        if (last < 0) {
          if (stateChain != null) {
            stateChain.io.dataIo.in <> daisyPins(child).state.out
          } else {
            daisyPins(m).state.out <> daisyPins(child).state.out
          }
        } else {
          val lastChild = m.children(last)
          daisyPins(lastChild).state.out <> daisyPins(m).state.in
        }
        last = cur
      }

      if (last > -1) {
        hasStateChain += m
        daisyPins(m).state.in <> daisyPins(m.children(last)).state.in
      } else if (stateChain != null) {
        daisyPins(m).state.in <> stateChain.io.dataIo.in
      }
    }
  } 

  def addSRAMChain(c: Module) {
    ChiselError.info("[DaisyBackend] add sram chains")

    def connectSRAMRestarts(m: Module) {
      if (daisyPins(m).stall.inputs.isEmpty && m.name != top.target.name) {
        connectSRAMRestarts(m.parent)
        daisyPins(m).sram.restart := daisyPins(m.parent).sram.restart
      }
    }

    val hasSRAMChain = HashSet[Module]()
    def insertSRAMChain(m: Module) = {
      var lastChain: SRAMChain = null
      for (sram <- srams(m)) {
        val read = sram.readAccesses.head
        val datawidth = sram.needWidth
        val chain = m.addModule(new SRAMChain, {
          case DataWidth => datawidth 
          case SRAMSize => sram.size})
        chain.io.stall := daisyPins(m).stall
        chain.io.dataIo.data := UInt(read)
        if (lastChain == null) {
          connectSRAMRestarts(m)
          daisyPins(m).sram.out <> chain.io.dataIo.out
        } else {
          lastChain.io.dataIo.in <> chain.io.dataIo.out
        }
        chain.io.restart := daisyPins(m).sram.restart
        // Connect chain addr to SRAM addr
        val readAddr = read.addr.asInstanceOf[Bits]
        chain.io.addrIo.in := readAddr
        when(chain.io.addrIo.out.valid) {
          readAddr := chain.io.addrIo.out.bits
        }
        lastChain = chain
      }
      if (lastChain != null) hasSRAMChain += m
      lastChain
    }

    if (Driver.hasSRAM) {
      for (m <- Driver.sortedComps ; if m.name != top.name) {
        val sramChain = insertSRAMChain(m)   
        // Filter children who have sram chains
        var last = -1
        for ((child, cur) <- m.children.zipWithIndex; if hasSRAMChain contains child) {
          if (last < 0) {
            if (sramChain != null) {
              sramChain.io.dataIo.in <> daisyPins(child).sram.out
            } else {
              connectSRAMRestarts(m)
              daisyPins(m).sram.out <> daisyPins(child).sram.out
            }
          } else {
            daisyPins(child).sram.out <> daisyPins(m.children(last)).sram.out
          }
          last = cur
        }

        if (last > -1) {
          hasSRAMChain += m
          daisyPins(m).sram.in <> daisyPins(m.children(last)).sram.in
        } else if (sramChain != null) {
          daisyPins(m).sram.in <> sramChain.io.dataIo.in
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
