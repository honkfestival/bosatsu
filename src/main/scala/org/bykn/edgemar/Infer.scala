package org.bykn.edgemar

/**
 * This is a scala port of the example of Hindley Milner inference
 * here: http://dev.stephendiehl.com/fun/006_hindley_milner.html
 */

import cats.data.{ EitherT, RWST, StateT, NonEmptyList }
import cats.{ Eval, Monad, MonadError, Traverse }
import cats.implicits._

case class Unique(id: Long) {
  def next: Unique =
    if (id == Long.MaxValue) sys.error("overflow")
    else Unique(id + 1L)
}

case class Subst(toMap: Map[String, Type]) {
  def getOrElse(s: String, t: => Type): Type =
    toMap.getOrElse(s, t)

  def compose(that: Subst): Subst = {
    val m1 = that.toMap.iterator.map { case (s, t) =>
      s -> Substitutable[Type].apply(this, t)
    }.toMap

    Subst(m1 ++ toMap)
  }
}
object Subst {
  def empty: Subst = Subst(Map.empty)
}

case class Constraint(left: Type, right: Type)

case class Unifier(subst: Subst, constraints: List[Constraint])

object Unifier {
  def empty: Unifier = Unifier(Subst.empty, Nil)
}

object Inference {

  type Infer[A] = RWST[EitherT[Eval, TypeError, ?], TypeEnv, Set[Constraint], Unique, A]

  type Solve[A] = StateT[EitherT[Eval, TypeError, ?], Unifier, A]

  def addConstraint(t1: Type, t2: Type): Infer[Unit] =
    RWST.tell(Set(Constraint(t1, t2)))

  def inEnv[A](n: String, scheme: Scheme, inf: Infer[A]): Infer[A] =
    inf.local(_.updated(n, scheme))

  def closeOver(t: Type): Scheme =
    Substitutable.generalize((), t).normalized

  def runSolve(cs: List[Constraint]): Either[TypeError, Unifier] =
    solver.runS(Unifier(Subst.empty, cs)).value.value

  val fresh: Infer[Type] =
    for {
      u <- (RWST.get: Infer[Unique])
      u1 = u.next
      _ <- (RWST.set(u1): Infer[Unit])
    } yield Type.Var(s"anon${u.id}")

  def unifies(a: Type, b: Type): Solve[Unifier] =
    (a, b) match {
      case (left, right) if left == right =>
        Monad[Solve].pure(Unifier.empty)
      case (Type.Var(tvar), t) => bind(tvar, t)
      case (t, Type.Var(tvar)) => bind(tvar, t)
      case (Type.Arrow(fa, ta), Type.Arrow(fb, tb)) =>
        unifyMany(List(fa, ta), List(fb, tb))
      case (Type.TypeApply(fa, ta), Type.TypeApply(fb, tb)) =>
        unifyMany(List(fa, ta), List(fb, tb))
      case (faila, failb) =>
        MonadError[Solve, TypeError].raiseError(TypeError.UnificationFail(faila, failb))
    }

  // do a pointwise unification
  def unifyMany(as: List[Type], bs: List[Type]): Solve[Unifier] =
    (as, bs) match {
      case (Nil, Nil) => Monad[Solve].pure(Unifier.empty)
      case (ha :: ta, hb :: tb) =>
        for {
          sc1 <- unifies(ha, hb)
          Unifier(s1, c1) = sc1
          ta1 = Substitutable[List[Type]].apply(s1, ta)
          tb1 = Substitutable[List[Type]].apply(s1, tb)
          sc2 <- unifyMany(ta1, tb1)
          Unifier(s2, c2) = sc2
        } yield Unifier(s2.compose(s1), c1 reverse_::: c2)
      case (ma, mb) =>
        MonadError[Solve, TypeError].raiseError(TypeError.UnificationMismatch(ma, mb))

    }

  def bind(tvar: String, tpe: Type): Solve[Unifier] =
    tpe match {
      case Type.Var(v) if tvar == v =>
        Monad[Solve].pure(Unifier.empty)
      case t if Substitutable[Type].occurs(tvar, t) =>
        MonadError[Solve, TypeError].raiseError(TypeError.InfiniteType(tvar, t))
      case _ =>
        Monad[Solve].pure(Unifier(Subst(Map(tvar -> tpe)), Nil))
    }

  val solver: Solve[Subst] = {

    def step(unit: Unit): Solve[Either[Unit, Subst]] = {
      val u = StateT.get: Solve[Unifier]
      u.flatMap {
        case Unifier(sub, Nil) => Monad[Solve].pure(Right(sub))
        case Unifier(sub, Constraint(a, b) :: tail) =>
          for {
            su1 <- unifies(a, b)
            Unifier(s1, c1) = su1
            sub1 = s1.compose(sub)
            tail1 = Substitutable[List[Constraint]].apply(s1, tail)
            cs = c1 reverse_::: tail1
            newU = Unifier(sub1, cs)
            _ <- StateT.set(newU): Solve[Unit]
          } yield Left(unit) // use u so we don't get a warning... dammit
      }
    }

    Monad[Solve].tailRecM(())(step)
  }

  private def substFor(s: Scheme): Infer[Subst] =
    Traverse[List].traverse(s.vars)(_ => fresh)
      .map { ts =>
        Subst(s.vars.zip(ts).toMap)
      }

