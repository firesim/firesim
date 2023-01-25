package midas
package passes

import scala.collection.mutable

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.WrappedExpression._
import firrtl.Utils.{zero, BoolType}


import midas.passes.Utils.cat
import midas.widgets.{BridgeIOAnnotation, AssertBridgeModule, AssertBridgeParameters}
import midas.passes.fame.{FAMEChannelConnectionAnnotation, WireChannel}
import midas.stage.phases.ConfigParametersAnnotation
import midas.targetutils.{ExcludeInstanceAssertsAnnotation, GlobalResetConditionSink}

private[passes] class AssertionSynthesis extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[Golden Gate] Assertion Synthesis"

  type Asserts = collection.mutable.HashMap[String, (Int, String, String)]
  type Messages = collection.mutable.HashMap[Int, String]

  private val asserts = collection.mutable.HashMap[String, Asserts]()
  private val messages = collection.mutable.HashMap[String, Messages]()
  private val assertPorts = collection.mutable.HashMap[String, (Port, Seq[ReferenceTarget])]()
  private val excludeInstAsserts = collection.mutable.HashSet[(String, String)]()

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
        val clockName = clk match {
          case Reference(name, _, _, _) => name
          case o => throw new RuntimeException(s"$clk")
        }
        asserts(mname)(en.serialize) = (idx, name, clockName)
        DefNode(info, name, en)
      case firrtl.ir.Verification(Formal.Assert,_,_,_,en,_) =>
        throw new RuntimeException(
          s"""|New Verification.Assert IR nodes cannot currently be synthesized.
              |EnsureConvert asserts run early in lowering translate them into a synthesizable form.
              |See midas.passes.RunConvertAssertsEarly.""".stripMargin)
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

  private class ModuleAssertInfo(m: Module, meta: StroberMetaData, mT: ModuleTarget) {
    def getChildren(ports: collection.mutable.Map[String, (Port, Seq[ReferenceTarget])],
                    instExcludes: collection.mutable.HashSet[(String, String)]) = {
      (meta.childInsts(m.name)
           .filterNot(instName => excludeInst(instExcludes.toSeq)(m.name, instName))
           .foldRight(Seq[(String, (Port, Seq[ReferenceTarget]))]())((x, res) =>
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
    lazy val (childAsserts, childAssertClocksRTs): (Seq[WSubField], Seq[Seq[ReferenceTarget]])  =
    (for ((childInstName, (assertPort, clockRTs)) <- assertChildren) yield {
      val childWidth = firrtl.bitWidth(assertPort.tpe).toInt
      val assertRef = WSubField(WRef(childInstName), assertPort.name)
      val clockRefs = clockRTs.map(_.addHierarchy(m.name, childInstName))
      (assertRef, clockRefs)
    }).unzip

    // Get references to all module-local synthesized assertions
    val sortedLocalAsserts = asserts(m.name).values.toSeq.sortWith(_._1 < _._1)
    val (localAsserts, localClocks) = sortedLocalAsserts.map({ case (_, en, clk) => (WRef(en), mT.ref(clk)) }).unzip

    def allAsserts = localAsserts ++ childAsserts
    def allClocks = localClocks ++ childAssertClocksRTs.flatten
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

  private def wireSynthesizedAssertions(meta: StroberMetaData, cT: CircuitTarget)
                                       (m: DefModule): DefModule = m match {
    case m: Module =>
      val ports = collection.mutable.ArrayBuffer[Port]()
      val stmts = collection.mutable.ArrayBuffer[Statement]()
      val mT = cT.module(m.name)
      val mInfo = new ModuleAssertInfo(m, meta, mT)
      // Connect asserts
      if (mInfo.hasAsserts()) {
        val namespace = Namespace(m)
        val tpe = mInfo.assertUInt
        val port = Port(NoInfo, namespace.newName("midasAsserts"), Output, tpe)
        val assertConnect = Connect(NoInfo, WRef(port.name), cat(mInfo.allAsserts.reverse))
        assertPorts(m.name) = (port, mInfo.allClocks)
        ports += port
        stmts += assertConnect
      }
      m.copy(ports = m.ports ++ ports.toSeq, body = Block(m.body +: stmts.toSeq))
    case m: ExtModule => m
  }

  def formatMessages(meta: StroberMetaData, topModule: String): Seq[String] = {
    val formattedMessages = new mutable.ArrayBuffer[String]()
    def dump(mod: String, path: String): Unit = {
        formattedMessages ++= (asserts(mod).values.toSeq).sortWith(_._1 < _._1).map({ case (idx, _, _) =>
          s"module: $mod, path: $path]\n" + (messages(mod)(idx) replace ("""\n""", "\n"))
        })
        meta.childInsts(mod).reverse
            .filterNot(inst => excludeInstAsserts((mod, inst)))
            .foreach(child => dump(meta.instModMap(child, mod), s"${path}.${child}"))
    }
    dump(topModule, topModule)
    formattedMessages.toSeq
  }

  def synthesizeAsserts(state: CircuitState): CircuitState = {

    // Step 1: Grab module-based exclusions
    state.annotations.collect {
      case a @ (_: ExcludeInstanceAssertsAnnotation) => excludeInstAsserts += a.target
    }

    // Step 2: Replace stop statements (asserts) and find associated message
    val c = state.circuit.copy(modules = state.circuit.modules.map(replaceStopsAndFindMessages))
    val topModule = c.modules.collectFirst({ case m: Module if m.name == c.main => m }).get
    val topMT = ModuleTarget(c.main, c.main)
    val namespace = Namespace(topModule)

    // Step 3: Wire assertions to the top-level
    val meta = StroberMetaData(c)
    val mods = postorder(c, meta)(wireSynthesizedAssertions(meta, CircuitTarget(c.main)))
    val formattedMessages = formatMessages(meta, c.main)

    val mInfo = new ModuleAssertInfo(topModule, meta, topMT)
    println(s"[Golden Gate] total # of assertions synthesized: ${mInfo.assertWidth}")

    if (!mInfo.hasAsserts()) state else {
      // Step 4: Associate each assertion with a source clock
      val postWiredState = state.copy(circuit = c.copy(modules = mods), form = MidForm)
      val loweredState = Seq(new ResolveAndCheck, new HighFirrtlToMiddleFirrtl, new MiddleFirrtlToLowFirrtl).foldLeft(postWiredState)((state, xform) => xform.transform(state))
      val finder = new ClockSourceFinder(loweredState)
      val clockMapping = mInfo.allClocks.map(cT => cT -> finder.findRootDriver(cT)).toMap
      // Generate (sourceClock, assertIndex) tuples for all synthesized
      // assertions. Reject assertions with undriven clocks
      val rootClocks = mInfo.allClocks
        .map(clockMapping)
        .zipWithIndex
        .collect({ case (Some(clockRT), idx) => (clockRT, idx) })

      // Group assertion indices by their clocks
      val groupedAsserts = rootClocks.groupBy(_._1).mapValues(values => values.map(_._2))

      // Step 5: Re-wire the top-level module
      val ports = collection.mutable.ArrayBuffer[Port]()
      val stmts = collection.mutable.ArrayBuffer[Statement]()
      val assertAnnos = collection.mutable.ArrayBuffer[Annotation]()

      // Step 5a: Connect all assertions to a single wire to match the order of our previous analysis
      val allAssertsWire = DefWire(NoInfo, "allAsserts", mInfo.assertUInt)
      val allAssertConnect = Connect(NoInfo, WRef(allAssertsWire), cat(mInfo.allAsserts.reverse))
      stmts ++= Seq(allAssertsWire, allAssertConnect)

      // Step 5b: Generate unique ports for each clock
      for ((clockRT, asserts) <- groupedAsserts) {
        val assertPortName = namespace.newName(s"midasAsserts_${clockRT.ref}_asserts")
        val resetPortName = namespace.newName(s"midasAsserts_${clockRT.ref}_globalResetCondition")
        val clockPortName = namespace.newName(s"midasAsserts_${clockRT.ref}_clock")
        val tpe = UIntType(IntWidth(asserts.size))
        val port = Port(NoInfo, assertPortName, Output, tpe)
        val resetPort = Port(NoInfo, resetPortName, Output, BoolType)
        val clockPort = Port(NoInfo, clockPortName, Output, ClockType)
        ports ++= Seq(port, clockPort, resetPort)
        val bitExtracts = asserts.map(idx => DoPrim(PrimOps.Bits, Seq(WRef(allAssertsWire)), Seq(idx, idx), UIntType(IntWidth(1))))
        val connectAsserts = Connect(NoInfo, WRef(port), cat(bitExtracts.reverse))
        val connectClock   = Connect(NoInfo, WRef(clockPort), WRef(clockRT.ref))
        // In the event no GlobalResetCondition is provided, tie this off
        val connectReset   = Connect(NoInfo, WRef(resetPort), zero)

        stmts ++= Seq(connectClock, connectAsserts, connectReset)

        // Generate the bridge Annotation
        val portRT = topMT.ref(assertPortName)
        val clockPortRT = topMT.ref(clockPortName)
        val assertFCCA = FAMEChannelConnectionAnnotation.source(assertPortName, WireChannel, Some(clockPortRT), Seq(portRT))

        val resetPortRT = topMT.ref(resetPortName)
        val resetFCCA = FAMEChannelConnectionAnnotation.source(resetPortName, WireChannel, Some(clockPortRT), Seq(resetPortRT))
        val resetConditionAnno = GlobalResetConditionSink(resetPortRT)

        val assertMessages = asserts.map(formattedMessages(_))
        val bridgeAnno = BridgeIOAnnotation(
          target = portRT,
          channelNames = Seq(resetPortName, assertPortName),
          widgetClass = classOf[AssertBridgeModule].getName,
          widgetConstructorKey = AssertBridgeParameters(assertPortName, resetPortName, assertMessages)
        )
        assertAnnos ++= Seq(resetConditionAnno, assertFCCA, resetFCCA, bridgeAnno)
      }
      val wiredTopModule = topModule.copy(ports = topModule.ports ++ ports,
                                          body = Block(topModule.body +: stmts.toSeq))

      state.copy(
        circuit = c.copy(modules = wiredTopModule +: mods.filterNot(_.name == c.main)),
        form    = HighForm,
        annotations = state.annotations ++ assertAnnos
      )
    }
  }

  def execute(state: CircuitState): CircuitState = {
    val p = state.annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get
    if (p(SynthAsserts)) synthesizeAsserts(state) else state
  }
}
