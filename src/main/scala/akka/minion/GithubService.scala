package akka.minion

import java.time.{Instant, ZoneOffset, ZonedDateTime}

import akka.Done
import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`If-None-Match`, ETag, EntityTag, GenericHttpCredentials}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.minion.App.Settings
import akka.stream.scaladsl._
import akka.stream.{ActorMaterializer, OverflowStrategy, ThrottleMode}
import com.github.blemale.scaffeine.Scaffeine
import spray.json._

import scala.None
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.Try

trait ZuluDateTimeMarshalling {
  import scala.util.Try

  implicit object DateFormat extends JsonFormat[ZonedDateTime] {
    def write(date: ZonedDateTime) = JsString(dateToIsoString(date))
    def read(json: JsValue) = json match {
      case JsString(rawDate) =>
        parseIsoDateString(rawDate)
          .fold(deserializationError(s"Expected ISO Date format, got $rawDate"))(identity)
      case JsNumber(rawNumber) =>
        parseEpochSeconds(rawNumber)
          .fold(deserializationError(s"Expected Epoch Seconds, got $rawNumber"))(identity)
      case error => deserializationError(s"Expected JsString, got $error")
    }
  }

  private def dateToIsoString(date: ZonedDateTime) =
    date.toString

  private def parseIsoDateString(date: String): Option[ZonedDateTime] =
    Try { Instant.parse(date).atZone(ZoneOffset.UTC) }.toOption

  private def parseEpochSeconds(date: BigDecimal): Option[ZonedDateTime] =
    Try { Instant.ofEpochSecond(date.toLong).atZone(ZoneOffset.UTC) }.toOption
}

object GithubService extends DefaultJsonProtocol with ZuluDateTimeMarshalling {

  case object Refresh
  case class ClientFailed(ex: Throwable)

  def props(settings: Settings, listeners: Seq[ActorRef]): Props = Props(new GithubService(settings, listeners))

  object CommitStatusConstants {
    final val SUCCESS = "success"
    final val PENDING = "pending"
    final val FAILURE = "failure"

    // context to enforce that last commit is green only if all prior commits are also green
    final val COMBINED = "combined"
    final val REVIEWED = "reviewed"
    final val CLA      = "cla"

    def jenkinsContext(ctx: String) = ctx match {
      case COMBINED | REVIEWED | CLA => false
      case _ => true
    }
  }

  case class User(login: String, avatar_url: String)
  case class Author(name: String, email: String) {// , username: Option[String]
    override def toString = name
  }
  case class Repository(name: String, full_name: String, git_url: String,
                        updated_at: Option[ZonedDateTime], created_at: Option[ZonedDateTime], pushed_at: Option[ZonedDateTime]) { // owner: Either[User, Author]
    override def toString = full_name
  }
  case class GitRef(sha: String, label: String, ref: String, repo: Repository, user: User) {
    override def toString = s"${repo}#${sha.take(7)}"
  }
  case class PullRequest(number: Int, state: String, title: String, body: Option[String],
                         created_at: Option[ZonedDateTime], updated_at: Option[ZonedDateTime], closed_at: Option[ZonedDateTime], merged_at: Option[ZonedDateTime],
                         head: GitRef, base: GitRef, user: User, merged: Option[Boolean], mergeable: Option[Boolean], merged_by: Option[User]) {
    override def toString = s"${base.repo}#$number"
  }

  case class Label(name: String, color: Option[String] = None, url: Option[String] = None) {
    override def toString = name
  }

  object Milestone {
    private val MergeBranch = """Merge to (\S+)\b""".r.unanchored
  }
  case class Milestone(number: Int, state: String, title: String, description: Option[String], creator: User,
                       created_at: Option[ZonedDateTime], updated_at: Option[ZonedDateTime], closed_at: Option[ZonedDateTime], due_on: Option[ZonedDateTime]) {
    override def toString = s"Milestone $title ($state)"

    def mergeBranch = description match {
      case Some(Milestone.MergeBranch(branch)) => Some(branch)
      case _                                   => None
    }
  }

  case class Issue(number: Int, state: String, title: String, body: Option[String], user: User, labels: List[Label],
                   assignee: Option[User], milestone: Option[Milestone], created_at: Option[ZonedDateTime], updated_at: Option[ZonedDateTime], closed_at: Option[ZonedDateTime]) {
    override def toString = s"Issue #$number"
  }

  case class CommitInfo(id: Option[String], message: String, timestamp: Option[ZonedDateTime], author: Author, committer: Author)
  // added: Option[List[String]], removed: Option[List[String]], modified: Option[List[String]]
  case class Commit(sha: String, commit: CommitInfo, url: Option[String] = None)

  case class CombiCommitStatus(state: String, sha: String, statuses: List[CommitStatus], total_count: Int)