  def instantiate(s: Scheme): Infer[Type] =
    substFor(s).map { subst =>
      Substitutable[Type].apply(subst, s.result)
    }

  private def instantiateMatch[T](arg: Type, dt: DefinedType, branches: NonEmptyList[(ConstructorName, List[String], Expr[T])]): Infer[Type] = {
    def withBind(cn: ConstructorName, args: List[String], ts: List[Type], result: Expr[T]): Infer[Type] =
      if (args.size != ts.size) MonadError[Infer, TypeError].raiseError(TypeError.InsufficientPatternBind(cn, args, ts, dt))
      else {
        infer(result)
          .local { te: TypeEnv =>
            args.zip(ts.map(Scheme.fromType _)).foldLeft(te) { case (te, (varName, tpe)) =>
              te.updated(varName, tpe)
            }
          }
      }

    def inferBranch(mp: Map[ConstructorName, List[Type]], ce: (ConstructorName, List[String], Expr[T])): Infer[Type] = {
      // TODO make sure we have proven that this map-get is safe:
      val (cname, bindings, branchRes) = ce
      val tpes = mp(cname)
      withBind(cname, bindings, tpes, branchRes)
    }

    val scheme = dt.typeScheme
    substFor(scheme).flatMap { subst =>
      // TODO: we need to assert type annotations on each of the branches
      val cMap = dt.consMap(subst)

      val matchT = Substitutable[Type].apply(subst, scheme.result)

      def constrain(n: NonEmptyList[(ConstructorName, List[String], Expr[T])]): Infer[Type] =
        n.reduceLeftM(inferBranch(cMap, _)) { (tpe, tup) =>
          for {
            tpe1 <- inferBranch(cMap, tup)
            _ <- addConstraint(tpe, tpe1)
          } yield tpe1
        }

      for {
        branchT <- constrain(branches)
        _ <- addConstraint(arg, matchT)
      } yield branchT
    }
  }

  def inferExpr[T](expr: Expr[T]): Either[TypeError, Scheme] =
    inferExpr(TypeEnv.empty, expr)

  def inferExpr[T](te: TypeEnv, expr: Expr[T]): Either[TypeError, Scheme] = {
    // get the constraints
    val tcE = infer(expr)
      .run(te, Unique(0L))
      .map { case (s, _, a) => (a, s) }
      .value
      .value

    // now solve
    for {
      tc <- tcE
      (tpe, cons) = tc
      unif <- runSolve(cons.toList)
      Unifier(subs, cs) = unif
      st = Substitutable[Type].apply(subs, tpe)
    } yield closeOver(st)
  }

  def lookup(n: String): Infer[Type] = {
    val it: Infer[TypeEnv] = RWST.ask
    it.flatMap { te =>
      te.schemeOf(n) match {
        case None => MonadError[Infer, TypeError].raiseError(TypeError.Unbound(n))
        case Some(scheme) => instantiate(scheme)
      }
    }
  }

  /**
   * Infer the type and generalize all free variables
   */
  def inferScheme[T](ex: Expr[T]): Infer[Scheme] =
    for {
      env <- (RWST.ask: Infer[TypeEnv])
      // we need to see current constraits, since they are not free variables
      t1c <- infer(ex).transform { (l, s, a) => (l, s, (a, l)) }
      (t1, cons) = t1c
    } yield Substitutable.generalize((env, cons), t1)

  def infer[T](expr: Expr[T]): Infer[Type] =
    expr match {
      case Expr.Var(n, _) =>
        lookup(n)

      case Expr.Lambda(arg, e, _) =>
        for {
          tv <- fresh
          t <- inEnv(arg, Scheme.fromType(tv), infer(e))
        } yield Type.Arrow(tv, t)

      case Expr.Ffi(_, _, scheme, _) =>
        instantiate(scheme)

      case Expr.App(fn, arg, _) =>
        for {
          t1 <- infer(fn)
          t2 <- infer(arg)
          tv <- fresh
          _ <- addConstraint(t1, Type.Arrow(t2, tv))
        } yield tv

      case Expr.Let(n, ex, in, _) =>
        for {
          sc <- inferScheme(ex)
          t2 <- inEnv(n, sc, infer(in))
        } yield t2

      case Expr.Op(e1, op, e2, _) =>
        for {
          t1 <- infer(e1)
          t2 <- infer(e2)
          tv <- fresh
          u1 = Type.Arrow(t1, Type.Arrow(t2, tv))
          u2 = Operator.typeOf(op)
          _ <- addConstraint(u1, u2)
        } yield tv

      case Expr.Literal(Lit.Integer(_), _) => Monad[Infer].pure(Type.intT)
      case Expr.Literal(Lit.Bool(_), _) => Monad[Infer].pure(Type.boolT)
      case Expr.If(cond, te, fe, _) =>
        for {
          tc <- infer(cond)
          t1 <- infer(te)
          t2 <- infer(fe)
          _ <- addConstraint(Type.boolT, tc)
          _ <- addConstraint(t1, t2)
        } yield t2
      case Expr.Match(arg, branches, _) =>
        for {
          env <- (RWST.ask: Infer[TypeEnv])
          dt <- MonadError[Infer, TypeError].fromEither(env.getDefinedType(branches.map(_._1)))
          targ <- infer(arg)
          tbranch <- instantiateMatch(targ, dt, branches)
        } yield tbranch
    }
}

