package midas
package passes

import java.io.{File, FileWriter, Writer}

import firrtl._
import firrtl.annotations.{CircuitName, ModuleName, ComponentName}
import firrtl.ir._
import firrtl.Mappers._
import firrtl.WrappedExpression._
import firrtl.Utils.{zero, to_flip}

import freechips.rocketchip.config.{Parameters, Field}

import Utils._
import strober.passes.{StroberMetaData, postorder}
import midas.widgets.AssertBundle


case class AddedAssertIoAnnotation(port: Port) extends AddedTargetIoAnnotation[AssertBundle]{
  def generateChiselIO(): (String, AssertBundle) = (port.name, new AssertBundle(firrtl.bitWidth(port.tpe).toInt))
  def update(renames: RenameMap): Seq[AddedAssertIoAnnotation] = Seq(this)
}

private[passes] class AssertPass(
     dir: File)
    (implicit p: Parameters) extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[MIDAS] Assertion Synthesis"

  type Asserts = collection.mutable.HashMap[String, (Int, String)]
  type Messages = collection.mutable.HashMap[Int, String]

  private val asserts = collection.mutable.HashMap[String, Asserts]()
  private val messages = collection.mutable.HashMap[String, Messages]()
  private val assertPorts = collection.mutable.HashMap[String, Port]()

  // Helper method to filter out module instances
  private def excludeInst(excludes: Seq[(String, String)])
                         (parentMod: String, inst: String): Boolean =
    excludes.exists({case (exMod, exInst) => parentMod == exMod && inst == exInst })

  private def excludeInstAsserts = excludeInst(p(ExcludeInstanceAsserts)) _


  // Matches all on all stop statements, registering the enable predicate
  private def synAsserts(mname: String,
                         namespace: Namespace)
                        (s: Statement): Statement =
    s map synAsserts(mname, namespace) match {
      case s: Stop if s.ret != 0 && !weq(s.en, zero) =>
        val idx = asserts(mname).size
        val name = namespace newName s"assert_$idx"
        asserts(mname)(s.en.serialize) = idx -> name
        DefNode(s.info, name, s.en)
      case s => s
    }


  private def findMessages(mname: String)
                          (s: Statement): Statement =
    s map findMessages(mname) match {
      case s: Print if s.args.isEmpty =>
        asserts(mname) get s.en.serialize match {
          case Some((idx, str)) =>
            messages(mname)(idx) = s.string.serialize
            EmptyStmt
          case _ => s
        }
      case s => s
    }

  private def transform(meta: StroberMetaData)
                       (m: DefModule): DefModule = {
    val namespace = Namespace(m)
    asserts(m.name) = new Asserts
    messages(m.name) = new Messages

    def getChildren(ports: collection.mutable.Map[String, Port], instExcludes: Seq[(String, String)]) = {
      (meta.childInsts(m.name)
           .filterNot(instName => excludeInst(instExcludes)(m.name, instName))
           .foldRight(Seq[(String, Port)]())((x, res) =>
             ports get meta.instModMap(x -> m.name) match {
               case None    => res
               case Some(p) => res :+ (x -> p)
            }
          )
      )
    }

    (m map synAsserts(m.name, namespace)
       map findMessages(m.name)) match {
      case m: Module =>
        val ports = collection.mutable.ArrayBuffer[Port]()
        val stmts = collection.mutable.ArrayBuffer[Statement]()
        // Connect asserts
        val assertChildren = getChildren(assertPorts, p(ExcludeInstanceAsserts))
        val assertWidth = asserts(m.name).size + ((assertChildren foldLeft 0)(
          (res, x) => res + firrtl.bitWidth(x._2.tpe).toInt))
        if (assertWidth > 0) {
          // Hack: Put the UInt in a bundle so that we can type match on it in SimMapping
          val tpe = BundleType(Seq(Field("asserts", Default, UIntType(IntWidth(assertWidth)))))
          val port = Port(NoInfo, namespace.newName("midasAsserts"), Output, tpe)
          val stmt = Connect(NoInfo, wsub(WRef(port.name), "asserts"), cat(
            (assertChildren map (x => wsub(wsub(wref(x._1), x._2.name), "asserts"))) ++
            (asserts(m.name).values.toSeq sortWith (_._1 > _._1) map (x => wref(x._2)))))
          assertPorts(m.name) = port
          ports += port
          stmts += stmt
        }
        m.copy(ports = m.ports ++ ports.toSeq, body = Block(m.body +: stmts.toSeq))
      case m: ExtModule => m
    }
  }
  private var assertNum = 0
  def dump(writer: Writer, meta: StroberMetaData, mod: String, path: String) {
      asserts(mod).values.toSeq sortWith (_._1 < _._1) foreach { case (idx, _) =>
        writer write s"[id: $assertNum, module: $mod, path: $path]\n"
        writer write (messages(mod)(idx) replace ("""\n""", "\n"))
        writer write "0\n"
        assertNum += 1
      }
      meta.childInsts(mod)
          .filterNot(inst => excludeInstAsserts(mod, inst))
          .foreach(child => dump(writer, meta, meta.instModMap(child, mod), s"${path}.${child}"))

  }
  def synthesizeAsserts(state: CircuitState): CircuitState = {
    val c = state.circuit
    val meta = StroberMetaData(c)
    val mods = postorder(c, meta)(transform(meta))
    val f = new FileWriter(new File(dir, s"${c.main}.asserts"))
    dump(f, meta, c.main, c.main)
    f.close

    println(s"[MIDAS] total # of assertions synthesized: $assertNum")

    val mName = ModuleName(c.main, CircuitName(c.main))
    val assertAnno = if (assertNum > 0) {
      Seq(AddedAssertIoAnnotation(assertPorts(c.main)))
    } else {
      Seq()
    }

    state.copy(
      circuit = c.copy(modules = mods),
      form    = HighForm,
      annotations = state.annotations ++ assertAnno)
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
