package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.collection.mutable.{Queue => ScalaQueue}

object DaisyBackend { 
  val chains = HashSet[Module]()
  val firePins = HashMap[Module, Bool]()
  val regsIns = HashMap[Module, ValidIO[UInt]]()
  val regsOuts = HashMap[Module, DecoupledIO[UInt]]()
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
      } else {
        firePins(m) = m.addPin(Bool(INPUT), "fire")
        regsIns(m) = m.addPin(Valid(UInt(width=daisywidth)).flip, "regs_in")
        regsOuts(m) = m.addPin(Decoupled(UInt(width=daisywidth)), "regs_out")
      } 
    } 
  }

  def addIOBuffers(c: Module) {
    ChiselError.info("[DaisyBackend] add io buffers")
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
      firePins(m) := firePins(m.parent)
      m bfs { _ match {
        case reg: Reg =>
          reg.inputs(0) = Multiplex(firePins(m), reg.inputs(0), reg)
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
    for (m <- Driver.sortedComps) { 
      if (m.name == top.name) {
        top.io.regsOut <> regsOuts(top.target)
        regsIns(top.target).bits := UInt(0)
        regsIns(top.target).valid := Bool(false)
      } else {
        val elems = ArrayBuffer[Node]()
        m bfs { _ match {
          case reg: Reg => elems += reg
          case _ =>
        } }
        if (elems.isEmpty) {
          if (m.children.isEmpty) {
            regsOuts(m).bits := regsIns(m).bits
            regsOuts(m).valid := regsIns(m).valid
          } else {
            regsOuts(m) <> regsOuts(m.children.head)
            regsIns(m.children.last) <> regsIns(m)
          }
        } else {
          val datawidth = (elems foldLeft 0)(_ + _.needWidth())
          val chain = m.addModule(new RegChain(datawidth))
          chains += chain
          chain.io.dataIn := UInt(Concatenate(elems))
          chain.io.stall  := !firePins(m) 
          regsOuts(m) <> chain.io.regsOut
          if (m.children.size > 1) {
            chain.io.regsIn.bits := regsOuts(m.children.head).bits
            chain.io.regsIn.valid := regsOuts(m.children.head).valid
            regsIns(m.children.last) <> regsIns(m)
          } else {
            chain.io.regsIn <> regsIns(m)
          }
        }

        for (s <- m.children sliding 2 ; if s.size == 2) {
          regsIns(s.head).bits := regsOuts(s.last).bits
          regsIns(s.head).valid := regsOuts(s.last).valid
          regsOuts(s.last).ready := regsOuts(s.head).ready
        }
      } 
    }
  }  
}
