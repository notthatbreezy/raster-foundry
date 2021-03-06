name := "database"

initialCommands in console :=
  """
import com.rasterfoundry.database._
import com.rasterfoundry.database.Implicits._

import doobie._, doobie.implicits._
import doobie.hikari._, doobie.hikari.implicits._
import doobie.postgres._, doobie.postgres.implicits._
import cats._, cats.data._, cats.effect.IO, cats.implicits._
import com.rasterfoundry.datamodel._
import doobie.util.log.LogHandler
import java.util.UUID

implicit val han = LogHandler({ e => println("*** " + e) })

import scala.concurrent.ExecutionContext

implicit val cs = IO.contextShift(ExecutionContext.global)

// implicit transactor for console testing

val xa = Transactor.fromDriverManager[IO](
  "org.postgresql.Driver",
  "jdbc:postgresql://database.service.rasterfoundry.internal/",
  "rasterfoundry",
  "rasterfoundry"
)

val y = xa.yolo
import y._
"""

testOptions in Test += Tests.Argument("-oD")