  case class CommitStatus(state: String, context: Option[String] = None, description: Option[String] = None, target_url: Option[String] = None)

  case class IssueComment(body: String, user: Option[User] = None, created_at: Option[ZonedDateTime], updated_at: Option[ZonedDateTime], id: Option[Long] = None)

  case class PullRequestComment(body: String, user: Option[User] = None, commit_id: Option[String] = None, path: Option[String] = None, position: Option[Int] = None, created_at: Option[ZonedDateTime], updated_at: Option[ZonedDateTime], id: Option[Long] = None)

  case class PullRequestEvent(action: String, number: Int, pull_request: PullRequest)
  case class PushEvent(ref: String, commits: List[CommitInfo], repository: Repository)

  case class PullRequestReviewCommentEvent(action: String, pull_request: PullRequest, comment: PullRequestComment, repository: Repository)
  case class IssueCommentEvent(action: String, issue: Issue, comment: IssueComment, repository: Repository)

  object ReviewStatusConstants {
    final val COMMENTED = "COMMENTED"
    final val CHANGES_REQUESTED = "CHANGES_REQUESTED"
    final val APPROVED = "APPROVED"
    final val DISMISSED = "DISMISSED"
  }

  case class PullRequestReview(user: User, body: String, submitted_at: Option[ZonedDateTime], state: String) {
    import ReviewStatusConstants._

    def commented = state == COMMENTED
    def changesRequested = state == CHANGES_REQUESTED
    def approved = state == APPROVED
    def dismissed = state == DISMISSED
  }

  case class RateLimit(limit: Int, remaining: Int, reset: ZonedDateTime)
  case class UsageStats(rate: RateLimit)

  private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtUser             : RJF[User]                          = jsonFormat2(User)
  implicit lazy val _fmtAuthor           : RJF[Author]                        = jsonFormat2(Author)
  implicit lazy val _fmtRepository       : RJF[Repository]                    = jsonFormat6(Repository)

  implicit lazy val _fmtGitRef           : RJF[GitRef]                        = jsonFormat5(GitRef)

  implicit lazy val _fmtPullRequest      : RJF[PullRequest]                   = jsonFormat14(PullRequest)

  implicit lazy val _fmtLabel            : RJF[Label]                         = jsonFormat3(Label)
  implicit lazy val _fmtMilestone        : RJF[Milestone]                     = jsonFormat9(Milestone.apply)
  implicit lazy val _fmtIssue            : RJF[Issue]                         = jsonFormat11(Issue)

  implicit lazy val _fmtCommitInfo       : RJF[CommitInfo]                    = jsonFormat5(CommitInfo)
  implicit lazy val _fmtCommit           : RJF[Commit]                        = jsonFormat3(Commit)
  implicit lazy val _fmtCommitStatus     : RJF[CommitStatus]                  = jsonFormat4(CommitStatus.apply)
  implicit lazy val _fmtCombiCommitStatus: RJF[CombiCommitStatus]             = jsonFormat(CombiCommitStatus, "state", "sha", "statuses", "total_count") // need to specify field names because we added methods to the case class..

  implicit lazy val _fmtIssueComment     : RJF[IssueComment]                  = jsonFormat5(IssueComment)
  implicit lazy val _fmtPullRequestComment: RJF[PullRequestComment]           = jsonFormat8(PullRequestComment)

  implicit lazy val _fmtPullRequestEvent : RJF[PullRequestEvent]              = jsonFormat3(PullRequestEvent)
  implicit lazy val _fmtPushEvent        : RJF[PushEvent]                     = jsonFormat3(PushEvent)
  implicit lazy val _fmtPRCommentEvent   : RJF[PullRequestReviewCommentEvent] = jsonFormat4(PullRequestReviewCommentEvent)
  implicit lazy val _fmtIssueCommentEvent: RJF[IssueCommentEvent]             = jsonFormat4(IssueCommentEvent)

  implicit lazy val _fmtPullRequestReview: RJF[PullRequestReview]             = jsonFormat4(PullRequestReview)

  implicit lazy val _fmtRateLimit        : RJF[RateLimit]                     = jsonFormat3(RateLimit)
  implicit lazy val _fmtUsageStats       : RJF[UsageStats]                    = jsonFormat1(UsageStats)

  case class FullReport(
    repo: Repository,
    pulls: Seq[PullRequest],
    comments: Map[PullRequest, Seq[IssueComment]],
    statuses: Map[PullRequest, CombiCommitStatus],
    reviews: Map[PullRequest, Seq[PullRequestReview]],
    usageStats: UsageStats
  )

}

class GithubService(settings: Settings, listeners: Seq[ActorRef]) extends Actor with ActorLogging {
  import GithubService._
  import context.dispatcher

  private implicit val mat = ActorMaterializer()

