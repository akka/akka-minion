package akka.minion

import java.time.{Duration, ZonedDateTime}

import akka.actor.{Actor, ActorLogging, Props}
import akka.minion.App.Settings
import akka.minion.GithubService.{
  CommitStatusConstants,
  FullReport,
  IssueComment,
  Label,
  PullRequest,
  PullRequestReview
}

import scala.collection.immutable.Seq

object Dashboard {
  def props(settings: Settings): Props = Props(new Dashboard(settings))

  // Main dashboard
  sealed trait Action {
    def importance: Int
  }
  object Action {
    case object Commented extends Action { override def importance = 2 }
    case object Approved extends Action { override def importance = 4 }
    case object RequestedChanges extends Action { override def importance = 3 }
    case object OpenedPr extends Action { override def importance = 1 }
    case object Dismissed extends Action { override def importance = 0 }

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
  case class Repo(name: String, fullName: String) {
    def denotes(fullName: String): Boolean = fullName == this.fullName
  }

  case class ApiUsageStats(limit: Int, remaining: Int, resetsIn: String)

  case object GetMainDashboard
  case class MainDashboardReply(report: Option[MainDashboardData])

  case class MainDashboardData(
      pulls: Iterable[MainDashboardEntry],
      usageStats: ApiUsageStats
  ) {
    def filterRepos(keep: Set[String]): MainDashboardData =
      copy(pulls = pulls.filter(entry => keep.exists(r => entry.isRepo(r))))
  }

  case class MainDashboardEntry(
      repo: Repo,
      author: Person,
      number: Int,
      title: String,
      labels: List[Label],
      lastUpdated: String,
      people: Set[Performance],
      lastActor: Performance,
      mergeable: Option[Boolean],
      status: PrValidationStatus,
      reviewedOk: Int,
      reviewedReject: Int
  ) {
    def isRepo(name: String) = repo.denotes(name)
  }

  // Personal dashboard
  case class GetPersonalDashboard(person: String)
  case class PersonalDashboardReply(report: Option[PersonalDashboard])

  case class PersonalDashboard(
      person: String,
      ownPrs: Iterable[PersonalDashboardEntry],
      teamPrs: Iterable[PersonalDashboardEntry],
      externalPrs: Iterable[PersonalDashboardEntry]
  )
  case class PersonalDashboardEntry(repo: Repo, pr: PullRequest, action: PrAction)

  sealed trait PrAction
  case object NoAction extends PrAction
  case object PleaseReview extends PrAction
  case object PleaseRebase extends PrAction
  case object PleaseResolve extends PrAction
  case object PleaseFix extends PrAction

