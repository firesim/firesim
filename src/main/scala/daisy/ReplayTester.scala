package Daisy

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap}
import scala.io.Source

class ReplayTester[+T <: Module](c: T) extends Tester(c) {
  lazy val basedir = ensureDir(Driver.targetDir)

  def poke(name: String, value: BigInt) {
    val cmd = "wire_poke %s %d".format(name, value)
    if (emulatorCmd(cmd) != "ok") {
       System.err.print("POKE %s with %d FAILED\n".format(name, value))
    }
  }

  def poke(name: String, value: BigInt, off: Int) = {
    val cmd = "mem_poke %s %d %d".format(name, off, value)
    if (emulatorCmd(cmd) != "ok") {
       System.err.print("POKE %s with %d FAILED\n".format(name, value))
    }
  }

  def peek(name: String) = {
    val cmd = "wire_peek %s".format(name)
    Literal.toLitVal(emulatorCmd(cmd))
  }

  def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def parseHex(hex: String) = {
    var data = BigInt(0)
    for (digit <- hex) {
      data = (data << 4) | parseNibble(digit)
    }
    data
  }

  def loadSnap(filename: String) {
    val MemRegex = """([\w\.]+)\[(\d+)\]""".r
    val lines = scala.io.Source.fromFile(basedir + "/" + filename).getLines
    for (line <- lines) {
      val tokens = line split " "
      tokens.head match {
        case "STEP" => {
          val n = Integer.parseInt(tokens.last, 16)
          step(n)
        }
        case "EXPECT" => {
          val signal = tokens.tail.head
          val expected = parseHex(tokens.last)
          val got = peek(signal)
          expect(got == expected, "EXPECT %s <- %d == %d".format(signal, got, expected))
        }
        case "//" => // TODO: ??
        case MemRegex(name, idx) => {
          val value = parseHex(tokens.last)
          println("LOAD %s[%s] <- %s".format(name, idx, value.toString))
          poke(name, value, idx.toInt)
        }
        case signal => {
          val value = parseHex(tokens.last)
          println("LOAD %s <- %s".format(signal, value.toString))
          poke(signal, value)
        }
      }
    }
  }

  loadSnap(c.name + ".snap")
}
