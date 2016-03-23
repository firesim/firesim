
package midas

//import Chisel._
import firrtl._
import firrtl.Utils._

object Utils {

  // Merges a sequence of maps via the provided function f
  // Taken from: https://groups.google.com/forum/#!topic/scala-user/HaQ4fVRjlnU
  def merge[K, V](maps: Seq[Map[K, V]])(f: (K, V, V) => V): Map[K, V] = {
    maps.foldLeft(Map.empty[K, V]) { case (merged, m) =>
      m.foldLeft(merged) { case (acc, (k, v)) =>
        acc.get(k) match {
          case Some(existing) => acc.updated(k, f(k, existing, v))
          case None => acc.updated(k, v)
        }
      }
    }
  }

  // This doesn't work because of Type Erasure >.<
  //private def getStmts[A <: Stmt](s: Stmt): Seq[A] = {
  //  s match {
  //    case a: A => Seq(a)
  //    case b: Begin => b.stmts.map(getStmts[A]).flatten
  //    case _ => Seq()
  //  }
  //}
  //private def getStmts[A <: Stmt](m: InModule): Seq[A] = getStmts[A](m.stmt)

  def getDefRegs(s: Stmt): Seq[DefRegister] = {
    s match {
      case r: DefRegister => Seq(r)
      case b: Begin => b.stmts.map(getDefRegs).flatten
      case _ => Seq()
    }
  }
  def getDefRegs(m: InModule): Seq[DefRegister] = getDefRegs(m.body)

  def getDefInsts(s: Stmt): Seq[DefInstance] = {
    s match {
      case i: DefInstance => Seq(i)
      case b: Begin => b.stmts.map(getDefInsts).flatten
      case _ => Seq()
    }
  }
  def getDefInsts(m: Module): Seq[DefInstance] =
    m match { case m: InModule => getDefInsts(m.body)
      case m: ExModule => Seq()
    }

  // Takes a set of strings or ints and returns equivalent expression node
  //   Strings correspond to subfields/references, ints correspond to indexes
  // eg. Seq(io, port, ready)    => io.port.ready
  //     Seq(io, port, 5, valid) => io.port[5].valid
  //     Seq(3)                  => UInt("h3")
  def buildExp(names: Seq[Any]): Expression = {
    def rec(names: Seq[Any]): Expression = {
      names.head match {
        // Useful for adding on indexes or subfields
        case head: Expression => head
        // Int -> UInt/SInt/Index
        case head: Int =>
          if( names.tail.isEmpty ) // Is the UInt/SInt inference good enough?
            if( head > 0 ) UIntValue(head, UnknownWidth()) else SIntValue(head, UnknownWidth())
          else WSubIndex(rec(names.tail), head, UnknownType(), UNKNOWNGENDER)
        // String -> Ref/Subfield
        case head: String =>
          if( names.tail.isEmpty ) Ref(head, UnknownType())
          else WSubField(rec(names.tail), head, UnknownType(), UNKNOWNGENDER)
        case _ => throw new Exception("Invalid argument type to buildExp! " + names)
      }
    }
    rec(names.reverse) // Let user specify in more natural format
  }
  def buildExp(name: Any): Expression = buildExp(Seq(name))

  def genPrimopReduce(op: PrimOp, args: Seq[Expression]): Expression = {
    if( args.length == 0 ) throw new Exception("genPrimopReduce called on empty sequence!")
    else if( args.length == 1 ) args.head
    else if( args.length == 2 ) DoPrim(op, Seq(args.head, args.last), Seq(), UnknownType())
    else DoPrim(op, Seq(args.head, genPrimopReduce(op, args.tail)), Seq(), UnknownType())
  }

  // Checks if a firrtl.Port matches the MIDAS SimPort pattern
  // This currently just checks that the port is of type bundle with ONLY the members
  //   hostIn and/or hostOut with correct directions
  def isSimPort(port: Port): Boolean = {
    //println("isSimPort called on port " + port.serialize)
    port.tpe match {
      case b: BundleType => {
        b.fields map { field =>
          if( field.name == "hostIn" ) field.flip == REVERSE
          else if( field.name == "hostOut" ) field.flip == DEFAULT
          else false
        } reduce ( _ & _ )
      }
      case _ => false
    }
  }

  def splitSimPort(port: Port): Seq[Field] = {
    try {
      val b = port.tpe.asInstanceOf[BundleType]
      Seq(b.fields.find(_.name == "hostIn"), b.fields.find(_.name == "hostOut")).flatten
    } catch {
      case e: Exception => throw new Exception("Invalid SimPort " + port.serialize)
    }
  }

  // From simulation host decoupled, return hostbits field
  def getHostBits(field: Field): Field = {
    try {
      val b = field.tpe.asInstanceOf[BundleType]
      b.fields.find(_.name == "hostBits").get
    } catch {
      case e: Exception => throw new Exception("Invalid SimField " + field.serialize)
    }
  }

  // For a port that is known to be of type BundleType, return the fields of that bundle
  def getFields(port: Port): Seq[Field] = {
    port.tpe match {
      case b: BundleType => b.fields
      case _ => throw new Exception("getFields called on invalid port " + port)
    }
  }

  // Recursively iterates through firrtl.Type returning sequence of names to address signals
  //  * Intended for use with recursive bundle types
  def enumerateMembers(tpe: Type): Seq[Seq[Any]] = {
    def rec(tpe: Type, path: Seq[Any], members: Seq[Seq[Any]]): Seq[Seq[Any]] = {
      tpe match {
        case b: BundleType => (b.fields map ( f => rec(f.tpe, path :+ f.name, members) )).flatten
        case v: VectorType => (Seq.tabulate(v.size.toInt) ( i => rec(v.tpe, path :+ i, members) )).flatten
        case _ => members :+ path
      }
    }
    rec(tpe, Seq[Any](), Seq[Seq[Any]]())
  }

