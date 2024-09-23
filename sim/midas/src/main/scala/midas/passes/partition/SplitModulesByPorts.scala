package midas.passes.partition

import scala.Console.println
import scala.collection.mutable
import scala.collection.immutable.ListMap
import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.transforms.{Flatten, FlattenAnnotation}
import java.io.File
import java.io.FileWriter

object Logger {
  val file        = new File("midas/test-outputs/stmt-graph.log")
  val graphWriter = new java.io.FileWriter(file)

  val debugFile   = new File("midas/test-outputs/debug.log")
  val debugWriter = new java.io.FileWriter(debugFile)

  val stmtFile   = new File("midas/test-outputs/grouped-stmts.log")
  val stmtWriter = new java.io.FileWriter(stmtFile)

  val replaceFile   = new File("midas/test-outputs/replace-debug.log")
  val replaceWriter = new java.io.FileWriter(replaceFile)

  def logPortToGroupIdx(p2i: Map[Port, Int]): Unit = {
    p2i.foreach { case (p, i) => Logger.logStmt(s"${p.name} -> ${i}") }
  }

  def logGroupedRefsAndStmts(
    name: String,
    rs:   Map[String, (Set[String], Set[Statement])],
  ): Unit = {
    Logger.logStmt(s"${name} Refs")
    rs.foreach { case (op, (_, stmts)) =>
      Logger.logStmt(s"--- ${op}")
      stmts.foreach(stmt => Logger.logStmt(s" |- ${stmt}"))
    }
    rs.foreach { case (op, (refs, _)) =>
      Logger.logStmt(s"--- ${op}")
      refs.foreach(ref => Logger.logStmt(s" |- ${ref}"))
    }
  }

  def writeLine(s: String, writer: FileWriter): Unit = {
    writer.write(s)
    writer.write("\n")
  }

  def logGraph(s: String): Unit = {
    writeLine(s, graphWriter)
  }

  def logStmt(s: String): Unit = {
    writeLine(s, stmtWriter)
  }

  def logReplace(s: String): Unit = {
    writeLine(s, replaceWriter)
    Logger.flush()
  }

  def critical(s: String): Unit = {
    println(s)
    writeLine(s, debugWriter)
    Logger.flush()
  }

  def flush(): Unit = {
    graphWriter.flush()
    debugWriter.flush()
    stmtWriter.flush()
    replaceWriter.flush()
  }

  def close(): Unit = {
    graphWriter.close()
    debugWriter.close()
    stmtWriter.close()
    replaceWriter.close()
  }
}

object SplitModulesByPortsHelperFuncs {
// def CoherenceManagerWrapper: String = "SimpleModule"
  def CoherenceManagerWrapper: String = "CoherenceManagerWrapper"
// def CoherenceManagerWrapper: String = "TLCacheCork"
}

case class SplitModulesInfo(
  mods:      Seq[Module],
  portToMod: Map[String, Module],
  clockPort: Option[Port],
  resetPort: Option[Port],
)

case class SplitSubModulesInfo(
  newMod:  Module,
  submods: Seq[Module],
)

class FlattenModules extends Transform with DependencyAPIMigration {
  import SplitModulesByPortsHelperFuncs._

  private def flattenModulesToSplit(state: CircuitState): CircuitState = {
// val modNames = state.circuit.modules.collect ({
// case m: Module if (m.name == CoherenceManagerWrapper) => m.name
// })
// val flattenAnno = modNames.map { n =>
// FlattenAnnotation(ModuleName(n, CircuitName(state.circuit.main)))
// }
    val flattenAnno     = FlattenAnnotation(ModuleName(CoherenceManagerWrapper, CircuitName(state.circuit.main)))
    val flattener       = new Flatten
    val resolver        = new ResolveAndCheck
    val annos           = state.annotations :+ flattenAnno
    val preFlattenState = state.copy(annotations = annos)
    val flattenedState  = flattener.runTransform(preFlattenState)
    resolver.runTransform(flattenedState)
  }

