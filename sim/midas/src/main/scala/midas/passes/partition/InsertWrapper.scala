package midas.passes.partition

import Array.range
import scala.Console.println
import scala.collection.mutable
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.ir.EmptyStmt
import firrtl.analyses.InstanceKeyGraph
import firrtl.annotations.TargetToken._
import midas.targetutils._

trait InsertWrapperPass {
  // Generate the wrapper module & forward the IOs
  def genWrapper(wrapperModuleName: String)(m: Module): Module = {
    val ports           = m.ports
    val inst            = DefInstance(m.name, m.name)
    val portConnections = m.ports.map { case Port(_, name, dir, _) =>
      val connection = dir match {
        case Input  => Connect(NoInfo, WSubField(WRef(inst), name), WRef(name))
        case Output => Connect(NoInfo, WRef(name), WSubField(WRef(inst), name))
      }
      connection
    }
    new Module(NoInfo, wrapperModuleName, ports, Block(Seq(inst) ++ portConnections))
  }

  // Find the instance of the module to remove, replace it by instantiating the wrapper module
  def replaceModuleWithWrapper(removeModuleName: String, wrapperModuleName: String)(m: DefModule): DefModule = {
    def replaceModuleInstance(stmt: Statement): Statement = {
      stmt match {
        case DefInstance(_, iname, mname, _) if (mname == removeModuleName) =>
          DefInstance(NoInfo, iname, wrapperModuleName)
        case s: DefInstance                                                 => s
        case s: DefWire                                                     => s
        case s: DefRegister                                                 => s
        case s: DefMemory                                                   => s
        case s: DefNode                                                     => s
        case s: Conditionally                                               => s
        case s: PartialConnect                                              => s
        case s: Connect                                                     => s
        case s: IsInvalid                                                   => s
        case s: Attach                                                      => s
        case s: Stop                                                        => s
        case s: Print                                                       => s
        case s: Verification                                                => s
        case s                                                              => replaceModuleInstance(s)
      }
    }

    m match {
      case m: Module =>
        val body = m.body.map(replaceModuleInstance)
        m.copy(body = body)
      case m         => m
    }
  }

  def wrapModule(state: CircuitState, removeModuleName: String, wrapperModuleName: String): CircuitState = {
    val replacedModuleWithWrapperCircuit =
      state.circuit.map(replaceModuleWithWrapper(removeModuleName, wrapperModuleName))
    val moduleList                       = replacedModuleWithWrapperCircuit.modules.flatMap({ m =>
      m match {
        case m: Module if (m.name == removeModuleName) =>
          Seq(m, genWrapper(wrapperModuleName)(m))
        case m                                         => Seq(m)
      }
    })

    // Update annotations targeting "removeModuleName"
    val updateModuleToWrapperAnnos = state.annotations.map(_ match {
      case RoCCBusyFirrtlAnnotation(b, r, v) if (b.module == removeModuleName)                =>
        val nb      = b.copy(module = wrapperModuleName)
        val nr      = r.copy(module = wrapperModuleName)
        val nv      = v.copy(module = wrapperModuleName)
        val newAnno = RoCCBusyFirrtlAnnotation(nb, nr, nv)
        newAnno
      case FirrtlEnableModelMultiThreadingAnnotation(it) if (it.ofModule == removeModuleName) =>
        val newIt   = it.copy(ofModule = wrapperModuleName)
        val newAnno = FirrtlEnableModelMultiThreadingAnnotation(newIt)
        newAnno
      case a @ FirrtlPortToNeighborRouterIdxAnno(rt, _, _)                                    =>
        val newRt = rt.copy(component = Seq(OfModule(wrapperModuleName)))
        a.copy(target = newRt)
      case anno                                                                               => anno
    })
    val circuit                    = state.circuit.copy(modules = moduleList)
    state.copy(circuit = circuit, annotations = updateModuleToWrapperAnnos)
  }
}

// case class InstanceTarget(
// circuit:           String,
// module:            String,
// override val path: Seq[(Instance, OfModule)],
// instance:          String,
// ofModule:          String)

// object DefInstance {
// def apply(name: String, module: String): DefInstance = DefInstance(NoInfo, name, module)
// }

