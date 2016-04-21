package grid

import java.util.concurrent.Executors

import grid.cluster.CrashSimulator
import grid.discovery.Repository
import grid.gridScheduler.{GridScheduler, IGridScheduler}
import grid.resourceManager.{IResourceManager, ResourceManager}
import grid.user.User

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object Main {

  import Repository._

  def main(args: Array[String]) {

    val url: String = args(0)
    val manifest = Manifest.fromFileName(url)
    val repoPath = manifest.repositoryPath

    val timer = Executors.newScheduledThreadPool(4)

    val rms = ListBuffer[IResourceManager]()
    val gss = ListBuffer[IGridScheduler]()

    manifest.gsIds.foreach(gsId => {
      val gs = new GridScheduler(
        gsId,
        rmRepo(repoPath + "/rms"),
        gsRepo(repoPath + "/gss")
      )

      gss += gs
    })

    manifest.rmIds.foreach(rmId => {
      val rm = new ResourceManager(
        rmId,
        manifest.nodesPerRm,
        userRepo(repoPath + "/users"),
        rmRepo(repoPath + "/rms"),
        gsRepo(repoPath + "/gss")
      )

      rms += rm
    })

    manifest.userIds.foreach(userId => {
      val user = new User(
        userId,
        userRepo(repoPath + "/users"),
        rmRepo(repoPath + "/rms")
      )

      rmRepo(repoPath + "/rms").ids.foreach { rmId =>
        Future {
          user.createJobs(rmId, 500, 2000)
        }
      }
    })

  }
}
