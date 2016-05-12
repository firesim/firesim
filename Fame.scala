 
package midas

import Utils._
import firrtl._
import firrtl.passes._
import firrtl.Utils._
import firrtl.Mappers._

/** FAME-1 Transformation
 *
 * This pass takes a lowered-to-ground circuit and performs a FAME-1 (Decoupled) transformation 
 *   to the circuit
 * It does this by creating a simulation module wrapper around the circuit, if we can gate the 
 *   clock, then there need be no modification to the target RTL, if we can't, then the target
 *   RTL will have to be modified (by adding a midasFire input and use this signal to gate 
 *   register enable
 *
 * ALGORITHM
 *  1. Flatten RTL 
 *     a. Create NewTop
 *     b. Instantiate Top in NewTop
 *     c. Iteratively pull all sim tagged instances out of hierarchy to NewTop
 *        i. Move instance declaration from child module to parent module
 *        ii. Create io in child module corresponding to io of instance
 *        iii. Connect original instance io in child to new child io
 *        iv. Connect child io in parent to instance io
 *        v. Repeate until instance is in SimTop 
 *           (if black box, repeat until completely removed from design)
 *     * Post-flattening invariants
 *       - No combinational logic on NewTop
 *       - All and only instances in NewTop are sim tagged
 *       - No black boxes in design
 *  2. Simulation Transformation
 *     a. Perform Decoupled Transformation on every RTL Module
 *        i.   Add targetFire signal input
 *        ii.  Find all DefInst nodes and propogate targetFire to the instances
 *        iii. Find all registers and add when statement connecting regIn to regOut when !targetFire
 *     b. Iteratively transform each inst in Top (see example firrtl in dram_midas top dir)
 *        i.   Create wrapper class
 *        ii.  Create input and output (ready, valid) pairs for every other sim module this module connects to
 *             * Note that TopIO counts as a "sim module"
 *        iii. Create targetFire as AND of inputs.valid and outputs.ready
 *        iv.  Connect targetFire to targetFire input of target rtl inst
 *        v.   Connect target IO to wrapper IO
 *
 * TODO 
 *  - Work on circuits without any top-level outputs
 *  - Is it okay to have ready signals for input queues depend on valid signals for those queues? This is generally bad
 *  - Change sequential memory read enable to work with targetFire
 *  - Implement Flatten RTL
 *  - Refactor important strings/naming to API (eg. "topIO" needs to be a constant defined somewhere or something)
 *  - Check that circuit is in LoFIRRTL?
 *
 * NOTES 
 *   - How do we only transform the necessary modules? Should there be a MIDAS list of modules 
 *     or something?
 *     * YES, this will be done by requiring the user to instantiate modules that should be split
 *       with something like: val module = new MIDASModule(class... etc.)
 *   - We also need a way to determine the difference between the MIDAS modules and their
 *     connecting Queues, perhaps they should be MIDAS queues, which then perhaps prints
 *     out a listing of all queues so that they can be properly transformed
 *       * What do these MIDAS queues look like since we're enforcing true decoupled 
 *         interfaces?
 */
object Fame1 extends Pass {
  def name = "Fame-1 (Decoupled) Transformation"

  // Constants, common nodes, and common types used throughout
  private type PortMap = Map[String, Seq[String]]
  private val  PortMap = Map[String, Seq[String]]()
  private type ConnMap = Map[String, PortMap]
  private val  ConnMap = Map[String, PortMap]()
  private type SimQMap = Map[(String, String), Module]
  private val  SimQMap = Map[(String, String), Module]()

  private val hostReady = Field("hostReady", REVERSE, UIntType(IntWidth(1)))
  private val hostValid = Field("hostValid", DEFAULT, UIntType(IntWidth(1)))
  private val hostClock = Port(NoInfo, "clk", INPUT, ClockType())
  private val hostReset = Port(NoInfo, "reset", INPUT, UIntType(IntWidth(1)))
  private val targetFire = Port(NoInfo, "targetFire", INPUT, UIntType(IntWidth(1)))

  private def wrapName(name: String): String = s"${name}_FAME1"
  private def unwrapName(name: String): String = name.stripSuffix("_FAME1")
  private def queueName(src: String, dst: String): String = s"SimQueue_${src}_${dst}"
  private def instName(name: String): String = s"inst_${name}"
  private def unInstName(name: String): String = name.stripPrefix("inst_")