  def execute(state: CircuitState): CircuitState = {
    flattenModulesToSplit(state)
  }
}

class SplitModulesByPortsStandalone extends Transform with DependencyAPIMigration {
  import SplitModulesByPortsHelperFuncs._

  def getClockResetSfx(name: String): String = {
    val len = name.length
    if (len < 5) name
    else name.substring(len - 5, len)
  }

  private def allBaseRefsList(exprs: Seq[Expression]): Seq[String] = {
    val refs = mutable.ArrayBuffer[String]()
    exprs.foreach { expr =>
      val brefs = allBaseRefs(expr)
      refs ++= brefs
    }
    refs.toSeq
  }

  private def allBaseRefs(expr: Expression): Seq[String] = {
    val brefs = expr match {
      case SubField(e, _, _, _)     =>
        allBaseRefsList(Seq(e))
      case SubIndex(e, _, _, _)     =>
        allBaseRefsList(Seq(e))
      case SubAccess(e, i, _, _)    =>
        allBaseRefsList(Seq(e, i))
      case Mux(cond, tval, fval, _) =>
        allBaseRefsList(Seq(cond, tval, fval))
      case ValidIf(cond, value, _)  =>
        allBaseRefsList(Seq(cond, value))
      case DoPrim(_, args, _, _)    =>
        allBaseRefsList(args)
      case e                        =>
        val br = baseRef(e)
        if (br._2.isDefined) Seq(br._2.get) else Seq()
    }
    brefs
  }

  private def baseRef(expr: Expression): (Expression, Option[String]) = {
    expr match {
      case Reference(name, _, _, _)   => (expr, Some(name))
      case SubField(subexpr, _, _, _) => baseRef(subexpr)
      case _                          =>
        (expr, None)
    }
  }

  // Ignore clock and reset
  private def getRefsInStmt(stmt: Statement): Seq[String] = {
    val refs = mutable.ArrayBuffer[String]()
    stmt match {
      case DefWire(_, name, _)                        =>
        refs.append(name)
      case DefRegister(_, name, _, _, _, _)           =>
        refs.append(name)
      case DefMemory(_, name, _, _, _, _, _, _, _, _) =>
        refs.append(name)
      case DefNode(_, name, value)                    =>
        refs.append(name)
        refs ++= allBaseRefs(value)
      case Conditionally(_, pred, conseq, alt)        =>
        refs ++= allBaseRefs(pred)
        refs ++= getRefsInStmt(conseq)
        refs ++= getRefsInStmt(alt)
      case PartialConnect(_, loc, expr)               =>
        refs ++= allBaseRefsList(Seq(loc, expr))
      case Connect(_, loc, expr)                      =>
        refs ++= allBaseRefsList(Seq(loc, expr))
      case IsInvalid(_, expr)                         =>
        refs ++= allBaseRefsList(Seq(expr))
      case Attach(_, exprs)                           =>
        refs ++= allBaseRefsList(exprs)
      case Stop(_, _, _, _)                           =>
        ()
      case _                                          =>
        ()
    }
    refs.toSeq
  }

