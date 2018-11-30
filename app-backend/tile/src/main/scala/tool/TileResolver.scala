package com.rasterfoundry.tile.tool

import com.rasterfoundry.common.utils._
import com.rasterfoundry.tile.image._
import com.rasterfoundry.tile._
import com.rasterfoundry.tile.image.Mosaic
import com.rasterfoundry.tool.ast._
import com.rasterfoundry.tool.maml._
import com.azavea.maml.ast._
import com.azavea.maml.error._
import com.azavea.maml.eval._
import com.azavea.maml.eval.tile._
import com.azavea.maml.util.NeighborhoodConversion
import cats._
import cats.data.Validated._
import cats.data.{NonEmptyList => NEL, _}
import cats.implicits._
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import doobie.util.transactor.Transactor
import geotrellis.proj4.WebMercator
import geotrellis.raster._
import geotrellis.raster.resample._
import geotrellis.spark._
import geotrellis.spark.io._
import geotrellis.spark.io.s3._
import geotrellis.spark.tiling._
import geotrellis.vector.{Extent, MultiPolygon, Projected}
import geotrellis.spark.io.postgres.PostgresAttributeStore

import scala.util.{Failure, Success, Try}
import java.util.UUID
import java.net.URI

import scala.concurrent.{ExecutionContext, Future}

