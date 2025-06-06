package com.chollinger.bridgefour.spren.http

import cats.data.Kleisli
import cats.effect.Async
import cats.effect.Resource
import cats.syntax.all._
import cats.Monad
import cats.Parallel
import com.chollinger.bridgefour.shared.background.BackgroundWorker.FiberContainer
import com.chollinger.bridgefour.shared.background.BackgroundWorker
import com.chollinger.bridgefour.shared.background.BackgroundWorkerService
import com.chollinger.bridgefour.shared.jobs.BridgeFourJobCreatorService
import com.chollinger.bridgefour.shared.models.IDs.TaskId
import com.chollinger.bridgefour.shared.models.Job.BackgroundTaskState
import com.chollinger.bridgefour.shared.persistence.InMemoryPersistence
import com.chollinger.bridgefour.spren.models.Config
import com.chollinger.bridgefour.spren.models.Config.ServiceConfig
import com.chollinger.bridgefour.spren.programs.TaskExecutorService
import com.chollinger.bridgefour.spren.services.WorkerService
import com.comcast.ip4s._
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.{Logger => Http4sLogger}
import org.http4s.HttpApp
import org.http4s.Request
import org.http4s.Response
import org.typelevel.log4cats.Logger
object Server {

  def run[F[_]: Async: Parallel: Network: Logger](cfg: ServiceConfig): F[Nothing] = {
    val mF = implicitly[Monad[F]]
    for {
//      client    <- EmberClientBuilder.default[F].build
      state <-
        Resource.make(InMemoryPersistence.makeF[F, Long, FiberContainer[F, BackgroundTaskState, TaskId]]())(_ =>
          mF.unit
        )
      bgSrv     = BackgroundWorkerService.make[F, BackgroundTaskState, TaskId](state)
      jcSrv     = BridgeFourJobCreatorService.make[F]()
      execSrv   = TaskExecutorService.make[F](cfg.self, bgSrv, jcSrv)
      workerSrv = WorkerService.make[F](cfg.self, execSrv)
      httpApp: Kleisli[F, Request[F], Response[F]] = (
                                                       TaskRoutes[F](cfg.self, execSrv).routes <+>
                                                         WorkerRoutes[F](workerSrv).routes
                                                     ).orNotFound

      // With Middlewares in place
      finalHttpApp: HttpApp[F] = Http4sLogger.httpApp(true, true)(httpApp)

      _ <- EmberServerBuilder
             .default[F]
             // TODO: scala3 + pureconfig = no ip4s types in config?
             .withHost(Host.fromString(cfg.self.host).get)
             .withPort(Port.fromInt(cfg.self.port).get)
             .withHttpApp(finalHttpApp)
             .build
    } yield ()
  }.useForever

}