  private def genHostDecoupled(fields: Seq[Field]): BundleType = {
    BundleType(Seq(hostReady, hostValid) :+ Field("hostBits", DEFAULT, BundleType(fields)))
  }

  // ********** findPortConn **********
  // This takes lowFIRRTL top module that follows invariants described above and returns a connection Map
  //   of instanceName -> (instanctPorts -> portEndpoint)
  // It honestly feels kind of brittle given it assumes there will be no intermediate nodes or anything in 
  //  the way of direct connections between IO of module instances
  private def processConnectExp(exp: Expression): (String, String) = {
    val unsupportedExp = new Exception("Unsupported Exp for finding port connections: " + exp)
    exp match {
      case ref: Ref => ("io", ref.name)
      case sub: SubField => 
        sub.exp match {
          case ref: Ref => (ref.name, sub.name)
          case _ => throw unsupportedExp
        }
      case sub: WSubField => 
        sub.exp match {
          case ref: Ref => (ref.name, sub.name)
          case _ => throw unsupportedExp
        }
      case exp: Expression => throw unsupportedExp
    }
  }
  private def processConnect(conn: Connect): ConnMap = {
    val lhs = processConnectExp(conn.loc)
    val rhs = processConnectExp(conn.exp)
    Map(lhs._1 -> Map(lhs._2 -> Seq(rhs._1)), rhs._1 -> Map(rhs._2 -> Seq(lhs._1))).withDefaultValue(PortMap)
  }
  private def findPortConn(connMap: ConnMap, stmts: Seq[Stmt]): ConnMap = {
    if (stmts.isEmpty) connMap
    else {
      stmts.head match {
        case conn: Connect => {
          val newConnMap = processConnect(conn)
          findPortConn((connMap map { case (k,v) => 
            k -> merge(Seq(v, newConnMap(k))) { (_, v1, v2) => v1 ++ v2 }}), stmts.tail )
        }
        case _ => findPortConn(connMap, stmts.tail)
      }
    }
  }
  private def findPortConn(top: Module, insts: Seq[DefInstance]): ConnMap = top match {
    case m: InModule =>
      val initConnMap = (insts map ( _.name -> PortMap )).toMap ++ Map("io" -> PortMap)
      val topStmts = m.body match {
        case b: Begin => b.stmts
        case s: Stmt => Seq(s) // This honestly shouldn't happen but let's be safe
      }
      findPortConn(initConnMap, topStmts)
    case m => ConnMap
  }

  // Removes clocks from a portmap
  private def scrubClocks(ports: Seq[Port], portMap: PortMap): PortMap = {
    val clocks = ports filter (_.tpe == ClockType()) map (_.name)
    portMap filter { case (portName, _) => !clocks.contains(portName) }
  }

  // ********** transformRTL **********
  // Takes an RTL module and give it targetFire input, propogates targetFire to all child instances,
  //   puts targetFire on regEnable for all registers
  // TODO
  //  - Add smem support
  private def transformRTL(m: InModule): Module = {
    val memEn = collection.mutable.HashSet[String]()
    def collectMemEn(s: Stmt): Stmt = {
      s map collectMemEn match {
        case mem: DefMemory => 
          memEn ++= mem.readers map (r => s"${mem.name}.${r}.en")
          memEn ++= mem.writers map (w => s"${mem.name}.${w}.en")
          memEn ++= mem.readwriters map (rw => s"${mem.name}.${rw}.en")
          mem
        case s => s
      }
    }

    val connectMap = collection.mutable.HashMap[Expression, Expression]()
    def collectMemEnConnects(s: Stmt): Stmt = {
      s map collectMemEnConnects match {
        case s: Connect if memEn(expToString(s.loc)) => 
          connectMap(s.loc) = s.exp
          s
        case s => s
      }
    }

    def transformStmt(s: Stmt): Stmt = {
      s map transformStmt match {
        case inst: DefInstance => 
          Begin(Seq(
            inst,
            Connect(NoInfo, buildExp(Seq(inst.name, targetFire.name)), buildExp(targetFire.name))
          ))
        case reg: DefRegister => 
          Begin(Seq(
            reg,
            Conditionally(reg.info, 
              DoPrim(NOT_OP, Seq(buildExp(targetFire.name)), Seq(), UnknownType()),
              Connect(NoInfo, buildExp(reg.name), buildExp(reg.name)), 
              Empty()
            )
          ))
        case Connect(info, loc, exp) if connectMap contains loc =>
          val nodeName = s"""${expToString(loc) replace (".", "_")}_fire"""
          Begin(Seq(
            DefNode(info, nodeName, 
              DoPrim(AND_OP, Seq(exp, buildExp(targetFire.name)), Seq(), UnknownType())
            ),
            Connect(info, loc, buildExp(nodeName))
          ))
        case s => s
      }
    }

    collectMemEn(m.body)
    collectMemEnConnects(m.body)
    InModule(m.info, m.name, m.ports :+ targetFire, transformStmt(m.body)) 
  }

