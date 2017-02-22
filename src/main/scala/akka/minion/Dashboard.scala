package akka.minion

import java.time.{Duration, ZonedDateTime}

import akka.actor.{Actor, ActorLogging, Props}
import akka.minion.App.Settings
import akka.minion.GithubService.{CommitStatusConstants, FullReport, IssueComment, PullRequest, PullRequestReview}

import scala.collection.immutable.Seq

object Dashboard {

  def props(settings: Settings): Props = Props(new Dashboard(settings))

  // Main dashboard
  sealed trait Action
  object Action {
    case object Commented extends Action
    case object Approved extends Action
    case object RequestedChanges extends Action
    case object OpenedPr extends Action
    case object Dismissed extends Action

    def toAction(review: PullRequestReview): Action = review match {
      case r if r.commented => Action.Commented
      case r if r.approved => Action.Approved
      case r if r.changesRequested => Action.RequestedChanges
      case r if r.dismissed => Action.Dismissed
    }
  }

  sealed trait PrValidationStatus
  object PrValidationStatus {
    case object Success extends PrValidationStatus
    case object Pending extends PrValidationStatus
    case object Failure extends PrValidationStatus
  }

  case class Person(login: String, avatarUrl: String)
  case class Performance(person: Person, action: Action, performedAt: ZonedDateTime)

  case class ApiUsageStats(limit: Int, remaining: Int, resetsIn: String)

  case object GetMainDashboard
  case class MainDashboardReply(report: Option[MainDashboardData])

  case class MainDashboardData(
    repo: String,
    pulls: Seq[MainDashboardEntry],
    usageStats: ApiUsageStats
  )


  case class MainDashboardEntry(
    author: Person,
    number: Int,
    title: String,
    lastUpdated: String,
    people: Set[Performance],
    lastActor: Performance,
    mergeable: Option[Boolean],
    status: PrValidationStatus,
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

  implicit class ZonedDateTimeOps(datetime: ZonedDateTime) {
    def prettyAgo: String = Duration.between(datetime, ZonedDateTime.now) match {
      case d if d.toMinutes < 60 => s"${d.toMinutes} minutes ago"
      case d if d.toHours < 24 => s"${d.toHours} hours ago"
      case d if d.toDays < 31 => s"${d.toDays} days ago"
      case d => s"months ago"
    }

    def prettyIn: String = Duration.between(ZonedDateTime.now, datetime) match {
      case d if d.toMinutes < 60 => s"in ${d.toMinutes} minutes"
      case d => s"in more than an hour"
    }
  }
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
      log.info(s"Received fresh report for ${report.repo}. Remaining API quota: ${report.usageStats.rate.remaining}")
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
      val reviews = report.reviews(pull)

      val commenters = comments.collect {
        case IssueComment(_, Some(user), Some(created), _, _) if !settings.bots(user.login) =>
          Performance(Person(user.login, user.avatar_url), Action.Commented, created)
      }

      val reviewers = reviews.collect {
        case review@PullRequestReview(user, _, Some(created), _) if !settings.bots(user.login) =>
          Performance(Person(user.login, user.avatar_url), Action.toAction(review), created)
      }

      val involvedPeople = (commenters ++ reviewers).toSeq.groupBy(_.person.login).map {
        case (_, performances) =>
          // pr approval/rejection is more important than comments
          performances
            .find(p => Seq(Action.Approved, Action.RequestedChanges).contains(p.action))
            .getOrElse(performances.head)
        }.toSet

      def lastPerformance = {
        val creation = Performance(Person(pull.user.login, pull.user.avatar_url), Action.OpenedPr, pull.created_at.get)
        val lastComment = commenters.lastOption
        val lastReview = reviewers.lastOption

        (Seq(creation) ++ lastComment ++ lastReview).sortBy(_.performedAt.toInstant).last
      }

      MainDashboardEntry(
        author = Person(pull.user.login, pull.user.avatar_url),
        number = pull.number,
        title = pull.title,
        lastUpdated = pull.updated_at.fold("long time ago...")(_.prettyAgo),
        people = involvedPeople,
        lastActor = lastPerformance,
        mergeable = pull.mergeable,
        status =
          if (status.statuses.exists(_.state == CommitStatusConstants.PENDING)) PrValidationStatus.Pending
          else if (status.statuses.exists(_.state == CommitStatusConstants.FAILURE)) PrValidationStatus.Failure
          else PrValidationStatus.Success,
        reviewedOk = reviews.count(_.approved),
        reviewedReject = reviews.count(_.changesRequested)
      )

    }

    MainDashboardData(
      report.repo.full_name,
      entries,
      ApiUsageStats(
        report.usageStats.rate.limit,
        report.usageStats.rate.remaining,
        report.usageStats.rate.reset.prettyIn))
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

    val (teamPrs, externalPrs) = rest.partition { pr => settings.teamMembers(pr.user.login) }

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
