package akka.minion

import akka.actor.{Actor, ActorLogging, Props}
import akka.minion.App.Settings
import akka.minion.GithubService.{CommitStatusConstants, FullReport, PullRequest}

import scala.collection.immutable.Seq

object Dashboard {

  def props(settings: Settings): Props = Props(new Dashboard(settings))

  // Main dashboard
  case object GetMainDashboard
  case class MainDashboardReply(report: Option[MainDashboardData])

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

  // Personal dashboard
  case class GetPersonalDashboard(person: String)
  case class PersonalDashboardReply(report: Option[PersonalDashboard])

  case class PersonalDashboard(
    person: String,
    repo: String,
    ownPrs: Seq[PersonalDashboardEntry],
    teamPrs: Seq[PersonalDashboardEntry],
    externalPrs: Seq[PersonalDashboardEntry]
  )
  case class PersonalDashboardEntry(pr: PullRequest, action: PrAction)

  sealed trait PrAction
  case object NoAction extends PrAction
  case object PleaseReview extends PrAction
  case object PleaseRebase extends PrAction
  case object PleaseResolve extends PrAction
  case object PleaseFix extends PrAction
}

class Dashboard(settings: Settings) extends Actor with ActorLogging {
  import akka.minion.Dashboard._

  private var lastFullReport: Option[FullReport] = None
  private var lastMainReport: Option[MainDashboardData] = None

  private var personalReports: Map[String, PersonalDashboard] = Map.empty

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
      lastFullReport = Some(report)
      lastMainReport = Some(createMainDashboard(report))
      personalReports = Map.empty

    case GetMainDashboard =>
      sender() ! MainDashboardReply(lastMainReport)

    case GetPersonalDashboard(person) =>
      if (personalReports.contains(person)) sender() ! PersonalDashboardReply(Some(personalReports(person)))
      else sender() ! PersonalDashboardReply(lastFullReport.map(createPersonalDashboard(person, _)))
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

  private def createPersonalDashboard(person: String, report: FullReport): PersonalDashboard = {
    log.info(s"Updating dashboard for $person")

    def actionFor(pr: PullRequest): PrAction = {
      if (
        pr.mergeable.getOrElse(true) &&
        !report.statuses(pr).statuses.exists(_.state == CommitStatusConstants.FAILURE) &&
        !report.reviews(pr).exists(_.user.login == person)
      ) PleaseReview
      else NoAction
    }

    val (ownPrs, rest) = report.pulls.partition(_.user.login == person)

    val (teamPrs, externalPrs) = report.pulls.partition { pr => settings.teamMembers(pr.user.login) }

    val ownActions = ownPrs.map { pr =>
      val action =
        if (!pr.mergeable.getOrElse(true)) PleaseRebase
        else if (report.reviews(pr).exists(_.changesRequested)) PleaseResolve
        else if (report.statuses(pr).statuses.exists(_.state == CommitStatusConstants.FAILURE)) PleaseFix
        else NoAction

      PersonalDashboardEntry(pr, action)
    }

    PersonalDashboard(
      person,
      report.repo.full_name,
      ownActions,
      teamPrs.map( pr => PersonalDashboardEntry(pr, actionFor(pr))),
      externalPrs.map( pr => PersonalDashboardEntry(pr, actionFor(pr)))
    )


  }

}