  private def stmtGraph(
    body:  Statement,
    ports: Seq[Port],
  ): (
    mutable.Map[String, mutable.Set[Statement]],
    mutable.Map[String, mutable.Set[String]],
  ) = {
    // Statements that defines this reference, or has this reference on the lhs
    val childStmts = mutable.Map[String, mutable.Set[Statement]]()

    // References that drives the current ref
    val childRefs = mutable.Map[String, mutable.Set[String]]()

    ports.foreach(p => childStmts(p.name) = mutable.Set[Statement]())
    ports.foreach(p => childRefs(p.name) = mutable.Set[String]())

    def initChildMap(name: String, stmt: Statement): Unit = {
      childStmts(name) = mutable.Set[Statement]()
      childStmts(name).add(stmt)
      childRefs(name)  = mutable.Set[String]()
    }

    def onStmt(stmt: Statement): Unit = {
      stmt match {
        case s @ DefInstance(_, name, _, _)                 =>
          initChildMap(name, s)
        case s @ DefWire(_, name, _)                        =>
          initChildMap(name, s)
        case s @ DefRegister(_, name, _, clk, rst, _)       =>
          initChildMap(name, s)
          childRefs(name) ++= allBaseRefs(clk)
          childRefs(name) ++= allBaseRefs(rst)
        case s @ DefMemory(_, name, _, _, _, _, _, _, _, _) =>
          initChildMap(name, s)
        case s @ DefNode(_, name, expr)                     =>
          initChildMap(name, s)
          val brefs = allBaseRefs(expr)
          childRefs(name) ++= brefs
        case s @ Connect(_, lexpr, rexpr)                   =>
          val lBaseRef = baseRef(lexpr)._2
          assert(lBaseRef.isDefined, "left hand side of a connection should always have a base ref")
          assert(childStmts.contains(lBaseRef.get) && childRefs.contains(lBaseRef.get))
          childStmts(lBaseRef.get).add(s)

          val rBaseRefs = allBaseRefs(rexpr)
          childRefs(lBaseRef.get) ++= rBaseRefs
        case s @ IsInvalid(_, expr)                         =>
          val ref = baseRef(expr)._2
          assert(ref.isDefined, s"BaseRef of IsInvalid is not defined yet ${s}")
          childStmts(ref.get).add(s)
        case Verification(_, _, _, _, _, _)                 =>
          ()
        case Stop(_, _, _, _)                               =>
          ()
        case Print(_)                                       =>
          ()
        case Block(s)                                       =>
          s.foreach(onStmt(_))
      }
    }
    body.foreachStmt(onStmt(_))

    Logger.logGraph("Child stmts")
    childStmts.foreach { case (ref, stmts) =>
      Logger.logGraph(s"=== ${ref}")
      stmts.toSeq.foreach { stmt => Logger.logGraph(s"|- ${stmt}") }
    }
    Logger.logGraph("Child Refs")
    childRefs.foreach { case (ref, refs) =>
      Logger.logGraph(s"=== ${ref}")
      refs.toSeq.foreach { ref => Logger.logGraph(s"|- ${ref}") }
    }

    (childStmts, childRefs)
  }

  private def groupRefsAndStmtsForBaseRef(
    childStmts: mutable.Map[String, mutable.Set[Statement]],
    childRefs:  mutable.Map[String, mutable.Set[String]],
    root:       String,
  ): (Set[String], Set[Statement]) = {
    val stmts = mutable.Set[Statement]()
    val refs  = mutable.Set[String]()

    val que = mutable.ArrayBuffer[String]()
    val vis = mutable.Set[String]()
    que.append(root)
    vis.add(root)

    Logger.critical(s"groupedRefsAndStmtsForBaseRef root: ${root}")

    while (que.size != 0) {
      val front = que.remove(0)
      stmts ++= childStmts(front)
      refs ++= childRefs(front)

      assert(childRefs.contains(front))
      val edges = childRefs(front).toSeq

      edges.foreach { adj =>
// Logger.critical(s"adj ${adj}")
        if (!vis.contains(adj)) {
          que.append(adj)
          vis.add(adj)
        }
      }
    }

    (refs.toSet, stmts.toSet)
  }

  private def groupRefsAndStmts(
    childStmts: mutable.Map[String, mutable.Set[Statement]],
    childRefs:  mutable.Map[String, mutable.Set[String]],
    baseRefs:   Seq[String],
  ): Map[String, (Set[String], Set[Statement])] = {
    val groupedRefsAndStmts = baseRefs.map { br =>
      br -> groupRefsAndStmtsForBaseRef(childStmts, childRefs, br)
    }.toMap
    groupedRefsAndStmts
  }

