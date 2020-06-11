package zio.spark.wrap

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import zio._
import zio.spark._
import zio.spark.wrap.Wrap.Aux

import scala.util.Try

trait Wrap[A] {
  type Out

  @inline
  def apply(a: A): Out
}

sealed trait LowLowPriorityWrap {
  final implicit def _any[T]: Wrap.Aux[T, ZWrap[T]] = new Wrap[T] {
    override type Out = ZWrap[T]
    override def apply(a: T): Out = new ZWrap[T](a) {}
  }
}

sealed trait LowPriorityWrap extends LowLowPriorityWrap {
  final implicit def _dataset[T]: Aux[Dataset[T], ZDataset[T]] = Wrap.zwrap(ds => new ZDataset(ds))
}

object Wrap extends LowPriorityWrap {
  type Aux[A, B] = Wrap[A] {
    type Out = B
  }

  type NoWrap[T] = Aux[T, T]

  final protected[spark] def noWrap[T]: NoWrap[T] = new Wrap[T] {
    override type Out = T

    @inline
    override def apply(a: T): Out = a
  }

  final protected[spark] def zwrap[A, B <: ZWrap[_]](f: A => B): Aux[A, B] = new Wrap[A] {
    override type Out = B
    override def apply(a: A): Out = f(a)
  }

  implicit val _string: NoWrap[String]            = noWrap
  implicit val _int: NoWrap[Int]                  = noWrap
  implicit val _long: NoWrap[Long]                = noWrap
  implicit val _unit: NoWrap[Unit]                = noWrap
  implicit val _boolean: NoWrap[Boolean]          = noWrap
  implicit val _row: NoWrap[Row]                  = noWrap
  implicit val _column: NoWrap[Column]            = noWrap
  implicit def _wrapped[T <: ZWrap[_]]: NoWrap[T] = noWrap

  implicit def _rdd[T]: Aux[RDD[T], ZRDD[T]]                   = zwrap(rdd => new ZRDD(rdd))
  implicit val _dataframe: Aux[DataFrame, ZDataFrame]          = zwrap(df => new ZDataFrame(df))
  implicit val _sparkSession: Aux[SparkSession, ZSparkSession] = zwrap(ss => new ZSparkSession(ss))
  implicit val _sparkContext: Aux[SparkContext, ZSparkContext] = zwrap(sc => new ZSparkContext(sc))
  implicit val _relationalgroupeddataset: Aux[RelationalGroupedDataset, ZRelationalGroupedDataset] = zwrap(
    rgd => new ZRelationalGroupedDataset(rgd)
  )

  implicit def _seq[A, B](implicit W: Aux[A, B]): Aux[Seq[A], Seq[B]] = new Wrap[Seq[A]] {
    override type Out = Seq[B]
    override def apply(a: Seq[A]): Out = a.map(W.apply)
  }

  implicit def _option[A, B](implicit W: Aux[A, B]): Aux[Option[A], Option[B]] = new Wrap[Option[A]] {
    override type Out = Option[B]
    override def apply(a: Option[A]): Out = a.map(W.apply)
  }

  def apply[A, B](a: A)(implicit W: Wrap[A]): W.Out = W(a)

  def effect[A, B](a: => A)(implicit W: Wrap[A]): Task[W.Out] = Task(W(a))
}

abstract class ZWrap[+Impure](private val value: Impure) {

  /** ...
   * ...
   *
   * @usecase def execute[B](f: V => B):Task[B]
   */
  final def execute[B, C](f: Impure => B)(implicit W: Wrap.Aux[B, C]): Task[C] = Task(W(f(value)))

  final def executeM[R, B, C](f: Impure => RIO[R, B])(implicit W: Wrap.Aux[B, C]): RIO[R, C] =
    Task(f(value).map(W.apply)).flatten

  final protected def executeTotal[B, C](f: Impure => B)(implicit W: Wrap.Aux[B, C]): UIO[C] = UIO(W(f(value)))

  final protected def executeTotalM[R, E, B, C](f: Impure => ZIO[R, E, B])(implicit W: Wrap.Aux[B, C]): ZIO[R, E, C] =
    f(value).map(W.apply)

  final protected def nowTotal[B, C](f: Impure => B)(implicit W: Wrap.Aux[B, C]): C = W(f(value))

  final protected def now[B, C](f: Impure => B)(implicit W: Wrap.Aux[B, C]): Try[C] = Try(W(f(value)))
}

abstract class ZWrapFImpure[-R, Impure](rio: RIO[R, ZWrap[Impure]]) {
  protected def makeChain[Self](create: RIO[R, ZWrap[Impure]] => Self): (Impure => Impure) => Self =
    f => create(execute(f))

  final def execute[B, Pure](f: Impure => B)(implicit W: Wrap.Aux[B, Pure]): RIO[R, Pure] = rio >>= (_.execute(f))
}

abstract class ZWrapF[-R, +A](rio: RIO[R, A]) {
  def execute[B](f: A => B): RIO[R, B]                     = rio map f
  def executeM[R1 <: R, B](f: A => RIO[R1, B]): RIO[R1, B] = rio >>= f
}
