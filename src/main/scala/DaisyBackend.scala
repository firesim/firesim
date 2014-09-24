package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.collection.mutable.{Queue => ScalaQueue}

object DaisyBackend { 
  val firePins = HashMap[Module, Bool]()
  val states = HashMap[Module, ArrayBuffer[Node]]()
  val stateIns = HashMap[Module, ValidIO[UInt]]()
  val stateOuts = HashMap[Module, DecoupledIO[UInt]]()
  val sramIns = HashMap[Module, ValidIO[UInt]]()
  val sramOuts = HashMap[Module, DecoupledIO[UInt]]()
  val cntrIns = HashMap[Module, ValidIO[UInt]]()
  val cntrOuts = HashMap[Module, DecoupledIO[UInt]]()
  val ioMap = HashMap[Bits, Bits]()
  lazy val top = Driver.topComponent.asInstanceOf[DaisyWrapper[Module]]
  var daisywidth = -1

  def addTransforms(width: Int = 1) {
    daisywidth = width
    Driver.backend.transforms += addDaisyPins
    Driver.backend.transforms += Driver.backend.findConsumers
    Driver.backend.transforms += addIOBuffers
    Driver.backend.transforms += connectFireSignals
    Driver.backend.transforms += Driver.backend.inferAll
    Driver.backend.transforms += addSnapshotChains
  } 

  def addDaisyPins(c: Module) {
    ChiselError.info("[DaisyBackend] add daisy pins")
    for (m <- Driver.sortedComps) {
      if (m.name == top.name) {
        firePins(m) = top.stepCounter.orR
        stateOuts(m) = top.io.stateOut
      } else {
        firePins(m) = m.addPin(Bool(INPUT), "fire")
        stateIns(m) = m.addPin(Valid(UInt(width=daisywidth)).flip, "state_in")
        stateOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)), "state_out")
      } 
    } 
  }

  def addIOBuffers(c: Module) {
    ChiselError.info("[DaisyBackend] add io buffers")
    states(top) = ArrayBuffer[Node]()
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
    for (m <- Driver.sortedComps ; if m.name != top.name) {
      states(m) = ArrayBuffer[Node]()
      firePins(m) := firePins(m.parent)
      m bfs { _ match {
        case reg: Reg => {
          reg.inputs(0) = Multiplex(firePins(m), reg.inputs(0), reg)
          // Regs are the state of the design
          states(m) += reg
        }
        case mem: Mem[_] => {
          for (write <- mem.writeAccesses) {
            write.inputs(1) = write.cond.asInstanceOf[Bool] && firePins(m) 
          }
        }
        case _ =>
      } }
    }
  }

  def addSnapshotChains(c: Module) {
    ChiselError.info("[DaisyBackend] add snapshot chains")
    def insertStateChain(m: Module) = {
      val datawidth = (states(m) foldLeft 0)(_ + _.needWidth())
      val chain = m.addModule(new StateChain(datawidth))
      chain.io.data := UInt(Concatenate(states(m)))
      chain.io.stall := !firePins(m)
      chain.io.out <> stateOuts(m)
      if (m.children.size > 1) {
        chain.io.in.bits := stateOuts(m.children.head).bits
        chain.io.in.valid := stateOuts(m.children.head).valid
        stateOuts(m.children.head).ready := stateOuts(m).ready
      } else {
        chain.io.in <> stateIns(m)
      }
      chain
    }

    for (m <- Driver.sortedComps) { 
      if (m.name == top.name) {
        if (states(m).isEmpty) {
          stateOuts(m) <> stateOuts(top.target)
        } else {
          insertStateChain(m)
        }
        stateIns(top.target).bits := UInt(0)
        stateIns(top.target).valid := Bool(false)
      } else {
        if (states(m).isEmpty) {
          if (m.children.isEmpty) {
            stateOuts(m).bits := stateIns(m).bits
            stateOuts(m).valid := stateIns(m).valid
          } else {
            stateOuts(m) <> stateOuts(m.children.head)
            stateIns(m.children.last).bits := stateIns(m).bits
            stateIns(m.children.last).valid := stateIns(m).valid
          }
        } else {
          insertStateChain(m)
          if (m.children.size > 1) {
            stateIns(m.children.last).bits := stateIns(m).bits
            stateIns(m.children.last).valid := stateIns(m).valid
          } 
        }

        for (s <- m.children sliding 2 ; if s.size == 2) {
          stateIns(s.head).bits := stateOuts(s.last).bits
          stateIns(s.head).valid := stateOuts(s.last).valid
          stateOuts(s.last).ready := stateOuts(s.head).ready
        }
      } 
    }
  }
}