  // ********** genWrapperModule **********
  // Generates FAME-1 Decoupled wrappers for simulation module instances
  private def genWrapperModule(inst: DefInstance, module: Module, portMap: PortMap): Module = {

    val instIO = module_type(module) match {
      case b: BundleType => b
      case _ => throw new Exception("Modules should always have BundleType!")
    }
    val nameToField = (instIO.fields map (f => f.name -> f)).toMap

    val connections = (portMap map (_._2)).toSeq.flatten.distinct // modules this inst connects to
    // Build simPort for each connecting module
    // TODO This whole chunk really ought to be rewritten or made a function
    val connPorts = connections map { c =>
      // Get ports that connect to this particular module as fields
      val fields = (portMap filter (_._2.contains(c))).keySet.toSeq.sorted map (nameToField(_))
      val noClock = fields filter (_.tpe != ClockType()) // Remove clock
      val inputSet  = noClock filter (_.flip == REVERSE) map (f => Field(f.name, DEFAULT, f.tpe))
      val outputSet = noClock filter (_.flip == DEFAULT)
      Port(inst.info, c, OUTPUT, BundleType(
        (if (inputSet.isEmpty) Seq()
        else Seq(Field("hostIn", REVERSE, genHostDecoupled(inputSet)))
        ) ++
        (if (outputSet.isEmpty) Seq()
        else Seq(Field("hostOut", DEFAULT, genHostDecoupled(outputSet)))
        )
      ))
    }
    val ports = hostClock +: hostReset +: connPorts // Add host and host reset

    // targetFire is signal to indicate when a simulation module can execute, this is indicated by all of its inputs
    //   being valid and all of its outputs being ready
    val targetFireInputs = (connPorts map { port => 
      getFields(port) map { field => 
        field.flip match {
          case REVERSE => buildExp(Seq(port.name, field.name, hostValid.name)) 
          case DEFAULT => buildExp(Seq(port.name, field.name, hostReady.name))
        }
      }
    }).flatten

    val defTargetFire = DefNode(inst.info, targetFire.name, genPrimopReduce(AND_OP, targetFireInputs))
    val connectTargetFire = Connect(NoInfo, buildExp(Seq(inst.name, targetFire.name)), buildExp(targetFire.name))

    // Only consume tokens when the module fires
    // TODO is it bad to have the input readys depend on the input valid signals?
    val inputsReady = (connPorts map { port => 
      getFields(port) filter (_.flip == REVERSE) map { field => // filter to only take inputs
        Connect(inst.info, buildExp(Seq(port.name, field.name, hostReady.name)), buildExp(targetFire.name))
      }
    }).flatten

    // Outputs are valid on cycles where we fire
    val outputsValid = (connPorts map { port => 
      getFields(port) filter (_.flip == DEFAULT) map { field => // filter to only take outputs
        Connect(inst.info, buildExp(Seq(port.name, field.name, hostValid.name)), buildExp(targetFire.name))
      }
    }).flatten

    // Connect up all of the IO of the RTL module to sim module IO, except clock which should be connected
    // This currently assumes naming things that are also done above when generating connPorts
    val connectedInstIOFields = instIO.fields filter(field => portMap.contains(field.name)) // skip unconnected IO
    val instIOConnect = (connectedInstIOFields map { field =>
      field.tpe match {             
        case t: ClockType => Seq(Connect(inst.info, buildExp(Seq(inst.name, field.name)), 
                                                 Ref(hostClock.name, ClockType())))
        case _ => field.flip match {
          case DEFAULT => portMap(field.name) map { endpoint =>
              Connect(inst.info, buildExp(Seq(endpoint, "hostOut", "hostBits", field.name)), 
                                 buildExp(Seq(inst.name, field.name))) 
          }
          case REVERSE => { 
              if (portMap(field.name).length > 1) 
                throw new Exception("It is illegal to have more than 1 connection to a single input" + field)
              Seq(Connect(inst.info, buildExp(Seq(inst.name, field.name)),
                                     buildExp(Seq(portMap(field.name).head, "hostIn", "hostBits", field.name))))
          }
        }
      }
    }).flatten
    val stmts = Begin(Seq(defTargetFire) ++ inputsReady ++ outputsValid ++ Seq(inst) ++ 
                      Seq(connectTargetFire) ++ instIOConnect)

    InModule(inst.info, wrapName(inst.name), ports, stmts)
  }