// NOTE : For some reason, the extracted modules does not get deduplicated in the
// dedup pass. Manually dedup here making the assumption that the user knows what
// he/she is doing & annotates only the modules that are multi-threadable.
trait DedupFAME5InstancesPass {
  def deduplicateInstancesOnFAME5(state: CircuitState): CircuitState = {
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) => p }).get
    if (p(midas.EnableModelMultiThreading)) {
      val instTargets     = state.annotations.collect(_ match {
        case FirrtlEnableModelMultiThreadingAnnotation(it) => it
      })
      val instModuleName  = instTargets.map { it => (it.instance, it.ofModule) }.toSet
      val dedupModuleName = instTargets.head.ofModule

      instModuleName.foreach { im => println(s"Dedup inst ${im._1} mod ${im._2} to ${dedupModuleName}") }

      val newCircuit = state.circuit.map(replaceModule(instModuleName, dedupModuleName))
      val newAnnos   = state.annotations.map(anno =>
        anno match {
          case FirrtlEnableModelMultiThreadingAnnotation(target) =>
            FirrtlEnableModelMultiThreadingAnnotation(target.copy(ofModule = dedupModuleName))
          case _                                                 => anno
        }
      )
      state.copy(circuit = newCircuit, annotations = newAnnos)
    } else {
      state
    }
  }

  def replaceModule(instTargets: Set[(String, String)], dedupModuleName: String)(mod: DefModule): DefModule = {
    mod match {
      case m: Module =>
        val newBody = m.body.map(replaceStmt(instTargets, dedupModuleName))
        m.copy(body = newBody)
      case m         => m
    }
  }

  def replaceStmt(instTargets: Set[(String, String)], dedupModuleName: String)(stmt: Statement): Statement = {
    stmt match {
      case DefInstance(_, iname, mname, _) if (instTargets.contains((iname, mname))) =>
        DefInstance(NoInfo, iname, dedupModuleName)
      case _                                                                         => stmt
    }
  }
}

