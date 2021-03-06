name := "tile"

assemblyJarName in assembly := "tile-assembly.jar"

initialCommands in console := """
  |import com.rasterfoundry.tile.Config
  |import com.rasterfoundry.datamodel._
  |import io.circe._
  |import io.circe.syntax._
  |import java.util.UUID
  |import java.sql.Timestamp
  |import java.time.Instant
  |import scala.concurrent.{Future,Await}
  |import scala.concurrent.duration._
  |import akka.actor.ActorSystem
  |import akka.stream.ActorMaterializer
  |val publicOrgId = UUID.fromString("dfac6307-b5ef-43f7-beda-b9f208bb7726")
  |import geotrellis.vector.{MultiPolygon, Polygon, Point, Geometry, Projected}
""".trim.stripMargin
