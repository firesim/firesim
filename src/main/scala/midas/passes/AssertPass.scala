package midas
package passes

import java.io.{File, FileWriter, Writer}

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.WrappedExpression._
import firrtl.Utils.{zero, to_flip}

import freechips.rocketchip.config.{Parameters, Field}

import Utils._
import strober.passes.{StroberMetaData, postorder}

private[passes] class AssertPass(
     dir: File)
    (implicit param: Parameters) extends firrtl.passes.Pass {
  override def name = "[midas] Assert Pass"
  type Asserts = collection.mutable.HashMap[String, (Int, String)]
  type Messages = collection.mutable.HashMap[Int, String]
  type Prints = collection.mutable.ArrayBuffer[Print]
  type Formats = collection.mutable.ArrayBuffer[(String, Seq[String])]

  private val asserts = collection.mutable.HashMap[String, Asserts]()
  private val messages = collection.mutable.HashMap[String, Messages]()
  private val assertPorts = collection.mutable.HashMap[String, Port]()
  private val prints = collection.mutable.HashMap[String, Prints]()
  private val printPorts = collection.mutable.HashMap[String, Port]()

  private def synAsserts(mname: String,
                         namespace: Namespace)
                        (s: Statement): Statement =
    s map synAsserts(mname, namespace) match {
      case s: Stop if param(EnableDebug) && s.ret != 0 && !weq(s.en, zero) =>
        val idx = asserts(mname).size
        val name = namespace newName s"assert_$idx"
        asserts(mname)(s.en.serialize) = idx -> name
        DefNode(s.info, name, s.en)
      case s => s
    }

  private def findMessages(mname: String)
                          (s: Statement): Statement =
    s map findMessages(mname) match {
      case s: Print if param(EnableDebug) && s.args.isEmpty =>
        asserts(mname) get s.en.serialize match {
          case Some((idx, str)) =>
            messages(mname)(idx) = s.string.serialize
            EmptyStmt
          case _ => s
        }
      case s: Print if param(EnablePrint) && s.args.nonEmpty && !(mname contains "UART") =>
        prints(mname) += s
        EmptyStmt
      case s => s
    }

  private def transform(meta: StroberMetaData)
                       (m: DefModule): DefModule = {
    val namespace = Namespace(m)
    asserts(m.name) = new Asserts
    messages(m.name) = new Messages
    prints(m.name) = new Prints

    def getChildren(ports: collection.mutable.Map[String, Port]) = {
      (meta.childInsts(m.name) filter (x =>
        !(m.name == "RocketTile" && x == "fpuOpt") &&
        !(m.name == "NonBlockingDCache_dcache" && x == "dtlb")
      ) foldRight Seq[(String, Port)]())(
        (x, res) => ports get meta.instModMap(x -> m.name) match {
          case None    => res
          case Some(p) => res :+ (x -> p)
        }
      )
    }

    (m map synAsserts(m.name, namespace)
       map findMessages(m.name)) match {
      case m: Module =>
        val ports = collection.mutable.ArrayBuffer[Port]()
        val stmts = collection.mutable.ArrayBuffer[Statement]()
        // Connect asserts
        val assertChildren = getChildren(assertPorts)
        val assertWidth = asserts(m.name).size + ((assertChildren foldLeft 0)(
          (res, x) => res + firrtl.bitWidth(x._2.tpe).toInt))
        if (assertWidth > 0) {
          val tpe = UIntType(IntWidth(assertWidth))
          val port = Port(NoInfo, namespace.newName("midasAsserts"), Output, tpe)
          val stmt = Connect(NoInfo, WRef(port.name), cat(
            (assertChildren map (x => wsub(wref(x._1), x._2.name))) ++
            (asserts(m.name).values.toSeq sortWith (_._1 > _._1) map (x => wref(x._2)))))
          assertPorts(m.name) = port
          ports += port
          stmts += stmt
        }
        // Connect prints
        val printChildren = getChildren(printPorts)
        if (printChildren.size + prints(m.name).size > 0) {
          val tpe = BundleType((prints(m.name).zipWithIndex map { case (print, idx) =>
              val total = (print.args foldLeft 0)((res, arg) => res + bitWidth(arg.tpe).toInt)
              val width = 8 * ((total - 1) / 8 + 1)
              Field(s"print_${idx}", Default, UIntType(IntWidth(width + 1)))
            }) ++ (printChildren flatMap { case (child, p) => p.tpe match {
              // Field(child, Default, p.tpe)
              case BundleType(fs) => fs map (f => f.copy(name=s"${child}_${f.name}")) }
            })
          )
          val port = Port(NoInfo, namespace.newName("midasPrints"), Output, tpe)
          printPorts(m.name) = port
          ports += port
          stmts ++= (printChildren flatMap { case (child, p) => p.tpe match {
            case BundleType(fs) => fs map (f =>
              Connect(NoInfo, wsub(WRef(port.name), s"${child}_${f.name}"),
                              wsub(wsub(WRef(child), p.name), f.name)))
          }}) ++ (prints(m.name).zipWithIndex map { case (print, idx) =>
              Connect(NoInfo, wsub(WRef(port.name), s"print_${idx}"),
                              cat(print.args.reverse :+ print.en))
          })
        }
        m.copy(ports = m.ports ++ ports.toSeq, body = Block(m.body +: stmts.toSeq))
      case m: ExtModule => m
    }
  }

  trait DumpType
  case object DumpAsserts extends DumpType {
    override def toString = "asserts"
  }
  case object DumpPrints extends DumpType {
    override def toString = "prints"
  }
  private var assertNum = 0
  private var printNum = 0
  def dump(writer: Writer, meta: StroberMetaData, mod: String, path: String)(implicit t: DumpType) {
    t match {
      case DumpAsserts =>
        asserts(mod).values.toSeq sortWith (_._1 < _._1) foreach { case (idx, _) =>
          writer write s"[id: $assertNum, module: $mod, path: $path]\n"
          writer write (messages(mod)(idx) replace ("""\n""", "\n"))
          writer write "0\n"
          assertNum += 1
        }
        meta.childInsts(mod) filter (x =>
         !(mod == "RocketTile" && x == "fpuOpt") &&
         !(mod == "NonBlockingDCache_dcache" && x == "dtlb")
        ) foreach { child =>
          dump(writer, meta, meta.instModMap(child, mod), s"${path}.${child}")
        }
      case DumpPrints =>
        prints(mod) foreach { print =>
          writer write """%s""".format(print.string.serialize)
          writer write s"\n"
          writer write (print.args map (arg => s"${path}.${arg.serialize} ${bitWidth(arg.tpe)}") mkString " ")
          writer write s"\n"
          printNum += 1
        }
        meta.childInsts(mod).reverse filter (x =>
         !(mod == "RocketTile" && x == "fpuOpt") &&
         !(mod == "NonBlockingDCache_dcache" && x == "dtlb")
        ) foreach { child =>
          dump(writer, meta, meta.instModMap(child, mod), s"${path}.${child}")
        }
    }
  }

  def run(c: Circuit) = {
    val meta = StroberMetaData(c)
    val mods = postorder(c, meta)(transform(meta))
    Seq(DumpAsserts, DumpPrints) foreach { t =>
      val f = new FileWriter(new File(dir, s"${c.main}.${t}"))
      dump(f, meta, c.main, c.main)(t)
      f.close
    }
    if (assertNum > 0) {
      println(s"[midas] total # of assertions: $assertNum")
    }
    if (printNum > 0) {
      println(s"[midas] total # of prints: $printNum")
    }
    c.copy(modules = mods)
  }
}