  implicit class ZonedDateTimeOps(datetime: ZonedDateTime) {
    def ending(value: Long, suffix: String) =
      s"$value $suffix${if (value > 1) "s" else ""} ago"

    def prettyAgo: String =
      Duration.between(datetime, ZonedDateTime.now) match {
        case d if d.toMinutes < 60 => ending(d.toMinutes, "minute")
        case d if d.toHours < 24 => ending(d.toHours, "hour")
        case d if d.toDays < 31 => ending(d.toDays, "day")
        case d => ending(d.toDays / 30, "month")
      }

    def prettyIn: String =
      Duration.between(ZonedDateTime.now, datetime) match {
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

  override def preStart(): Unit =
    log.info("Dashboard started")

  override def postStop(): Unit =
    log.info("Dashboard stopped")

  override def receive: Receive = {
    case App.ServicePing =>
      sender() ! App.ServicePong

    case report: FullReport =>
      log.info(
        s"Received fresh report for ${report.pulls.keys.map(_.name)}. Remaining API quota: ${report.usageStats.rate.remaining}"
      )
      lastFullReport = Some(report)
      lastMainReport = Some(createMainDashboard(report))
      personalReports = Map.empty

    case GetMainDashboard =>
      sender() ! MainDashboardReply(lastMainReport)

    case GetPersonalDashboard(person) =>
      if (personalReports.contains(person))
        sender() ! PersonalDashboardReply(Some(personalReports(person)))
      else
        sender() ! PersonalDashboardReply(lastFullReport.map(createPersonalDashboard(person, _)))
  }

  private def createMainDashboard(report: FullReport): MainDashboardData = {
    val entries = report.pulls
      .flatMap {
        case (repo, pulls) =>
          pulls
            .filter(_.assignee.forall(assignee => settings.teamMembers.contains(assignee.login)))
            .map { pull =>
              val comments = report.comments(pull)
              val status = report.statuses(pull)
              val reviews = report.reviews(pull)

              val commenters = comments.collect {
                case IssueComment(_, Some(user), Some(created), _, _) if !settings.bots(user.login) =>
                  Performance(Person(user.login, user.avatar_url), Action.Commented, created)
              }

              val reviewers = reviews.collect {
                case review @ PullRequestReview(user, _, Some(created), _) if !settings.bots(user.login) =>
                  Performance(Person(user.login, user.avatar_url), Action.toAction(review), created)
              }

              val involvedPeople = (commenters ++ reviewers).toSeq
                .groupBy(_.person.login)
                .map {
                  case (_, performances) =>
                    performances.maxBy(_.action.importance)
                }
                .toSet

              def lastPerformance = {
                val creation =
                  Performance(Person(pull.user.login, pull.user.avatar_url), Action.OpenedPr, pull.created_at.get)
                val lastComment = commenters.lastOption
                val lastReview = reviewers.lastOption

                (Seq(creation) ++ lastComment ++ lastReview).maxBy(_.performedAt.toInstant)
              }

              MainDashboardEntry(
                repo = Repo(repo.name, repo.full_name),
                author = Person(pull.user.login, pull.user.avatar_url),
                number = pull.number,
                title = pull.title,
                labels = pull.labels,
                lastUpdated = pull.updated_at.fold("long time ago...")(_.prettyAgo),
                people = involvedPeople,
                lastActor = lastPerformance,
                mergeable = pull.mergeable,
                status =
                  if (status.statuses.exists(_.state == CommitStatusConstants.PENDING))
                    PrValidationStatus.Pending
                  else if (status.statuses.exists(_.state == CommitStatusConstants.FAILURE))
                    PrValidationStatus.Failure
                  else PrValidationStatus.Success,
                reviewedOk = involvedPeople.count(_.action == Action.Approved),
                reviewedReject = involvedPeople.count(_.action == Action.RequestedChanges)
              )
            }
      }
      .toSeq
      .sortBy(_.lastActor.performedAt)(Ordering.fromLessThan(_ isBefore _))

    MainDashboardData(
      entries,
      ApiUsageStats(
        report.usageStats.rate.limit,
        report.usageStats.rate.remaining,
        report.usageStats.rate.reset.prettyIn
      )
    )
  }

  private def createPersonalDashboard(person: String, report: FullReport): PersonalDashboard = {
    log.info(s"Updating dashboard for $person")

    def actionFor(pr: PullRequest): PrAction =
      if (pr.mergeable.getOrElse(true) &&
          !report
            .statuses(pr)
            .statuses
            .exists(_.state == CommitStatusConstants.FAILURE) &&
          !report.reviews(pr).exists(_.user.login == person)) PleaseReview
      else NoAction

    val (ownActions, teamActions, externalActions) = report.pulls
      .map {
        case (repo, pulls) =>
          val (ownPrs, rest) = pulls.partition(_.user.login == person)

          val (teamPrs, externalPrs) = rest.partition(pr => settings.teamMembers(pr.user.login))

          val ownEntries = ownPrs.map { pr =>
            val action =
              if (!pr.mergeable.getOrElse(true)) PleaseRebase
              else if (report.reviews(pr).exists(_.changesRequested)) PleaseResolve
              else if (report
                         .statuses(pr)
                         .statuses
                         .exists(_.state == CommitStatusConstants.FAILURE)) PleaseFix
              else NoAction

            PersonalDashboardEntry(Repo(repo.name, repo.full_name), pr, action)
          }

          val teamEntries =
            teamPrs.map(pr => PersonalDashboardEntry(Repo(repo.name, repo.full_name), pr, actionFor(pr)))
          val externalEntries =
            externalPrs.map(pr => PersonalDashboardEntry(Repo(repo.name, repo.full_name), pr, actionFor(pr)))

          (ownEntries, teamEntries, externalEntries)
      }
      .reduce((report1, report2) => (report1._1 ++ report2._1, report1._2 ++ report1._2, report1._3 ++ report2._3))

    PersonalDashboard(
      person,
      ownActions,
      teamActions,
      externalActions
    )
  }
}
