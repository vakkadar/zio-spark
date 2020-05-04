package zio.spark

import zio._
import zio.test._

object SparkEnvImplicitClassTest extends DefaultRunnableSpec {

  val sparkZIO: Task[SparkZIO] = ss
  val pathToto: String         = "src/test/resources/toto"

  import zio.test._

  override def spec: ZSpec[zio.test.environment.TestEnvironment, Any] = zio.test.suite("SparkEnv")(
    testM("sparkEven read and SparkEnv sql example") {
      val prg: ZIO[SparkEnv, Throwable, TestResult] = for {
        df  <- SparkEnv.read.textFile(pathToto)
        _   <- Task(df.createTempView("totoview"))
        df2 <- SparkEnv.sql(s"""SELECT * FROM totoview""")
      } yield assert(df.collect().toSeq)(zio.test.Assertion.equalTo(df2.collect().toSeq))

      sparkZIO.flatMap(prg.provide)
    } @@ max20secondes,
    testM("toDataSet") {
      import SparkEnv.implicits._

      val prg = for {

        df <- SparkEnv.read.textFile(pathToto)
        ds <- Task(df.as[String])
        v  <- Task(ds.take(1)(0))
      } yield {
        assert(v.trim)(Assertion.equalTo("bonjour"))
      }

      sparkZIO.flatMap(prg.provide)

    } @@ max20secondes
  )

}