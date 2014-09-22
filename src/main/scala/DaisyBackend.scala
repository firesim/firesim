package faee

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}
import scala.collection.mutable.{Queue => ScalaQueue}

object DaisyBackend extends Backend {
  val keywords = HashSet[String]()
  val chains = HashSet[Module]()
  val newNodes = HashSet[Node]()
  val firePins = HashMap[Module, Bool]()
  val regsIns = HashMap[Module, ValidIO[UInt]]()
  val regsOuts = HashMap[Module, DecoupledIO[UInt]]()
  val sramIns = HashMap[Module, ValidIO[UInt]]()
  val sramOuts = HashMap[Module, DecoupledIO[UInt]]()
  val cntrIns = HashMap[Module, ValidIO[UInt]]()
  val cntrOuts = HashMap[Module, DecoupledIO[UInt]]()
  lazy val top = Driver.topComponent.asInstanceOf[DaisyWrapper]
  var daisywidth = -1

  def addTransforms(width: Int = 1) {
    daisywidth = width
    Driver.backend.transforms += addDaisyPins
    Driver.backend.transforms += findConsumers
    Driver.backend.transforms += addIOBuffers
    Driver.backend.transforms += connectFireSignals
    Driver.backend.transforms += inferAll
    Driver.backend.transforms += addSnapshotChains
  } 

  def addPin[T <: Data](m: Module, pin: T, name: String) = {
    for ((n, io) <- pin.flatten) {
      io.component = m
      io.isIo = true
    }
    if (name != "")
      pin nameIt (name, true)
    m.io.asInstanceOf[Bundle] += pin
    newNodes += pin
    pin
  }

  def addDaisyPins(c: Module) {
    ChiselError.info("[DaisyBackend] add daisy pins")
    for (m <- Driver.sortedComps) {
      if (m.name == top.name) {
        firePins(m) = top.stepCounter.orR
      } else {
        firePins(m) = addPin(m, Bool(INPUT), "fire")
        regsIns(m) = addPin(m, Valid(UInt(width=daisywidth)).flip, "regs_in")
        regsOuts(m) = addPin(m, Decoupled(UInt(width=daisywidth)), "regs_out")
      } 
    } 
  }

  def addIOBuffers(c: Module) {
    ChiselError.info("[DaisyBackend] add io buffers")
    for ((n, pin) <- top.io.targetIO.flatten) {
      val buffer = Reg(UInt())
      buffer.comp.setName(pin.name + "_buf")
      buffer.comp.component = top
      newNodes += buffer.comp
      if (pin.dir == INPUT) {
        when(top.io.stepsIn.valid || top.stepCounter.orR) {
          buffer := pin
        }
        for (consumer <- pin.consumers) 
          consumer.inputs(0) = buffer
      }
      else if (pin.dir == OUTPUT) {
        when(top.stepCounter.orR) {
          buffer := pin.inputs(0).asInstanceOf[Bits]
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
        case reg: Reg if !(newNodes contains reg) =>
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
          case reg: Reg if !(newNodes contains reg) => elems += reg
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
          val chain = Module(new RegChain(datawidth), m)
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