trait GroupAndInsertWrapperPass {
  type InstGroup = mutable.ArrayBuffer[DefInstance]
  def wrapModulesByGroups(
    state:       CircuitState,
    moduleNames: Seq[Set[String]],
    wrapperPfx:  String,
  ): CircuitState = {

    def findAndRemoveModuleInstances(
      stmt:  Statement,
      insts: mutable.ArrayBuffer[InstGroup],
    ): Statement = {
      stmt match {
        case s @ DefInstance(_, _, mname, _) if (moduleNames.map(s => s(mname)).reduce(_ || _)) =>
          val gidx = moduleNames.zipWithIndex
            .map { case (mn, idx) =>
              if (mn(mname)) Some(idx) else None
            }
            .collectFirst(_ match {
              case Some(x) => x
            })
            .getOrElse(0)

          insts(gidx).append(s)
          EmptyStmt
        case s: DefInstance                                                                     => s
        case s: DefWire                                                                         => s
        case s: DefRegister                                                                     => s
        case s: DefMemory                                                                       => s
        case s: DefNode                                                                         => s
        case s: Conditionally                                                                   => s
        case s: PartialConnect                                                                  => s
        case s: Connect                                                                         => s
        case s: IsInvalid                                                                       => s
        case s: Attach                                                                          => s
        case s: Stop                                                                            => s
        case s: Print                                                                           => s
        case s: Verification                                                                    => s
        case s                                                                                  => findAndRemoveModuleInstances(s, insts)
      }
    }

    // Generate a wrapper form a group of modules
    def generateWrapperForGroup(
      insts:                      mutable.ArrayBuffer[DefInstance],
      instPortMap:                mutable.Map[(String, String), (String, String)],
      wrapperName:                String,
      igraph:                     InstanceKeyGraph,
      modulePortToWrapperPortMap: mutable.Map[(String, String), (String, String)],
    ): DefModule = {

      val idxs                         = range(0, insts.size)
      val wrapperPortToInstanceMap     = mutable.Map[Port, DefInstance]()
      val wrapperPortToInstancePortMap = mutable.Map[Port, Port]()

      val wrappedInsts = insts.map { inst => DefInstance(inst.name, inst.module) }
      val wrapperPorts = insts.zip(wrappedInsts).zip(idxs).flatMap { x =>
        val inst  = x._1._1
        val winst = x._1._2
        val idx   = x._2

        val mod          = igraph.moduleMap(inst.module)
        val ports        = mod.ports.filter(p => (p.name != "clock"))
        val indexedPorts = ports.map { p =>
          val pfx     = if (idx == 0 && p.name == "reset") "" else s"p${idx}_"
          val newPort = Port(NoInfo, pfx + p.name, p.direction, p.tpe)
          wrapperPortToInstancePortMap(newPort)          = p
          instPortMap((inst.name, p.name))               = (wrapperName, newPort.name)
          modulePortToWrapperPortMap((mod.name, p.name)) = (wrapperName, newPort.name)
          newPort
        }

        indexedPorts.foreach { ip =>
          wrapperPortToInstanceMap(ip) = winst
        }

        indexedPorts
      }

      val portConnections = wrapperPorts.map { wp =>
        val port       = wrapperPortToInstancePortMap(wp)
        val inst       = wrapperPortToInstanceMap(wp)
        val connection = wp.direction match {
          case Input  => Connect(NoInfo, WSubField(WRef(inst.name), port.name), WRef(wp.name))
          case Output => Connect(NoInfo, WRef(wp.name), WSubField(WRef(inst.name), port.name))
        }
        connection
      }

      val clockPort             = Port(NoInfo, "clock", Input, ClockType)
      val clockResetConnections = insts.flatMap { inst =>
        Seq(Connect(NoInfo, WSubField(WRef(inst.name), "clock"), WRef(clockPort)))
      }
      insts.foreach { i => instPortMap((i.name, "clock")) = (wrapperName, "clock") }

      val allPorts              = wrapperPorts.toSeq ++ Seq(clockPort)
      val body                  = wrappedInsts.toSeq ++ portConnections ++ clockResetConnections
      new Module(NoInfo, wrapperName, allPorts, Block(body))
    }

    def replaceConnectionToWrapper(
      instPortMap:   mutable.Map[(String, String), (String, String)],
      instsToRemove: Set[String],
    )(stmt:          Statement
    ): Statement = {
      stmt match {
        case s @ Connect(_, WSubField(WRef(linst), lname, lt, lf), WSubField(WRef(rinst), rname, rt, rf)) =>
          if (instsToRemove.contains(linst._1) && instPortMap.contains(linst._1, lname)) {
            val (wrapperInst, wrapperPort) = instPortMap((linst._1, lname))
            Connect(NoInfo, WSubField(WRef(wrapperInst), wrapperPort), WSubField(WRef(rinst._1), rname, rt, rf))
          } else if (instsToRemove.contains(rinst._1) && instPortMap.contains(rinst._1, rname)) {
            val (wrapperInst, wrapperPort) = instPortMap((rinst._1, rname))
            Connect(NoInfo, WSubField(WRef(linst._1), lname, lt, lf), WSubField(WRef(wrapperInst), wrapperPort))
          } else {
            s
          }
        case s: Connect                                                                                   => s
        case s: DefInstance                                                                               => s
        case s: DefWire                                                                                   => s
        case s: DefRegister                                                                               => s
        case s: DefMemory                                                                                 => s
        case s: DefNode                                                                                   => s
        case s: Conditionally                                                                             => s
        case s: PartialConnect                                                                            => s
        case s: IsInvalid                                                                                 => s
        case s: Attach                                                                                    => s
        case s: Stop                                                                                      => s
        case s: Print                                                                                     => s
        case s: Verification                                                                              => s
        case EmptyStmt                                                                                    => EmptyStmt
        case s: Block                                                                                     =>
          s.mapStmt { is => replaceConnectionToWrapper(instPortMap, instsToRemove)(is) }
      }
    }

    def replaceModuleGroupsByWrapper(
      modulePortToWrapperPortMap: mutable.Map[(String, String), (String, String)],
      instToGroupWrapperMap:      mutable.Map[String, String],
      igraph:                     InstanceKeyGraph,
    )(module:                     Module
    ): Seq[DefModule] = {
      val groups  = mutable.ArrayBuffer[InstGroup]()
      val ngroups = moduleNames.size
      for (i <- 0 until ngroups) {
        groups.append(new InstGroup)
      }
      val instRemovedModuleBody = module.body.mapStmt(stmt => findAndRemoveModuleInstances(stmt, groups))
      groups.foreach { group =>
        println("Grouped Modules")
        group.foreach(inst => println(s"- ${inst.name}"))
      }
      println(s"groups.size ${groups.size}")

      val groupIdx       = range(0, groups.size)
      val instPortMap    = mutable.Map[(String, String), (String, String)]()
      val wrapperModules = groups.zip(groupIdx).map { gidx =>
        val group                  = gidx._1
        val idx                    = gidx._2
        val groupWrapperModuleName = s"${wrapperPfx}_${idx}"

        group.foreach { dm => instToGroupWrapperMap(dm.name) = groupWrapperModuleName }

        println(s"Generating a wrapper (${groupWrapperModuleName})")
        println("- Grouped Modules")
        group.foreach(inst => println(s"  - ${inst.name}"))

        generateWrapperForGroup(group, instPortMap, groupWrapperModuleName, igraph, modulePortToWrapperPortMap)
      }

      val wrapperInsts = wrapperModules.map { mod =>
        DefInstance(NoInfo, mod.name, mod.name)
      }

      val instsToRemove = groups.flatten.map(_.name).toSet
      val newModuleBody = Block(
        wrapperInsts.toSeq ++
          Seq(replaceConnectionToWrapper(instPortMap, instsToRemove)(instRemovedModuleBody))
      )
      Seq(module.copy(body = newModuleBody)) ++ wrapperModules
    }

    val igraph                     = InstanceKeyGraph(state.circuit)
    val modulePortToWrapperPortMap = mutable.Map[(String, String), (String, String)]()
    val instanceToGroupWrapperMap  = mutable.Map[String, String]()

    val wrappedModules = state.circuit.modules.flatMap(_ match {
      case mod: Module if (mod.name == state.circuit.main) =>
        replaceModuleGroupsByWrapper(modulePortToWrapperPortMap, instanceToGroupWrapperMap, igraph)(mod)
      case mod                                             => Seq(mod)
    })

    val updatedAnnos = state.annotations.map(_ match {
      case roccAnno @ RoCCBusyFirrtlAnnotation(b, r, v)                                                       =>
        if (modulePortToWrapperPortMap.contains((b.module, b.ref))) {
          assert(modulePortToWrapperPortMap contains (r.module, r.ref))
          assert(modulePortToWrapperPortMap contains (v.module, v.ref))

          val (rm, rt) = modulePortToWrapperPortMap((r.module, r.ref))
          val (vm, vt) = modulePortToWrapperPortMap((v.module, v.ref))
          val (bm, bt) = modulePortToWrapperPortMap((b.module, b.ref))

          val nb      = b.copy(module = bm, ref = bt)
          val nr      = r.copy(module = rm, ref = rt)
          val nv      = v.copy(module = vm, ref = vt)
          val newAnno = RoCCBusyFirrtlAnnotation(nb, nr, nv)
          newAnno
        } else {
          roccAnno
        }
      case FirrtlEnableModelMultiThreadingAnnotation(it) if (instanceToGroupWrapperMap.contains(it.instance)) =>
        val groupWrapper = instanceToGroupWrapperMap(it.instance)
        val newIt        = it.copy(module = groupWrapper)
        FirrtlEnableModelMultiThreadingAnnotation(newIt)
      case a @ FirrtlPortToNeighborRouterIdxAnno(rt, _, _)                                                    =>
        val module: String         = rt.component.head.asInstanceOf[OfModule].value
        val (wrapper, wrapperPort) = modulePortToWrapperPortMap((module, rt.ref))
        val newRt                  = rt.copy(ref = wrapperPort, component = Seq(OfModule(wrapper)))
        a.copy(target = newRt)
      case anno                                                                                               => anno
    })

    val wrappedCircuit = state.circuit.copy(modules = wrappedModules)
    state.copy(circuit = wrappedCircuit, annotations = updatedAnnos)
  }
}
