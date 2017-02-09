package akka.minion

import akka.actor.{Actor, ActorLogging, Props}
import akka.minion.App.Settings
import akka.minion.GithubService.{CommitStatusConstants, FullReport}

import scala.collection.immutable.Seq

object Dashboard {

  def props(settings: Settings): Props = Props(new Dashboard(settings))

  case object GetMainDashboard
  case class MainDashboardReply(report: Option[MainDashboardData])

  // Main dashboard
  case class MainDashboardData(
    repo: String,
    pulls: Seq[MainDashboardEntry]
  )


  case class MainDashboardEntry(
    author: String,
    number: Int,
    title: String,
    lastUpdated: String,
    people: Set[String],
    lastActor: String,
    mergeable: Option[Boolean],
    statusOk: Option[Boolean],
    reviewedOk: Int,
    reviewedReject: Int
  )

}

class Dashboard(settings: Settings) extends Actor with ActorLogging {
  import akka.minion.Dashboard._

  private var lastMainReport: Option[MainDashboardData] = None

  override def preStart(): Unit = {
    log.info("Dashboard started")
  }

  override def postStop(): Unit = {
    log.info("Dashboard stopped")
  }

  override def receive: Receive = {
    case App.ServicePing =>
      sender() ! App.ServicePong

    case report: FullReport =>
      log.info(s"Received fresh report for ${report.repo}")
      lastMainReport = Some(createMainDashboard(report))

    case GetMainDashboard =>
      sender() ! MainDashboardReply(lastMainReport)
  }



  private def createMainDashboard(report: FullReport): MainDashboardData = {
    val entries = report.pulls.map { pull =>
      val comments = report.comments(pull)
      val status = report.statuses(pull)

      MainDashboardEntry(
        author = pull.user.login,
        number = pull.number,
        title = pull.title,
        lastUpdated = comments.last.updated_at.get.fold(_.toString, _.toString),
        people = comments.iterator.map(_.user).collect {
          case Some(user) if !settings.bots(user.login) => user.login
        }.toSet,
        lastActor = comments.map(_.user).collect {
          case Some(user) if !settings.bots(user.login) => user.login
        }.lastOption.getOrElse(""),
        mergeable = pull.mergeable,
        statusOk =
          if (status.statuses.isEmpty) None
          else Some(!status.statuses.exists(_.state == CommitStatusConstants.FAILURE)),
        reviewedOk = status.statuses.count(_.state == CommitStatusConstants.REVIEWED),
        reviewedReject = 0
      )

    }

    MainDashboardData(report.repo.full_name, entries)
  }

}