  // MIDAS Queue, ideally we could directly use Chisel instead of a FIRRTL string

  // Queue
  // TODO
  //  - Make more robust (use Chisel instead of FIRRTL string?)
  //  - Insert null tokens upon hostReset (or should this be elsewhere?)
  def buildSimQueue(name: String, tpe: Type, init: Boolean = true): Module = {
    val scopeSpaces = " " * 4 // Spaces before lines in module scope, for default assignments
    val templatedQueue =
    """
circuit `NAME :
  module `NAME :
    input hostClock : Clock
    input hostReset : UInt<1>
    output io : {flip enq : {flip hostReady : UInt<1>, hostValid : UInt<1>, hostBits : `TYPE}, deq : {flip hostReady : UInt<1>, hostValid : UInt<1>, hostBits : `TYPE}, count : UInt<3>}

    io is invalid
    cmem ram : `TYPE[4]
    reg T_53 : UInt<2>, hostClock with : (reset => (hostReset, UInt<2>("h00")))
    reg T_55 : UInt<2>, hostClock with : (reset => (hostReset, UInt<2>("h00")))
    reg maybe_full : UInt<1>, hostClock with : (reset => (hostReset, UInt<1>("h00")))
    node ptr_match = eq(T_53, T_55)
    node T_60 = eq(maybe_full, UInt<1>("h00"))
    node empty = and(ptr_match, T_60)
    node full = and(ptr_match, maybe_full)
    node maybe_flow = and(UInt<1>("h00"), empty)
    node do_flow = and(maybe_flow, io.deq.hostReady)
    node T_66 = and(io.enq.hostReady, io.enq.hostValid)
    node T_68 = eq(do_flow, UInt<1>("h00"))
    node do_enq = and(T_66, T_68)
    node T_70 = and(io.deq.hostReady, io.deq.hostValid)
    node T_72 = eq(do_flow, UInt<1>("h00"))
    node do_deq = and(T_70, T_72)
    when do_enq :
      infer mport T_74 = ram[T_53], hostClock
      T_74 <- io.enq.hostBits
      node T_79 = eq(T_53, UInt<2>("h03"))
      node T_81 = and(UInt<1>("h00"), T_79)
      node T_84 = add(T_53, UInt<1>("h01"))
      node T_85 = tail(T_84, 1)
      node T_86 = mux(T_81, UInt<1>("h00"), T_85)
      T_53 <= T_86
      skip
    when do_deq :
      node T_88 = eq(T_55, UInt<2>("h03"))
      node T_90 = and(UInt<1>("h00"), T_88)
      node T_93 = add(T_55, UInt<1>("h01"))
      node T_94 = tail(T_93, 1)
      node T_95 = mux(T_90, UInt<1>("h00"), T_94)
      T_55 <= T_95
      skip
    node T_96 = neq(do_enq, do_deq)
    when T_96 :
      maybe_full <= do_enq
      skip
    node T_98 = eq(empty, UInt<1>("h00"))
    node T_100 = and(UInt<1>("h00"), io.enq.hostValid)
    node T_101 = or(T_98, T_100)
    io.deq.hostValid <= T_101
    node T_103 = eq(full, UInt<1>("h00"))
    node T_105 = and(UInt<1>("h00"), io.deq.hostReady)
    node T_106 = or(T_103, T_105)
    io.enq.hostReady <= T_106
    infer mport T_107 = ram[T_55], hostClock
    node T_111 = mux(maybe_flow, io.enq.hostBits, T_107)
    io.deq.hostBits <- T_111
    node T_115 = sub(T_53, T_55)
    node ptr_diff = tail(T_115, 1)
    node T_117 = and(maybe_full, ptr_match)
    node T_118 = cat(T_117, ptr_diff)
    io.count <= T_118
    """ +
    (if (init)
    """
    reg init : UInt<1>, hostClock with : (reset => (hostReset, UInt<1>("h01")))
    when init :
      infer mport T_121 = ram[T_53], hostClock
      T_121 <- io.enq.hostBits
      node T_126 = eq(T_53, UInt<2>("h03"))
      node T_128 = and(UInt<1>("h00"), T_126)
      node T_131 = add(T_53, UInt<1>("h01"))
      node T_132 = tail(T_131, 1)
      node T_133 = mux(T_128, UInt<1>("h00"), T_132)
      T_53 <= T_133
      init <= UInt<1>("h00")
      skip
    """
    else "")

    // Generate initial values
    val signals = enumerateMembers(tpe) map ( Seq("io", "deq", "hostBits") ++ _ )
    val defaultAssign = signals map { sig =>
      scopeSpaces + Connect(NoInfo, buildExp(sig), UIntValue(0, UnknownWidth())).serialize
    }

    val concreteQueue = templatedQueue.replaceAllLiterally("`NAME", name).
                                       replaceAllLiterally("`TYPE", tpe.serialize).
                                       replaceAllLiterally(scopeSpaces+"`DEFAULT_ASSIGN", defaultAssign.mkString("\n"))

    val ast = firrtl.Parser.parse(concreteQueue.split("\n"))
    ast.modules.head
  }

  // Useful for recreating above FIRRTL queue
  def genSimQueueFirrtl = {
    val c = Chisel.Driver.emit(() => new GenSimQueue)
    println(c)
  }
}