  private def getStmtIdx(m: Module): Map[Statement, Int] = {
    val stmtIdx = mutable.Map[Statement, Int]()
    m.body.foreachStmt { stmt =>
      val idx = stmtIdx.size
      stmtIdx(stmt) = idx
    }
    stmtIdx.toMap
  }

  private def getStmtUnion(
    refToStmtGroup: Map[String, Set[Statement]],
    iports:         Set[String],
  ): (
    mutable.Map[String, Int],
    mutable.Map[Int, mutable.Set[Statement]],
  ) = {

    def iportsInStmtGroup(
      stmtGroup: Set[Statement],
      iports:    Set[String],
    ): Set[String] = {
      iports.filter { ip =>
        checkRefInStmtGroup(ip, stmtGroup)
      }
    }

    val refToIpMap = refToStmtGroup.map { case (r, sg) =>
      r -> iportsInStmtGroup(sg, iports)
    }.toMap

    val keys        = refToStmtGroup.keys.toSeq
    Logger.critical(s"getStmtUnion keys.size: ${keys.size}")
    val keyUnionMap = mutable.Map[Int, Int]()                    // keyidx -> unionidx
    val stmtUnions  = mutable.Map[Int, mutable.Set[Statement]]() // unionidx -> refs
    val ipUnions    = mutable.Map[Int, mutable.Set[String]]()
    for (i <- 0 until keys.size) {
      val ik = keys(i)
      val ig = refToStmtGroup(ik)

      if (!keyUnionMap.contains(i)) {
        keyUnionMap(i) = stmtUnions.size
      }

      val uidx = keyUnionMap(i)
      if (!stmtUnions.contains(uidx)) {
        stmtUnions(uidx) = mutable.Set[Statement]()
        stmtUnions(uidx) ++= ig.toSeq

        ipUnions(uidx) = mutable.Set[String]()
        ipUnions(uidx) ++= refToIpMap(ik).toSeq
      }

      for (j <- (i + 1) until keys.size) {
        val jk  = keys(j)
        val jg  = refToStmtGroup(jk)
        val jpi = refToIpMap(jk)
        for (k <- 0 until stmtUnions.size) {
          val curRefs    = stmtUnions(k)
          val curPortsIn = ipUnions(k)
          val stmtInter  = jg.intersect(curRefs)
          val iportInter = jpi.intersect(curPortsIn)
          if (stmtInter.size > 0 || iportInter.size > 0) {
            keyUnionMap(j) = k
            stmtUnions(k) ++= jg
            ipUnions(k) ++= jpi
          }
        }
        // hasn't found any union, new group
        if (!keyUnionMap.contains(j)) {
          val j_uidx = stmtUnions.size
          keyUnionMap(j)     = j_uidx
          stmtUnions(j_uidx) = mutable.Set[Statement]()
          stmtUnions(j_uidx) ++= jg.toSeq
          ipUnions(j_uidx)   = mutable.Set[String]()
          ipUnions(j_uidx) ++= jpi
        }
      }
    }

    keyUnionMap.foreach { case (k, v) => Logger.logStmt(s"${k} -> ${v}") }
    stmtUnions.foreach { case (k, v) =>
      Logger.logStmt(s"${k}")
      v.foreach { s => Logger.logStmt(s"${s}") }
    }
    val refToUnionIdx = keyUnionMap.map { case (k, v) =>
      keys(k) -> v
    }

    // refToUnionIdx : ref -> unionIdx
    // stmtUnions : unionIdx -> Stmts that have sth in common
    (refToUnionIdx, stmtUnions)
  }

