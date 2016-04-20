package grid

import grid.discovery.Repository
import grid.gridScheduler.GridScheduler
import grid.resourceManager.ResourceManager
import grid.user.User

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object RunJob extends App {

  import Repository._

  val gs0 = new GridScheduler(
    0,
    rmRepo,
    gsRepo
  )

  val gs1 = new GridScheduler(
    1,
    rmRepo,
    gsRepo
  )

  val gs2 = new GridScheduler(
    2,
    rmRepo,
    gsRepo
  )

  val gs3 = new GridScheduler(
    3,
    rmRepo,
    gsRepo
  )

  val rm0 = new ResourceManager(
    0,
    1000,
    userRepo,
    rmRepo,
    gsRepo
  )

  val rm1 = new ResourceManager(
    1,
    1000,
    userRepo,
    rmRepo,
    gsRepo
  )

  val user = new User(
    0,
    userRepo,
    rmRepo
  )

  Future { user.createJobs(0, 5000, 3000) }

  Thread.sleep(300)

  rm0.shutDown()
}