/** This interpreter handles resource resolution and compilation of MapAlgebra ASTs */
class TileResolver(xaa: Transactor[IO], ec: ExecutionContext)
    extends LazyLogging {

  implicit val execution: ExecutionContext = ec
  implicit val xa: Transactor[IO] = xaa

  val store = PostgresAttributeStore()

  val intNdTile = IntConstantTile(NODATA, 256, 256)

  def resolveBuffered(fullExp: Expression)
    : (Int, Int, Int) => Future[Interpreted[Expression]] = {

    def eval(exp: Expression,
             buffer: Int): (Int, Int, Int) => Future[Interpreted[Expression]] =
      (z: Int, x: Int, y: Int) => {
        lazy val extent = TileLayouts(z).mapTransform(SpatialKey(x, y))
        exp match {
          case RasterLit(ProjectRaster(projId, None, celltype)) =>
            Future.successful(
              Invalid(NEL.of(NonEvaluableNode(exp, Some("no band given")))))
          case RasterLit(ProjectRaster(projId, Some(band), celltype)) =>
            lazy val ndtile = celltype match {
              case Some(ct) => intNdTile.convert(ct)
              case None     => intNdTile
            }
            val futureSource = if (buffer > 0) {
              (for {
                tl <- Mosaic
                  .raw(projId, z, x - 1, y - 1)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                tm <- Mosaic
                  .raw(projId, z, x, y - 1)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                tr <- Mosaic
                  .raw(projId, z, x, y - 1)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                ml <- Mosaic
                  .raw(projId, z, x - 1, y)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                mm <- Mosaic
                  .raw(projId, z, x, y)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                mr <- Mosaic
                  .raw(projId, z, x + 1, y)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                bl <- Mosaic
                  .raw(projId, z, x - 1, y + 1)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                bm <- Mosaic
                  .raw(projId, z, x, y + 1)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
                br <- Mosaic
                  .raw(projId, z, x + 1, y + 1)
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
                  .orElse(OptionT.pure[Future](ndtile))
              } yield {
                TileWithNeighbors(
                  mm,
                  Some(NeighboringTiles(tl, tm, tr, ml, mr, bl, bm, br)))
                  .withBuffer(buffer)
              })
            } else {
              Mosaic
                .raw(projId, z, x, y)
                .map({ tile =>
                  tile.band(band).interpretAs(celltype.getOrElse(tile.cellType))
                })
            }

            futureSource.value.map({ maybeSource =>
              maybeSource match {
                case Some(tile) =>
                  Valid(RasterLit(Raster(tile, extent)))
                case None =>
                  Invalid(NEL.of(NonEvaluableNode(exp, None)))
              }
            })

          case RasterLit(CogRaster(_, None, celltype, location)) =>
            Future.successful(
              Invalid(NEL.of(NonEvaluableNode(exp, Some("no band given")))))
          case RasterLit(CogRaster(_, Some(band), celltype, location)) =>
            lazy val ndtile = celltype match {
              case Some(ct) => intNdTile.convert(ct)
              case None     => intNdTile
            }
            val futureSource =
              if (buffer > 0)
                (for {
                  tl <- LayerCache
                    .cogTile(location, z, SpatialKey(x - 1, y - 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  tm <- LayerCache
                    .cogTile(location, z, SpatialKey(x, y - 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  tr <- LayerCache
                    .cogTile(location, z, SpatialKey(x + 1, y - 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  ml <- LayerCache
                    .cogTile(location, z, SpatialKey(x - 1, y))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  mm <- LayerCache
                    .cogTile(location, z, SpatialKey(x, y))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                  mr <- LayerCache
                    .cogTile(location, z, SpatialKey(x + 1, y))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  bl <- LayerCache
                    .cogTile(location, z, SpatialKey(x - 1, y + 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  bm <- LayerCache
                    .cogTile(location, z, SpatialKey(x, y + 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  br <- LayerCache
                    .cogTile(location, z, SpatialKey(x + 1, y + 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                } yield {
                  TileWithNeighbors(
                    mm,
                    Some(NeighboringTiles(tl, tm, tr, ml, mr, bl, bm, br)))
                    .withBuffer(buffer)
                })
              else
                LayerCache
                  .cogTile(location, z, SpatialKey(x, y))
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
            futureSource.value.map({ maybeTile =>
              maybeTile match {
                case Some(tile) =>
                  Valid(RasterLit(Raster(tile, extent)))
                case None =>
                  Invalid(NEL.of(NonEvaluableNode(exp, None)))
              }
            })

          case RasterLit(SceneRaster(sceneId, None, celltype, _)) =>
            Future.successful(
              Invalid(NEL.of(NonEvaluableNode(exp, Some("no band given")))))
          case RasterLit(SceneRaster(sceneId, Some(band), celltype, _)) =>
            lazy val ndtile = celltype match {
              case Some(ct) => intNdTile.convert(ct)
              case None     => intNdTile
            }
            val futureSource =
              if (buffer > 0)
                (for {
                  tl <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x - 1, y - 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  tm <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x, y - 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  tr <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x + 1, y - 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  ml <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x - 1, y))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  mm <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x, y))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                  mr <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x + 1, y))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  bl <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x - 1, y + 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  bm <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x, y + 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                  br <- LayerCache
                    .layerTile(sceneId, z, SpatialKey(x + 1, y + 1))
                    .map({ tile =>
                      tile
                        .band(band)
                        .interpretAs(celltype.getOrElse(tile.cellType))
                    })
                    .orElse(OptionT.pure[Future](ndtile))
                } yield {
                  TileWithNeighbors(
                    mm,
                    Some(NeighboringTiles(tl, tm, tr, ml, mr, bl, bm, br)))
                    .withBuffer(buffer)
                })
              else
                LayerCache
                  .layerTile(sceneId, z, SpatialKey(x, y))
                  .map({ tile =>
                    tile
                      .band(band)
                      .interpretAs(celltype.getOrElse(tile.cellType))
                  })
            futureSource.value.map({ maybeTile =>
              maybeTile match {
                case Some(tile) =>
                  Valid(RasterLit(Raster(tile, extent)))
                case None =>
                  Invalid(NEL.of(NonEvaluableNode(exp, None)))
              }
            })

          case f: FocalExpression =>
            exp.children
              .map({ child =>
                eval(child,
                     buffer + NeighborhoodConversion(f.neighborhood).extent)(z,
                                                                             x,
                                                                             y)
              })
              .toList
              .sequence
              .map({ futureValidChildren =>
                futureValidChildren.toList.sequence
              })
              .map({ children =>
                children.map({ exp.withChildren(_) })
              })
          case _ =>
            exp.children
              .map({ child =>
                eval(child, buffer)(z, x, y)
              })
              .toList
              .sequence
              .map({ futureValidChildren =>
                futureValidChildren.toList.sequence
              })
              .map({ children =>
                children.map({ exp.withChildren(_) })
              })
        }
      }
    eval(fullExp, 0)
  }

  def resolveForExtent(fullExp: Expression,
                       zoom: Int,
                       extent: Extent): Future[Interpreted[Expression]] = {
    fullExp match {
      case RasterLit(SceneRaster(sceneId, None, celltype, _)) =>
        Future.successful(
          Invalid(NEL.of(NonEvaluableNode(fullExp, Some("no band given")))))
      case RasterLit(SceneRaster(sceneId, Some(band), celltype, _)) =>
        Future.successful({
          Try {
            val layerId = LayerId(sceneId.toString, zoom)

            S3CollectionLayerReader(store)
              .query[SpatialKey, MultibandTile, TileLayerMetadata[SpatialKey]](
                layerId)
              .result
              .stitch
              .crop(extent)
              .tile
          } match {
            case Success(tile) =>
              val t =
                tile.band(band).interpretAs(celltype.getOrElse(tile.cellType))
              Valid(RasterLit(Raster(t, extent)))
            case Failure(e) =>
              Invalid(NEL.of(NonEvaluableNode(fullExp, None)))
          }
        })

      case RasterLit(ProjectRaster(projId, None, celltype)) =>
        Future.successful(
          Invalid(NEL.of(NonEvaluableNode(fullExp, Some("no band given")))))
      case RasterLit(ProjectRaster(projId, Some(band), celltype)) =>
        Mosaic
          .rawForExtent(projId,
                        zoom,
                        Some(Projected(extent.toPolygon, 3857)),
                        Some(band))
          .value
          .map({ maybeTile =>
            {
              maybeTile match {
                case Some(tile) =>
                  val t =
                    tile.interpretAs(celltype.getOrElse(tile.cellType))
                  Valid(RasterLit(Raster(t.band(0), extent)))
                case None =>
                  Invalid(NEL.of(NonEvaluableNode(fullExp, None)))
              }
            }
          })

      case RasterLit(CogRaster(_, None, celltype, location)) =>
        Future.successful(
          Invalid(NEL.of(NonEvaluableNode(fullExp, Some("no band given")))))

      case RasterLit(CogRaster(_, Some(band), celltype, location)) =>
        CogUtils
          .fromUri(location)
          .map(_.tiff)
          .flatMap { tiff =>
            CogUtils.cropForZoomExtent(tiff, zoom, Some(extent)).map {
              tile: MultibandTile =>
                tile.band(band).interpretAs(celltype.getOrElse(tile.cellType))
            }
          }
          .value
          .map({ maybeTile =>
            {
              val resampled = maybeTile map { tile =>
                tile.resample(256, 256, Average)
              }
              resampled match {
                case Some(tile) =>
                  Valid(RasterLit(Raster(tile, extent)))
                case None =>
                  Invalid(NEL.of(NonEvaluableNode(fullExp, None)))
              }
            }
          })

      case _ =>
        fullExp.children
          .map({ child =>
            resolveForExtent(child, zoom, extent)
          })
          .toList
          .sequence
          .map({ futureValidChildren =>
            futureValidChildren.toList.sequence
          })
          .map({ children =>
            children.map({ fullExp.withChildren(_) })
          })
    }
  }
}