  private def checkForMissingStmts(
    sortedStmts: Seq[Map[Int, Statement]],
    stmtToIdx:   Map[Statement, Int],
  ): Unit = {
    val processedIdx = mutable.Set[Int]()
    sortedStmts.foreach { stmts =>
      Logger.logStmt("======= Grouped Statements =======")
      stmts.foreach { case (l, s) =>
        Logger.logStmt(s"${l} ${s}")
        processedIdx.add(l)
      }
    }

    val missingStmtsCnt = stmtToIdx.size - processedIdx.size
    Logger.logStmt(s"=== Missing statements: ${missingStmtsCnt} ====")
    stmtToIdx.foreach { case (stmt, idx) =>
      if (!processedIdx.contains(idx)) {
        Logger.logStmt(s"[${idx}] ${stmt}")
      }
    }
  }

  private def checkRefInStmtGroup(
    ref:   String,
    stmts: Set[Statement],
  ): Boolean = {
    val foundRef = stmts
      .map { s =>
        val refs = getRefsInStmt(s).toSet
        refs.contains(ref)
      }
      .reduce(_ || _)
    foundRef
  }

  private def checkRefInStmtGroup(
    ref:   String,
    stmts: Map[Int, Statement],
  ): Boolean = {
    val foundRef = stmts
      .map { case (_, stmt) =>
        val refs = getRefsInStmt(stmt).toSet
        refs.contains(ref)
      }
      .reduce(_ || _)

    foundRef
  }

  private def getStmtGrpIdxForRef(
    ref:         String,
    sortedStmts: Seq[Map[Int, Statement]],
  ): Int = {
    val gidx = sortedStmts.zipWithIndex.flatMap { case (stmts, idx) =>
      if (checkRefInStmtGroup(ref, stmts)) Some(idx) else None
    }
    assert(gidx.size == 1, s"Ref: ${ref} in multiple stmt groups\n ${gidx}")
    gidx.head
  }

  private def trySplitModuleByPorts(m: Module): SplitModulesInfo = {
    Logger.critical(s"Split Modules By Port ${m.name}")

    // Obtain the statement -> line# mapping
    val stmtToIdx = getStmtIdx(m)

    val ports = m.ports

    val oports    = ports.filter(_.direction == firrtl.ir.Output)
    val oPortRefs = ports.filter(p => p.direction == firrtl.ir.Output).map(_.name)

    val iports          = ports.filter(_.direction == firrtl.ir.Input)
    val noclkrst_iports = iports.filter(p => !p.name.contains("clock") && !p.name.contains("reset"))

    // Obtain the statement graph
    val graph = stmtGraph(m.body, ports)
    Logger.critical("Statement Graph Generated")

    // First group the statements according to the output ports
    val oPortRefsAndStmts = groupRefsAndStmts(graph._1, graph._2, oPortRefs)
    Logger.critical("groupRefsAndStmts for outports done")
    Logger.logGroupedRefsAndStmts(s"OutPort ${m.name}", oPortRefsAndStmts)

    // Get union of the statements and group them if they have some
    // common reference to a FIRRTL node
    val refToStmtGroup = oPortRefsAndStmts.map { case (k, v) =>
      k -> v._2
    }.toMap
    val unionInfo      = getStmtUnion(refToStmtGroup, noclkrst_iports.map(_.name).toSet)
    val unionStmts     = unionInfo._2
    val refToUnionIdx  = unionInfo._1
    Logger.critical("Statement Union Done")

    // Sort the statements as their original order
    val sortedStmts = unionStmts.map { case (_, stmts) =>
      val idxToStmt = stmts.map { stmt =>
        stmtToIdx(stmt) -> stmt
      }.toMap
      val sorted    = ListMap(idxToStmt.toSeq.sortBy(_._1): _*)
      sorted
    }.toSeq

    // Check if there are any missing statements
    checkForMissingStmts(sortedStmts, stmtToIdx)

    val oportNames      = oports.map(_.name).toSet
    val oportNameToPort = oports.map { op =>
      op.name -> op
    }.toMap
    val opToUIdx        = refToUnionIdx
      .filter { case (k, _) =>
        oportNames.contains(k)
      }
      .map { case (k, v) =>
        oportNameToPort(k) -> v
      }
      .toMap

    val ipToUIdx = noclkrst_iports.map { p =>
      val gidx = getStmtGrpIdxForRef(p.name, sortedStmts)
      p -> gidx
    }.toMap

    Logger.logPortToGroupIdx(opToUIdx)
    Logger.logPortToGroupIdx(ipToUIdx)

    def headOrNone[T](x: Seq[T]): Option[T] = {
      if (x.size > 0) Some(x.head) else None
    }

    val clockPort = headOrNone(iports.filter(p => getClockResetSfx(p.name) == "clock"))
    val resetPort = headOrNone(iports.filter(p => getClockResetSfx(p.name) == "reset"))

    val portToMod     = mutable.Map[String, Module]()
    val modules       = sortedStmts.zipWithIndex.map { case (lineStmts, idx) =>
      val stmts  = lineStmts.map(_._2)
      val ip     = ipToUIdx.filter { case (_, gidx) => gidx == idx }.map(_._1).toSeq
      val op     = opToUIdx.filter { case (_, gidx) => gidx == idx }.map(_._1).toSeq
      val crpOpt = Seq(clockPort, resetPort)
      val crp    = crpOpt.flatten

      val ports = ip ++ op ++ crp
      val body  = Block(stmts.toSeq)
      val mod   = Module(NoInfo, m.name + s"_SPLIT_${idx}", ports, body)

      ip.foreach(p => portToMod(p.name) = mod)
      op.foreach(p => portToMod(p.name) = mod)

      mod
    }
    val newModuleList = modules
    SplitModulesInfo(newModuleList, portToMod.toMap, clockPort, resetPort)
  }

