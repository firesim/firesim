package midas
package passes

import java.io.{File, FileWriter, Writer}
import scala.collection.mutable

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

  type Asserts = collection.mutable.HashMap[String, (Int, String, String)]
  type Messages = collection.mutable.HashMap[Int, String]

  private val asserts = collection.mutable.HashMap[String, Asserts]()
  private val messages = collection.mutable.HashMap[String, Messages]()
  private val assertPorts = collection.mutable.HashMap[String, (Port, Seq[ReferenceTarget])]()
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
        val clockName = clk match {
          case Reference(name, _) => name
          case WRef(name, _, _, _) => name
          case o => throw new RuntimeException(s"$clk")
        }
        asserts(mname)(en.serialize) = (idx, name, clockName)
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
      val assertRef = wsub(wref(childInstName), assertPort.name)
      val clockRefs = clockRTs.map(_.addHierarchy(m.name, childInstName))
      (assertRef, clockRefs)
    }).unzip

    // Get references to all module-local synthesized assertions
    val sortedLocalAsserts = asserts(m.name).values.toSeq.sortWith (_._1 > _._1)
    val (localAsserts, localClocks) = sortedLocalAsserts.map({ case (_, en, clk) => (wref(en), mT.ref(clk)) }).unzip

    def allAsserts = childAsserts ++ localAsserts
    def allClocks = childAssertClocksRTs.flatten ++ localClocks
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
      if (mInfo.hasAsserts) {
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
        formattedMessages ++= (asserts(mod).values.toSeq).sortWith(_._1 > _._1).map({ case (idx, _, _) =>
          s"module: $mod, path: $path]\n" + (messages(mod)(idx) replace ("""\n""", "\n"))
        })
        meta.childInsts(mod)
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

    if (!mInfo.hasAsserts) state else {
      // Step 4: Associate each assertion with a source clock
      val postWiredState = state.copy(circuit = c.copy(modules = mods), form = MidForm)
      val loweredState = Seq(new ResolveAndCheck, new HighFirrtlToMiddleFirrtl, new MiddleFirrtlToLowFirrtl).foldLeft(postWiredState)((state, xform) => xform.transform(state))
      val clockMapping = FindClockSources.analyze(loweredState, mInfo.allClocks)
      val rootClocks = mInfo.allClocks.map(clockMapping)

      // For each clock in clock channel, list associated assert indices
      val groupedAsserts = rootClocks.zipWithIndex.groupBy(_._1).mapValues(values => values.map(_._2))

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
        val portName = namespace.newName(s"midasAsserts_${clockRT.ref}")
        val clockPortName = namespace.newName(s"midasAsserts_${clockRT.ref}_clock")
        val tpe = UIntType(IntWidth(asserts.size))
        val port = Port(NoInfo, portName, Output, tpe)
        val clockPort = Port(NoInfo, clockPortName, Output, ClockType)
        ports ++= Seq(port, clockPort)
        val bitExtracts = asserts.map(idx => DoPrim(PrimOps.Bits, Seq(WRef(allAssertsWire)), Seq(idx, idx), UIntType(IntWidth(1))))
        val connectAsserts = Connect(NoInfo, WRef(port), cat(bitExtracts.reverse))
        val connectClock   = Connect(NoInfo, WRef(clockPort), WRef(clockRT.ref))
        stmts ++= Seq(connectClock, connectAsserts)

        // Generate the bridge Annotation
        val portRT = ModuleTarget(c.main, c.main).ref(portName)
        val clockPortRT = ModuleTarget(c.main, c.main).ref(clockPortName)
        val fcca = FAMEChannelConnectionAnnotation.source(portName, WireChannel, Some(clockPortRT), Seq(portRT))
        val assertMessages = asserts.map(formattedMessages(_))
        val bridgeAnno = BridgeIOAnnotation(
          target = portRT,
          widget = Some((p: Parameters) => new AssertBridgeModule(assertMessages)(p)),
          channelMapping = Map("" -> portName)
        )
        assertAnnos ++= Seq(fcca, bridgeAnno)
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
    if (p(SynthAsserts)) synthesizeAsserts(state) else state
  }
}