  // ********** generateSimQueues **********
  // Takes Seq of SimWrapper modules
  // Returns Map of (src, dest) -> SimQueue
  // To prevent duplicates, instead of creating a map with (src, dest) as the key, we could instead
  //   only one direction of the queue for each simport of each module. The only problem with this is
  //   it won't create queues for TopIO since that isn't a real module
  private def generateSimQueues(wrappers: Seq[Module]): SimQMap = {
    def rec(wrappers: Seq[Module], map: SimQMap): SimQMap = {
      if (wrappers.isEmpty) map
      else {
        val w = wrappers.head
        val name = unwrapName(w.name)
        val newMap = (w.ports filter(isSimPort) map { port =>
          (splitSimPort(port) map { field =>
            val (src, dst) = if (field.flip == DEFAULT) (name, port.name) else (port.name, name)
            if (map.contains((src, dst))) SimQMap
            else Map((src, dst) -> buildSimQueue(queueName(src, dst), getHostBits(field).tpe))
          }).flatten.toMap
        }).flatten.toMap
        rec(wrappers.tail, map ++ newMap)
      }
    }
    rec(wrappers, SimQMap)
  }

  // ********** generateSimTop **********
  // Creates the Simulation Top module where all sim modules and sim queues are instantiated and connected
  private def transformTopIO(ports: Seq[Port]): Seq[Port] = {
    val noClock = ports filter (_.tpe != ClockType())
    val inputs  = noClock filter (_.direction == INPUT) map (_.toField.flip()) // Flip because wrapping port is input
    val outputs = noClock filter (_.direction == OUTPUT) map (_.toField)

    Seq(Port(NoInfo, "io", OUTPUT, BundleType(Seq(Field("hostIn", REVERSE, genHostDecoupled(inputs)),
                                                  Field("hostOut", DEFAULT, genHostDecoupled(outputs))))))
  }
  private def generateSimTop(wrappers: Seq[Module], portMap: PortMap, rtlTop: Module): Module = {
    val insts = (wrappers map { m => DefInstance(NoInfo, instName(m.name), m.name) })
    val connectClocks = (wrappers) map { m => 
      Connect(NoInfo, buildExp(Seq(instName(m.name), hostClock.name)), buildExp(hostClock.name)) 
    }
    val connectResets = (wrappers) map { m =>
      Connect(NoInfo, buildExp(Seq(instName(m.name), hostReset.name)), buildExp(hostReset.name))
    }
    // Connect queues to simulation modules (excludes IO)
    val connectWrappers = (wrappers map {m => (m.ports map { port =>
      if (port.direction == OUTPUT) {
        Connect(NoInfo, buildExp(Seq(port.name)), buildExp(Seq(m.name, port.name)))
      } else {
        Connect(NoInfo,buildExp(Seq(m.name, port.name)), buildExp(Seq(port.name)))
      }})}).flatten

   // val connectQueues = (simQueues map { case ((src, dst), queue) =>
   //   (if (src == "topIO") Seq()
   //    else Seq(BulkConnect(NoInfo, buildExp(Seq(instName(queue.name), "io", "enq")), 
   //                                 buildExp(Seq(instName(wrapName(src)), dst, "hostOut"))))
   //   ) ++
   //   (if (dst == "topIO") Seq()
   //    else Seq(BulkConnect(NoInfo, buildExp(Seq(instName(wrapName(dst)), src, "hostIn")),
   //                                 buildExp(Seq(instName(queue.name), "io", "deq"))))
   //   )
   // }).flatten
   // // Connect IO queues, Src means input, Dst means output (ie. the outside word is the Src or Dst)
   // val ioSrcSignals = rtlTop.ports filter (sig => sig.tpe != ClockType() && sig.direction == INPUT) map (_.name)
   // val ioDstSignals = rtlTop.ports filter (sig => sig.tpe != ClockType() && sig.direction == OUTPUT) map (_.name)

   // val ioSrcQueueConnect = if (ioSrcQueues.length > 0) {
   //   val readySignals = ioSrcQueues map (queue => buildExp(Seq(instName(queue.name), "io", "enq", hostReady.name)))
   //   val validSignals = ioSrcQueues map (queue => buildExp(Seq(instName(queue.name), "io", "enq", hostValid.name)))

   //   (ioSrcSignals map { sig => 
   //     (portMap(sig) map { dst => 
   //       Connect(NoInfo, buildExp(Seq(instName(queueName("topIO", dst)), "io", "enq", "hostBits", sig)),
   //                       buildExp(Seq("io", "hostIn", "hostBits", sig)))
   //     })
   //   }).flatten ++
   //   (validSignals map (sig => Connect(NoInfo, buildExp(sig), buildExp(Seq("io", "hostIn", hostValid.name))))) :+
   //   Connect(NoInfo, buildExp(Seq("io", "hostIn", hostReady.name)), genPrimopReduce(AND_OP, readySignals))
   // } else Seq(Empty())

   // val ioDstQueueConnect = if (ioDstQueues.length > 0) {
   //   val readySignals = ioDstQueues map (queue => buildExp(Seq(instName(queue.name), "io", "deq", hostReady.name)))
   //   val validSignals = ioDstQueues map (queue => buildExp(Seq(instName(queue.name), "io", "deq", hostValid.name)))

   //   (ioDstSignals map { sig => 
   //     (portMap(sig) map { src => 
   //       Connect(NoInfo, buildExp(Seq("io", "hostOut", "hostBits", sig)),
   //                       buildExp(Seq(instName(queueName(src, "topIO")), "io", "deq", "hostBits", sig)))
   //     })
   //   }).flatten ++
   //   (readySignals map (sig => Connect(NoInfo, buildExp(sig), buildExp(Seq("io", "hostOut", hostReady.name))))) :+
   //   Connect(NoInfo, buildExp(Seq("io", "hostOut", hostValid.name)), genPrimopReduce(AND_OP, validSignals))
   // } else Seq(Empty())

    val stmts = Begin(insts ++ connectClocks ++ connectResets ++ connectWrappers)
    val ports = Seq(hostClock, hostReset) ++ transformTopIO(rtlTop.ports)
    InModule(NoInfo, "SimTop", ports, stmts)
  }

