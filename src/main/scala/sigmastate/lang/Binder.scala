package sigmastate.lang

import org.bitbucket.inkytonik.kiama.rewriting.Rewriter._
import sigmastate.lang.Terms._
import sigmastate._
import Values._
import sigmastate.utxo._
import sigmastate.SCollection._

trait Binder {
  def bind(e: SValue): SValue
}

class SigmaBinder(env: Map[String, Any]) extends Binder {
  import SigmaBinder._
  import SigmaPredef._

  /** Rewriting of AST with respect to environment to resolve all references and infer types.
    * If successfull, returns type-checked Value which is ready for evaluation by the interpreter. */
  private def eval(e: SValue, env: Map[String, Any]): SValue = rewrite(reduce(strategy[SValue]({
    case Ident(n, NoType) => env.get(n) match {
      case Some(v) => v match {
        case arr: Array[Byte] => Some(ByteArrayConstant(arr))
        case v: Int => Some(IntConstant(v))
        case v: Long => Some(IntConstant(v))
        case b: Boolean => Some(if(b) TrueLeaf else FalseLeaf)
        case v: SValue => Some(v)
        case _ => None
//        case _ => error(s"Variable $n has invalid value $v")
      }
      case None => predefinedEnv.get(n) match {
        case Some(v) => Some(Ident(n, v.tpe))
        case None => n match {
          case "HEIGHT" => Some(Height)
          case "INPUTS" => Some(Inputs)
          case "OUTPUTS" => Some(Outputs)
          case "LastBlockUtxoRootHash" => Some(LastBlockUtxoRootHash)
          case "SELF" => Some(Self)
          case _ => None
//          case _ =>
//            error(s"Variable name $n is not defined")
        }
      }
    }
    // Rule: Array(...) -->
    case Apply(Ident("Array", _), args) =>
      val tpe = if (args.isEmpty) NoType else args(0).tpe
      Some(ConcreteCollection(args)(tpe))

    // Rule: col.size --> SizeOf(col)
    case Select(obj, "size") if obj.tpe.isCollection =>
      Some(SizeOf(obj.asValue[SCollection[SType]]))

    // Rule: all(Array(...)) --> AND(...)
    case Apply(AllSym, Seq(ConcreteCollection(args: Seq[Value[SBoolean.type]]@unchecked))) =>
      Some(AND(args))

    // Rule: exists(input, f) -->
    case Apply(ExistsSym, Seq(input: Value[SCollection[SType]]@unchecked, pred: Value[SFunc]@unchecked)) =>
      val tItem = input.tpe.elemType
      val expectedTpe = SFunc(Vector(tItem), SBoolean)
      if (!pred.tpe.canBeTypedAs(expectedTpe))
        error(s"Invalid type of $pred. Expected $expectedTpe")
      val args = expectedTpe.tDom.zipWithIndex.map { case (t, i) => (s"arg${i+1}", t) }
      Some(ExistsSym)
//      Some(Exists(input, ))

    // Rule: fun (...) = ... --> fun (...): T = ...
    case lam @ Lambda(args, t, Some(body)) if !lam.evaluated =>
      val b1 = eval(body, env)
      val t1 = if (t != NoType) t else b1.tpe
      Some(new Lambda(args, t1, Some(b1)) { override def evaluated = true })

    // Rule: { e } --> e
    case Block(Seq(), e) => Some(e)
    
    case block @ Block(binds, t) if !block.evaluated =>
      val newBinds = for (Let(n, t, b) <- binds) yield {
        if (env.contains(n)) error(s"Variable $n already defined ($n = ${env(n)}")
        val b1 = eval(b, env)
        Let(n, if (t != NoType) t else b1.tpe, b1)
      }
      val t1 = eval(t, env)
      Some(new Block(newBinds, t1) { override def evaluated = true })
//    case v =>
//      val v1 = rewrite(some(rule[Value[SType]] { case v => eval(v, env) }))(v)
//      Some(v1)
  })))(e)

  def bind(e: Value[SType]): Value[SType] = eval(e, env)
}

class BinderException(msg: String) extends Exception(msg)

object SigmaBinder {
  def error(msg: String) = throw new BinderException(msg)
}