  private def hasSubModule(m: Module): Boolean = {
    val has = mutable.ArrayBuffer[Boolean]()
    m.body.foreachStmt(stmt =>
      stmt match {
        case DefInstance(_, _, _, _) => has.append(true)
        case _                       => has.append(false)
      }
    )
    has.reduce(_ || _)
  }

  private def flattenAndRetrySplitModuleByPorts(
    m:     Module,
    state: CircuitState,
  ): SplitModulesInfo = {
    val hasSubMod = hasSubModule(m)
    val si        = trySplitModuleByPorts(m)
    if (!hasSubMod || si.mods.size > 1) {
      si
    } else {
      Logger.critical(s"Flatten ${m.name}")
      val fanno   = FlattenAnnotation(ModuleName(m.name, CircuitName(state.circuit.main)))
      val fpass   = new Flatten
      val annos   = state.annotations :+ fanno
      val fstate  = fpass.runTransform(state.copy(annotations = annos))
      val fmod    = fstate.circuit.modules
        .collectFirst(_ match {
          case x @ Module(_, n, _, _) if (n == m.name) => x
        })
        .get
      val newBody = mutable.ArrayBuffer[Statement]()
      fmod.body.foreachStmt(stmt =>
        stmt match {
          case Block(stmts) => newBody ++= stmts
          case s            => newBody.append(s)
        }
      )
      trySplitModuleByPorts(m.copy(body = Block(newBody.toSeq)))
    }
  }

  private def pickSubModulesToSplit(m: Module): Seq[String] = {
    val mods = mutable.ArrayBuffer[String]()
    m.body.foreachStmt(stmt =>
      stmt match {
        case DefInstance(_, _, m, _) =>
          mods.append(m)
        case _                       => ()
      }
    )
    mods.toSeq
  }

  private def checkClkRstFwd(m: Module): Boolean = {
    val iports           = m.ports.filter(_.direction == firrtl.ir.Input)
    val oports           = m.ports.filter(_.direction == firrtl.ir.Output)
    val nonclkrst_iports = iports.filter { p =>
      val sfx = getClockResetSfx(p.name)
      sfx != "clock" && sfx != "reset"
    }
    val nonclkrst_oports = oports.filter { p =>
      val sfx = getClockResetSfx(p.name)
      sfx != "clock" && sfx != "reset"
    }
    (nonclkrst_iports.size == 0) && (nonclkrst_oports.size == 0)
  }