  // ********** run **********
  // Perform FAME-1 Transformation for MIDAS
  def run(c: Circuit): Circuit = {
    // We should check that the invariants mentioned above are true
    val wrappedNameToModule = (c.modules map (m => m.name -> m))(collection.breakOut): Map[String, Module] 
    val wrappedTop = wrappedNameToModule(c.main)
    
    val wrappedTopInst = DefInstance(NoInfo, instName(wrappedTop.name), wrappedTop.name) 
    val wrappedTopConnects = wrappedTop.ports map { port =>
      if ( port.direction == OUTPUT )
        Connect(NoInfo, buildExp(Seq(port.name)), buildExp(Seq(wrappedTopInst.name, port.name)))
      else 
        Connect(NoInfo,buildExp(Seq(wrappedTopInst.name, port.name)),buildExp(Seq(port.name)))
    }
    val wrappedTopModule = InModule(NoInfo, wrappedTopInst.name, wrappedTop.ports, Begin(Seq(wrappedTopInst) ++ wrappedTopConnects))
    val wrappedCircuit = Circuit(c.info, Seq(wrappedTopModule) ++ c.modules, wrappedTopInst.name)


    val nameToModule = (wrappedCircuit.modules map (m => m.name -> m))(collection.breakOut): Map[String, Module] 
    val top = nameToModule(wrappedCircuit.main)

    val rtlModules = c.modules filter (_.name != top.name) map {
      case m: ExModule => m
      case m: InModule => transformRTL(m)
    }

    val insts = getDefInsts(top)

    val portConn = findPortConn(top, insts)

    // Check that port Connections include all ports for each instance?

    val simWrappers = insts map { inst => 
      genWrapperModule(inst, nameToModule(inst.module), portConn(inst.name))
    }

    val modules = rtlModules ++ simWrappers

    Circuit(wrappedCircuit.info, modules, wrapName(s"${top.name}"))
  }

}