  private val timer = context.system.scheduler.schedule(1.second, settings.pollInterval, self, Refresh)

  private val responseCache = Scaffeine()
    .maximumSize(500)
    .build[Uri, (EntityTag, Any)]()

  // Throttled global connection pool
  val (queue: SourceQueueWithComplete[(HttpRequest, Promise[HttpResponse])], clientFuture: Future[Done]) =
    Source.queue[(HttpRequest, Promise[HttpResponse])](256, OverflowStrategy.backpressure)
      .throttle(settings.apiCallPerHour, 1.hour, 100, ThrottleMode.shaping)
      .via(Http(context.system).cachedHostConnectionPoolHttps("api.github.com"))
      //.via(responseCache)
      .toMat(Sink.foreach { case (response, promise) =>
        promise.tryComplete(response)
      })(Keep.both).run()

  clientFuture.onFailure { case ex: Throwable =>
    self ! ClientFailed(ex)
  }

  private def throttledRequest(req: HttpRequest): Future[HttpResponse] = {
    val promise = Promise[HttpResponse]

    val request = req
      .addHeader(headers.Authorization(GenericHttpCredentials("token", settings.token)))
      .addHeader(headers.Accept(MediaRange.custom("application/vnd.github.black-cat-preview+json")))

    queue.offer(request, promise).flatMap(_ => promise.future)
  }

  private def throttledJson[T: RootJsonFormat](req: HttpRequest): Future[T] = {
    val etag = responseCache.getIfPresent(req.uri)
    throttledRequest(etag.fold(req){ case (tag, _) => req.addHeader(`If-None-Match`(tag))}).flatMap {
      case response @ HttpResponse(StatusCodes.NotModified, _, _, _) =>
        response.discardEntityBytes()
        etag match {
          case Some((_, res)) => Future.successful(res.asInstanceOf[T])
          case None => throw new Error("No changes from the cached version, but cache is empty.")
        }
      case response @ HttpResponse(StatusCodes.OK, _, entity, _) =>
        val resp = Unmarshal(entity).to[T]
        resp.onSuccess {
          case r => response.header[ETag].fold()(e => responseCache.put(req.uri, (e.etag, r)))
        }
        resp
      case response => Unmarshal(response.entity).to[T]
    }
  }

  private def api[T: RootJsonFormat](repo: String, apiPath: String = "", base: String = "/repos/"): Future[T] = {
    val req = HttpRequest(GET, uri = s"$base$repo$apiPath")
    throttledJson[T](req)
  }

  override def preStart(): Unit = {
    log.info("Starting Github service")
  }

  override def postStop(): Unit = {
    log.info("Stopped Github service")
    timer.cancel()
  }

  override def receive: Receive = {
    case Refresh => refresh()
    case ClientFailed(ex) =>
      throw ex
    case report: FullReport =>
      log.info("Refreshed report")
      listeners.foreach(_ ! report)
    case Failure(ex) =>
      log.error(ex, "Failed to refresh Github status")
  }


  private def createReport(repo: String): Future[FullReport] = {
    val repoFuture = api[Repository](repo)
    val pullsFuture = api[Seq[PullRequest]](repo, "/pulls")
    val usageStatsFuture = api[UsageStats](repo = "", base = "/rate_limit")

    val pullCommentsFuture = pullsFuture.flatMap { pulls =>

      val allCommentFutures: Seq[Future[(PullRequest, Seq[IssueComment])]] =
        pulls.map { pull =>
          api[Seq[IssueComment]](repo, s"/issues/${pull.number}/comments").map(pull -> _)
        }
      Future.sequence(allCommentFutures).map(_.toMap)
    }

    val pullStatusFuture = pullsFuture.flatMap { pulls =>
      val statuses = pulls.map { pull =>
        api[CombiCommitStatus](repo, s"/commits/${pull.head.sha}/status").map(pull -> _)
      }
      Future.sequence(statuses).map(_.toMap)
    }

    val pullReviewsFuture = pullsFuture.flatMap { pulls =>
      val pullReviews = pulls.map { pull =>
        api[Seq[PullRequestReview]](repo, s"/pulls/${pull.number}/reviews").map(pull -> _)
      }
      Future.sequence(pullReviews).map(_.toMap)
    }

    for {
      repo <- repoFuture
      pulls <- pullsFuture
      pullComments <- pullCommentsFuture
      pullStatus <- pullStatusFuture
      pullReviews <- pullReviewsFuture
      usageStats <- usageStatsFuture
    } yield FullReport(
      repo, pulls, pullComments, pullStatus, pullReviews, usageStats
    )

  }

  private def refresh(): Unit = {
    log.info("Refreshing Github data")
    import akka.pattern.pipe

    createReport("akka/akka").pipeTo(self)

  }
}