  private def coerceSubModuleClkRstToTop(
    m:   Module,
    clk: String,
    rst: String,
  ): Module = {
    val newbdy = mutable.ArrayBuffer[Statement]()
    m.body.foreachStmt(stmt =>
      stmt match {
        case s @ Connect(_, WSubField(WRef(li), lref, _, _), _) =>
          val sfx = getClockResetSfx(lref)
          if (sfx == "clock") {
            val nc = Connect(NoInfo, WSubField(WRef(li._1), lref), WRef(clk))
            newbdy.append(nc)
          } else if (sfx == "reset") {
            val nc = Connect(NoInfo, WSubField(WRef(li._1), lref), WRef(rst))
            newbdy.append(nc)
          } else {
            newbdy.append(s)
          }
        case s                                                  => newbdy.append(s)
      }
    )
    m.copy(body = Block(newbdy.toSeq))
  }

  private def replaceBodyWithSplitMods(
    m:     Module,
    sinfo: Map[String, SplitModulesInfo],
  ): Module = {

    def logSplitModulesInfo(s: SplitModulesInfo): Unit = {
      Logger.logReplace("SplitModulesInfo")
      Logger.logReplace(s"mods: ${s.mods}")
      Logger.logReplace(s"portToMod: ${s.portToMod}")
    }

    def getSplitInst(
      name:           String,
      ref:            String,
      sinfo:          Map[String, SplitModulesInfo],
      orig_inst2mod:  mutable.Map[String, String],
      split_mod2inst: mutable.Map[String, String],
    ): String = {
      val mname = orig_inst2mod(name)
      val si    = sinfo(mname)
      if (!si.portToMod.contains(ref)) {
        logSplitModulesInfo(si)
      }
      val mod   = si.portToMod(ref)
      val inst  = split_mod2inst(mod.name)
      Logger.critical(s"getSplitInst inst: ${name} split_inst ${inst}")
      inst
    }

    Logger.critical("replaceBodyWithSplitMods")

    val newbdy         = mutable.ArrayBuffer[Statement]()
    val orig_inst2mod  = mutable.Map[String, String]()
    val split_mod2inst = mutable.Map[String, String]()
    m.body.foreachStmt(stmt =>
      stmt match {
        case s @ DefInstance(_, n, m, t)                                                      =>
          if (sinfo.contains(m)) {
            sinfo(m).mods.zipWithIndex.foreach { case (mod, i) =>
              val iname = n + s"_${i}"
              val di    = DefInstance(NoInfo, iname, mod.name, t)
              newbdy.append(di)
              orig_inst2mod(n)         = m
              split_mod2inst(mod.name) = iname
            }
          } else {
            newbdy.append(s)
            orig_inst2mod(n) = m
          }
        case s @ Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), rref, _, _)) =>
          val lmod = orig_inst2mod(li._1)
          val rmod = orig_inst2mod(ri._1)
          if (sinfo.contains(lmod) && sinfo.contains(rmod)) {
            val linst = getSplitInst(li._1, lref, sinfo, orig_inst2mod, split_mod2inst)
            val rinst = getSplitInst(ri._1, rref, sinfo, orig_inst2mod, split_mod2inst)
            val con   = Connect(NoInfo, WSubField(WRef(linst), lref), WSubField(WRef(rinst), rref))
            newbdy.append(con)
          } else if (sinfo.contains(lmod)) {
            val linst = getSplitInst(li._1, lref, sinfo, orig_inst2mod, split_mod2inst)
            val con   = Connect(NoInfo, WSubField(WRef(linst), lref), WSubField(WRef(ri._1), rref))
            newbdy.append(con)
          } else if (sinfo.contains(rmod)) {
            val rinst = getSplitInst(ri._1, rref, sinfo, orig_inst2mod, split_mod2inst)
            val con   = Connect(NoInfo, WSubField(WRef(li._1), lref), WSubField(WRef(rinst), rref))
            newbdy.append(con)
          } else {
            newbdy.append(s)
          }
        case s @ Connect(_, WSubField(WRef(li), lref, _, _), rxpr)                            =>
          val lmod = orig_inst2mod(li._1)
          if (sinfo.contains(lmod)) {
            val si    = sinfo(lmod)
            val isClk = si.clockPort.isDefined && (lref == si.clockPort.get.name)
            val isRst = si.resetPort.isDefined && (lref == si.resetPort.get.name)
            if (isClk || isRst) {
              val insts = si.mods.map(m => split_mod2inst(m.name))
              insts.foreach { inst =>
                val con = Connect(NoInfo, WSubField(WRef(inst), lref), rxpr)
                newbdy.append(con)
              }
            } else {
              val linst = getSplitInst(li._1, lref, sinfo, orig_inst2mod, split_mod2inst)
              val con   = Connect(NoInfo, WSubField(WRef(linst), lref), rxpr)
              newbdy.append(con)
            }
          } else {
            newbdy.append(s)
          }
        case s @ Connect(_, lxpr, WSubField(WRef(ri), rref, _, _))                            =>
          val rmod = orig_inst2mod(ri._1)
          if (sinfo.contains(rmod)) {
            val rinst = getSplitInst(ri._1, rref, sinfo, orig_inst2mod, split_mod2inst)
            val con   = Connect(NoInfo, lxpr, WSubField(WRef(rinst), rref))
            newbdy.append(con)
          } else {
            newbdy.append(s)
          }
        case s                                                                                =>
          newbdy.append(s)
      }
    )
    m.copy(body = Block(newbdy.toSeq))
  }

  // TODO : Currently, we select instances within the current module,
  // split those instances, and perform the splitting in the current module.
  // However, we can generalize this so that it can perform recursive splitting.
  private def splitSubModulesByPorts(
    m:     Module,
    state: CircuitState,
  ): SplitSubModulesInfo = {
    val modNames = pickSubModulesToSplit(m).toSet
    val mods     = state.circuit.modules.collect({
      case m: Module if (modNames.contains(m.name)) => m
    })

    def checkPortType(p: Port, name: String): Boolean = {
      val sfx = getClockResetSfx(p.name)
      (p.direction == firrtl.ir.Input) && (sfx == name)
    }

    val clkPort = m.ports.filter(checkPortType(_, "clock")).head.name
    val rstPort = m.ports.filter(checkPortType(_, "reset")).head.name

    val clkrst_m      = coerceSubModuleClkRstToTop(m, clkPort, rstPort)
    val modsToSplit   = mods.filter(!checkClkRstFwd(_))
    val splitModsInfo = modsToSplit.map { m =>
      m.name -> flattenAndRetrySplitModuleByPorts(m, state)
    }.toMap
    val newMod        = replaceBodyWithSplitMods(clkrst_m, splitModsInfo)

    SplitSubModulesInfo(newMod, splitModsInfo.map(_._2.mods).flatten.toSeq)
  }

  private def splitModuleByPorts(m: Module, state: CircuitState): Seq[Module] = {
    val splitSubInfo = splitSubModulesByPorts(m, state)
    val splitInfo    = trySplitModuleByPorts(splitSubInfo.newMod)
    splitInfo.mods ++ splitSubInfo.submods ++ Seq(m)
  }

  def execute(state: CircuitState): CircuitState = {
    val transformedModules = state.circuit.modules.flatMap {
      case m: Module if (m.name == CoherenceManagerWrapper) =>
        splitModuleByPorts(m, state)
      case m                                                => Seq(m)
    }

    Logger.close()

    val transformedCircuit = state.circuit.copy(modules = transformedModules)
    state.copy(circuit = transformedCircuit)
  }
}
