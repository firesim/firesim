package daisy

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap}
import scala.io.Source

abstract class ReplayTester[+T <: Module](c: T) extends Tester(c) {
  lazy val basedir = ensureDir(Driver.targetDir)

  def poke(name: String, value: String) {
    val cmd = "wire_poke %s 0x%s".format(name, value)
    if (emulatorCmd(cmd) != "ok") {
       System.err.print("POKE %s with %d FAILED\n".format(name, value))
    }
  }

  def poke(name: String, value: String, off: Int) = {
    val cmd = "mem_poke %s %d 0x%s".format(name, off, value)
    if (emulatorCmd(cmd) != "ok") {
       System.err.print("POKE %s with %d FAILED\n".format(name, value))
    }
  }

  def peek(name: String) = {
    val cmd = "wire_peek %s".format(name)
    Literal.toLitVal(emulatorCmd(cmd))
  }

  def replayMem(addr: BigInt, data: BigInt) { }
  
  def runTests(args: Any*) { }

  def loadSnap(filename: String, args: Any*) {
    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "POKE" => {
          val signal = tokens.tail.head
          val value = tokens.last
          println("POKE %s <- %s".format(signal, BigInt(value, 16)))
          signal match {
            case MemRegex(name, idx) =>
              poke(name, value, idx.toInt)
            case _ => 
              poke(signal, value)
          }
        }
        case "LOAD" => {
          val addr = BigInt(tokens.tail.head, 16)
          val data = BigInt(tokens.last, 16)
          replayMem(addr, data)        
        }
        case "STEP" => {
          val n = tokens.last.toInt
          step(n)
        }
        case "EXPECT" => {
          val signal = tokens.tail.head
          val expected = BigInt(tokens.last, 16)
          val got = peek(signal)
          expect(got == expected, "EXPECT %s <- %d == %d".format(signal, got, expected))
        }
        case "//" => 
          runTests(args:_*)
        case _ =>
      }
    }
  }
}

class Replay[+T <: Module](c: T) extends ReplayTester(c) {
  lazy val filename = c.name + ".snap"
  loadSnap(filename)
}
