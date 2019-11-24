package midas
package passes

import java.io.{File, FileWriter, Writer}

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.WrappedExpression._
import firrtl.Utils.{zero, to_flip}

import freechips.rocketchip.config.{Parameters, Field}

import Utils._
import strober.passes.{StroberMetaData, postorder}
import midas.widgets.{BridgeIOAnnotation, AssertBundle, AssertBridgeModule}
import midas.passes.fame.{FAMEChannelConnectionAnnotation, WireChannel}
import midas.targetutils.ExcludeInstanceAssertsAnnotation

private[passes] class AssertPass(
     dir: File)
    (implicit p: Parameters) extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[Golden Gate] Assertion Synthesis"

  type Asserts = collection.mutable.HashMap[String, (Int, String, Expression)]
  type Messages = collection.mutable.HashMap[Int, String]

  private val asserts = collection.mutable.HashMap[String, Asserts]()
  private val messages = collection.mutable.HashMap[String, Messages]()
  private val assertPorts = collection.mutable.HashMap[String, (Port, Port)]()
  private val excludeInstAsserts = collection.mutable.HashSet[(String, String)]()

  private val clockVectorName = "midasAssertsClocks"
  private def getNameOrCroak(ns: Namespace, name: String): String = { assert(ns.tryName(name)); name }

  // Helper method to filter out module instances
  private def excludeInst(excludes: Seq[(String, String)])
                         (parentMod: String, inst: String): Boolean =
    excludes.exists({case (exMod, exInst) => parentMod == exMod && inst == exInst })


  // Matches all on all stop statements, registering the enable predicate
  private def synAsserts(mname: String,
                         namespace: Namespace)
                        (s: Statement): Statement =
    s map synAsserts(mname, namespace) match {
      case Stop(info, ret, clk, en) if ret != 0 && !weq(en, zero) =>
        val idx = asserts(mname).size
        val name = namespace newName s"assert_$idx"
        asserts(mname)(en.serialize) = (idx, name, clk)
        DefNode(info, name, en)
      case s => s
    }


  private def findMessages(mname: String)
                          (s: Statement): Statement =
    s map findMessages(mname) match {
      // work around a large design assert build failure
      // drop arguments and just show the format string
      //case s: Print if s.args.isEmpty =>
      case s: Print =>
        asserts(mname) get s.en.serialize match {
          case Some((idx, str, _)) =>
            messages(mname)(idx) = s.string.serialize
            EmptyStmt
          case _ => s
        }
      case s => s
    }

  private class ModuleAssertInfo(m: Module, meta: StroberMetaData) {
    def getChildren(ports: collection.mutable.Map[String, (Port, Port)],
                    instExcludes: collection.mutable.HashSet[(String, String)]) = {
      (meta.childInsts(m.name)
           .filterNot(instName => excludeInst(instExcludes.toSeq)(m.name, instName))
           .foldRight(Seq[(String, (Port, Port))]())((x, res) =>
             ports get meta.instModMap(x -> m.name) match {
               case None    => res
               case Some(p) => res :+ (x -> p)
            }
          )
      )
    }

    val assertChildren = getChildren(assertPorts, excludeInstAsserts)
    val assertWidth = asserts(m.name).size + ((assertChildren foldLeft 0)({
      case (sum, (_, (assertP, _))) => sum + firrtl.bitWidth(assertP.tpe).toInt
    }))

    def hasAsserts(): Boolean = assertWidth > 0

    // Get references to assertion ports on all child instances
    lazy val (childAsserts, childAssertClocks): (Seq[WSubField], Seq[Seq[WSubIndex]])  =
    (for ((childInstName, (assertPort, clockPort)) <- assertChildren) yield {
      val childWidth = firrtl.bitWidth(assertPort.tpe).toInt
      val assertRef = wsub(wref(childInstName), assertPort.name)
      val clockRefs = Seq.tabulate(childWidth)(i => widx(wsub(wref(childInstName), clockPort.name), i))
      (assertRef, clockRefs)
    }).unzip

    // Get references to all module-local synthesized assertions
    val sortedLocalAsserts = asserts(m.name).values.toSeq.sortWith (_._1 > _._1)
    val (localAsserts, localClocks) =
      sortedLocalAsserts.map({ case (_, en, clk) => (wref(en), clk) }).unzip

    def allAsserts = childAsserts ++ localAsserts
    def allClocks = childAssertClocks.flatten ++ localClocks
    def assertUInt = UIntType(IntWidth(assertWidth))
  }

  private def replaceStopsAndFindMessages(m: DefModule): DefModule = m match {
    case m: Module =>
      val namespace = Namespace(m)
      asserts(m.name) = new Asserts
      messages(m.name) = new Messages
      m.map(synAsserts(m.name, namespace))
       .map(findMessages(m.name))
     case m: ExtModule => m
   }

  private def wireSynthesizedAssertions(meta: StroberMetaData)
                                       (m: DefModule): DefModule = m match {
    case m: Module =>
      val ports = collection.mutable.ArrayBuffer[Port]()
      val stmts = collection.mutable.ArrayBuffer[Statement]()
      val mInfo = new ModuleAssertInfo(m, meta)
      // Connect asserts
      if (mInfo.hasAsserts) {
        val namespace = Namespace(m)
        val tpe = mInfo.assertUInt
        val port = Port(NoInfo, namespace.newName("midasAsserts"), Output, tpe)
        val clockType = VectorType(ClockType, mInfo.assertWidth)
        val clockPort = Port(NoInfo, getNameOrCroak(namespace, clockVectorName), Output, clockType)
        val assertConnect = Connect(NoInfo, WRef(port.name), cat(mInfo.allAsserts))
        val clockConnects = for ((clock, idx) <- mInfo.allClocks.zipWithIndex) yield {
          Connect(NoInfo, widx(WRef(clockPort.name), idx), clock)
        }
        assertPorts(m.name) = (port, clockPort)
        ports ++= Seq(port, clockPort)
        stmts ++= (assertConnect +: clockConnects)
      }
      m.copy(ports = m.ports ++ ports.toSeq, body = Block(m.body +: stmts.toSeq))
    case m: ExtModule => m
  }

  private var assertNum = 0
  def dump(writer: Writer, meta: StroberMetaData, mod: String, path: String) {
      asserts(mod).values.toSeq sortWith (_._1 < _._1) foreach { case (idx, _, _) =>
        writer write s"[id: $assertNum, module: $mod, path: $path]\n"
        writer write (messages(mod)(idx) replace ("""\n""", "\n"))
        writer write "0\n"
        assertNum += 1
      }
      meta.childInsts(mod)
          .filterNot(inst => excludeInstAsserts((mod, inst)))
          .foreach(child => dump(writer, meta, meta.instModMap(child, mod), s"${path}.${child}"))

  }
  def synthesizeAsserts(state: CircuitState): CircuitState = {

    // Step 1: Grab module-based exclusions
    state.annotations.collect {
      case a @ (_: ExcludeInstanceAssertsAnnotation) => excludeInstAsserts += a.target
    }

    // Step 2: Replace stop statements (asserts) and find associated message 
    val c = state.circuit.copy(modules = state.circuit.modules.map(replaceStopsAndFindMessages))
    val topModule = c.modules.collectFirst({ case m: Module if m.name == c.main => m }).get
    val namespace = Namespace(topModule)

    // Step 3: Wiring assertions and associated clocks to the top-level
    val meta = StroberMetaData(c)
    val mods = postorder(c, meta)(wireSynthesizedAssertions(meta))
    val f = new FileWriter(new File(dir, s"${c.main}.asserts"))
    dump(f, meta, c.main, c.main)
    f.close

    // Step 4: Use wired-clocks to associate assertions with particular input clocks
    val postWiredState = state.copy(circuit = c.copy(modules = mods), form = MidForm)
    val loweredState = Seq(new ResolveAndCheck, new HighFirrtlToMiddleFirrtl, new MiddleFirrtlToLowFirrtl).foldLeft(postWiredState)((state, xform) => xform.transform(state))
    val connectivity = (new firrtl.transforms.CheckCombLoops).analyze(loweredState)(c.main)
    val mainModulePortMap  = loweredState.circuit.modules
                                         .collectFirst({ case m if m.name == c.main => m }).get
                                         .ports.map(p => p.name -> p).toMap

    // For each clock in clock channel, list associated assert indices
    val groupedAsserts = Seq.tabulate(assertNum)(i => i)
      .groupBy({ idx =>
        val name = s"${clockVectorName}_$idx"
        val srcClockPorts = connectivity.getEdges(name).map(mainModulePortMap(_))
        assert(srcClockPorts.size == 1)
        srcClockPorts.head
      })

    // Step 5: Re-wire the top-level module
    val ports = collection.mutable.ArrayBuffer[Port]()
    val stmts = collection.mutable.ArrayBuffer[Statement]()
    val assertAnnos = collection.mutable.ArrayBuffer[Annotation]()
    val mInfo = new ModuleAssertInfo(topModule, meta)

    // Step 5a: Connect all assertions to a single wire to match the order of our previous analysis
    val allAssertsWire = DefWire(NoInfo, "allAsserts", mInfo.assertUInt)
    val allAssertConnect = Connect(NoInfo, WRef(allAssertsWire), cat(mInfo.allAsserts))
    stmts ++= Seq(allAssertsWire, allAssertConnect)

    // Step 5b: Generate unique ports for each clock
    for ((clockName, asserts) <- groupedAsserts) {
      val portName = namespace.newName(s"midasAsserts_${clockName.name}")
      val clockPortName = namespace.newName(s"midasAsserts_${clockName.name}_clock")
      val tpe = UIntType(IntWidth(asserts.size))
      val port = Port(NoInfo, portName, Output, tpe)
      val clockPort = Port(NoInfo, clockPortName, Output, ClockType)
      ports ++= Seq(port, clockPort)
      val bitExtracts = asserts.map(idx => DoPrim(PrimOps.Bits, Seq(WRef(allAssertsWire)), Seq(idx, idx), UIntType(IntWidth(1))))
      val connectAsserts = Connect(NoInfo, WRef(port), cat(bitExtracts))
      val connectClock   = Connect(NoInfo, WRef(clockPort), WRef(clockName))
      stmts ++= Seq(connectClock, connectAsserts)

      val portRT = ModuleTarget(c.main, c.main).ref(portName)
      val clockPortRT = ModuleTarget(c.main, c.main).ref(clockPortName)
      val fcca = FAMEChannelConnectionAnnotation.source(portName, WireChannel, Some(clockPortRT), Seq(portRT))
      val bridgeAnno = BridgeIOAnnotation(
        target = portRT,
        widget = Some((p: Parameters) => new AssertBridgeModule(assertNum)(p)),
        channelMapping = Map("" -> portName)
      )
      assertAnnos ++= Seq(fcca, bridgeAnno)
    }
    val wiredTopModule = topModule.copy(ports = topModule.ports ++ ports,
                                        body = Block(topModule.body +: stmts.toSeq))

    println(s"[Golden Gate] total # of assertions synthesized: $assertNum")

    state.copy(
      circuit = c.copy(modules = wiredTopModule +: mods.filterNot(_.name == c.main)),
      form    = HighForm,
      annotations = state.annotations ++ assertAnnos
    )
  }

  def execute(state: CircuitState): CircuitState = {
    if (p(SynthAsserts)) synthesizeAsserts(state) else {
      // Still need to touch the file.
      val f = new FileWriter(new File(dir, s"${state.circuit.main}.asserts"))
      f.close
      state
    }
  }
}
