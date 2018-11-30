package com.rasterfoundry.api.config

import akka.http.scaladsl.server.Route
import cats.effect.IO
import com.rasterfoundry.api.Codec._
import com.rasterfoundry.authentication.Authentication
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import doobie.util.transactor.Transactor
import io.circe._
import io.circe.generic.auto._
import doobie._
import doobie.implicits._
import doobie.Fragments.in
import doobie.postgres._
import doobie.postgres.implicits._

trait ConfigRoutes extends Authentication {
  val xa: Transactor[IO]
  val configRoutes: Route = {
    pathEndOrSingleSlash {
      get {
        complete {
          AngularConfigService.getConfig().transact(xa).unsafeToFuture
        }
      }
    }
  }
}
