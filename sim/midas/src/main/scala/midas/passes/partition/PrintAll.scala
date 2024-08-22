package midas.passes.partition

import firrtl._
import firrtl.ir._

class PrintAllPass extends Transform with DependencyAPIMigration {
  def execute(state: CircuitState): CircuitState = {
    state
  }

  def walkModules(m: DefModule): DefModule = {
    println(s"defModule ${m.name}")
    val ports = m.ports
    ports.foreach(p => println(s"port ${p.name} ${p.direction} ${p.tpe}"))

    m match {
      case Module(_, _, _, b)             =>
        b.foreachStmt(s => println(s"statement ${s}"))
      case ExtModule(_, _, _, dn, params) =>
        params.foreach(_ => println("param ${param}"))
        println(s"defName ${dn}")
    }
    m
  }
}
