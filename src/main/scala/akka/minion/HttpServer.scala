package akka.minion

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.minion.App.Settings
import akka.minion.Dashboard._
import akka.pattern.{ask, AskTimeoutException}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scalatags.Text.TypedTag
import scalatags.stylesheet.{CascadingStyleSheet, Cls}

object HttpServer {
  def props(settings: App.Settings, ghService: ActorRef, bot: ActorRef, dashboard: ActorRef): Props =
    Props(new HttpServer(settings, ghService, bot, dashboard))
}

class HttpServer(
    val settings: App.Settings,
    val githubService: ActorRef,
    val bot: ActorRef,
    val dashboard: ActorRef
) extends Actor
    with ActorLogging {
  import context.dispatcher

  private implicit val materializer: Materializer = SystemMaterializer(context.system).materializer
  private var bindingFuture: Future[Http.ServerBinding] = _

  implicit val timeout = Timeout(3.seconds)

  private val route =
    path("status") {
      get {
        val ghStatus = serviceStatus(githubService)
        val botStatus = serviceStatus(bot)
        val dashboardStatus = serviceStatus(dashboard)

        complete(ghStatus.map(Template.servicesStatus(_)).map(Template(_)))
      }
    } ~
    path("overview") {
      redirect("/", StatusCodes.PermanentRedirect)
    } ~
    pathSingleSlash {
      parameters(Symbol("team") ?) { team =>
        get {
          val reportFuture =
            (dashboard ? GetMainDashboard)
              .mapTo[MainDashboardReply]
              .map { reply =>
                reply.report match {
                  case Some(report) =>
                    val filteredReport = team match {
                      case Some(teamName) =>
                        val repos = settings.teamRepos.getOrElse(teamName, Set.empty)
                        report.filterRepos(repos)
                      case None =>
                        report
                    }
                    Template(Template.mainDashboard(filteredReport, settings))
                  case None =>
                    Template(Template.noDataYet(settings), Template.refreshPage)
                }
              }

          complete(reportFuture)
        }
      }
    } ~
    path("personal" / Segment) { person =>
      get {
        val reportFuture =
          (dashboard ? GetPersonalDashboard(person))
            .mapTo[PersonalDashboardReply]
            .map(reply => Template(Template.personalDashboard(reply.report)))

        complete(reportFuture)
      }
    }

  def serviceStatus(service: ActorRef): Future[String] =
    (service ? App.ServicePing)
      .mapTo[App.ServicePong.type]
      .map(_ => "Up")
      .recover {
        case _: AskTimeoutException => "Down"
      }

  override def preStart(): Unit = {
    bindingFuture = Http(context.system).newServerAt("0.0.0.0", settings.httpPort).bindFlow(route)
    log.info(s"HTTP server started on port ${settings.httpPort}")
  }

  override def postStop(): Unit = {
    import context.dispatcher
    if (bindingFuture ne null) bindingFuture.foreach(_.unbind())
    log.info("HTTP server stopped")
  }

  override def receive: Receive = {
    case _ =>
  }
}

object Template {
  import scalatags.Text.all._

  val refreshPage: TypedTag[String] = meta(httpEquiv := "refresh", content := "5")

  def apply(content: TypedTag[String], additionalHeaders: scalatags.Text.Modifier*): HttpEntity.Strict = {
    val tags = html(
      head(
        link(
          rel := "stylesheet",
          href := "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css"
        ),
        link(
          rel := "stylesheet",
          href := "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap-theme.min.css"
        ),
        script(
          src := "https://code.jquery.com/jquery-3.3.1.min.js"
        ),
        script(
          src := "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js"
        ),
        link(
          rel := "stylesheet",
          href := "https://cdnjs.cloudflare.com/ajax/libs/octicons/4.4.0/font/octicons.min.css"
        ),
        tag("style")(
          Style.styleSheetText,
          raw("""
            |.table > tbody > tr > td {
            |     vertical-align: middle;
            |}
          """.stripMargin)
        ),
        additionalHeaders
      ),
      body(
        div(`class` := "container", content)
      )
    )

    HttpEntity(ContentTypes.`text/html(UTF-8)`, tags.render)
  }

  def noDataYet(settings: Settings): TypedTag[String] =
    div(
      alert(p("No dashboard data available, yet."), "info"),
      div("Teams: ", for (team <- settings.teamRepos.keys.toSeq) yield a(href := s"/?team=$team", s"$team "))
    )

  def mainDashboard(report: MainDashboardData, settings: Settings): TypedTag[String] = {
    def repoUrl(name: String) = s"https://github.com/$name"

    div(
      h2(s"Report"),
      div("Teams: ", for (team <- settings.teamRepos.keys.toSeq) yield a(href := s"/?team=$team", s"$team ")),
      table(
        `class` := "table table-condensed",
        thead(
          tr(
            th(""),
            th("Title"),
            th("Last Updated"),
            th("People"),
            th("Last actor"),
            th("M"),
            th("S"),
            th("R")
          )
        ),
        tbody(
          for (pull <- report.pulls.toSeq)
            yield tr(
              td(person(pull.author)),
              td(
                a(
                  href := s"${repoUrl(pull.repo.fullName)}/pull/${pull.number}",
                  target := "_blank",
                  s"${pull.repo.name}#${pull.number}: ${pull.title}"
                ),
                for (label <- pull.labels)
                  yield span(
                    Style.issueLabel,
                    backgroundColor := label.color.getOrElse("#111111"),
                    color := "#FFFFFF",
                    label.name
                  )
              ),
              td(Style.noWrap, pull.lastUpdated),
              td(pull.people.map(involvment).toSeq),
              td(Style.noWrap, performance(pull.lastActor)),
              td(threeState(pull.mergeable)),
              td(validationStatus(pull.status)),
              td(reviewStatus(pull))
            )
        )
      ),
      p(
        s"API call quota: ${report.usageStats.remaining}/${report.usageStats.limit}. Will reset ${report.usageStats.resetsIn}"
      ),
      p(
        a(href := "https://github.com/akka/akka-minion", "Source code in GitHub")
      )
    )
  }

  def personalDashboard(data: Option[PersonalDashboard]): TypedTag[String] =
    data match {
      case None => alert(p("No dashboard data is available"), "info")
      case Some(report) =>
        def repoUrl(name: String) = s"https://github.com/$name"

        def renderAction(action: PrAction): TypedTag[String] =
          action match {
            case NoAction => p("N/A")
            case PleaseReview => p("Please review")
            case PleaseFix => p("Fix failure")
            case PleaseResolve => p("Act on review")
            case PleaseRebase => p("Rebase")
          }

        def mkTable(data: Iterable[PersonalDashboardEntry]): TypedTag[String] =
          table(
            `class` := "table table-condensed",
            thead(
              tr(
                th("Title"),
                th("Action")
              )
            ),
            tbody(
              for (entry <- data.toSeq if entry.action != NoAction)
                yield tr(
                  td(
                    a(
                      href := s"${repoUrl(entry.repo.fullName)}/pull/${entry.pr.number}",
                      target := "_blank",
                      s"${entry.pr.number}: ${entry.pr.title}"
                    )
                  ),
                  td(renderAction(entry.action))
                )
            )
          )

        div(
          h1(s"Personal report for ${report.person}"),
          h2("Own PRs needing attention"),
          mkTable(report.ownPrs),
          h2("Team PRs needing attention"),
          mkTable(report.teamPrs),
          h2("External PRs needing attention"),
          mkTable(report.externalPrs)
        )
    }

  def threeState(state: Option[Boolean]): TypedTag[String] =
    state match {
      case None => p("")
      case Some(bool) => p(bool.toString())
    }

  def alert(content: TypedTag[String], kind: String): TypedTag[String] =
    div(`class` := s"alert alert-$kind", role := "alert", content)

  def panel(heading: String, content: TypedTag[String]): TypedTag[String] =
    div(
      `class` := "panel panel-default",
      div(`class` := "panel-heading", heading),
      div(`class` := "panel-body", content)
    )

  def servicesStatus(ghStatus: String): TypedTag[String] =
    panel("Services Status", p("Github poller", ghStatus))

  def involvment(p: Performance): TypedTag[String] =
    person(
      p.person,
      (p.action match {
        case Action.Approved => Seq(Style.good)
        case Action.RequestedChanges => Seq(Style.bad)
        case _ => Seq()
      }): _*
    )

  def person(p: Person, styles: Cls*): TypedTag[String] =
    a(
      href := s"https://github.com/${p.login}",
      target := "_blank",
      img(
        Style.avatar,
        styles,
        src := p.avatarUrl,
        title := p.login
      )
    )

  def performance(perf: Performance): Seq[TypedTag[String]] = {
    val (icon, titleText) = perf.action match {
      case Action.Commented => ("comment", "Commented")
      case Action.Approved => ("check", "Approved PR")
      case Action.RequestedChanges => ("x", "Requested changes")
      case Action.OpenedPr => ("git-pull-request", "Opened PR")
      case Action.Dismissed => ("mute", "Dismissed review")
    }

    Seq(
      person(perf.person),
      span(`class` := s"octicon octicon-$icon", title := titleText)
    )
  }

  def reviewStatus(pull: MainDashboardEntry): TypedTag[String] = {
    val (icon, titleText, style) = pull match {
      case p if p.reviewedReject > 0 =>
        ("x", "Changes requested", Style.badIcon)
      case p if p.reviewedOk >= 2 =>
        ("check", "Pull Request approved", Style.goodIcon)
      case _ => ("", "", Style.empty)
    }
    span(
      `class` := s"octicon octicon-$icon",
      style,
      title := titleText
    )
  }

  def validationStatus(status: PrValidationStatus): TypedTag[String] = {
    val (icon, titleText, style) = status match {
      case PrValidationStatus.Success =>
        ("check", "All PR validations passed", Style.goodIcon)
      case PrValidationStatus.Pending =>
        ("clock", "PR validation in progress", Style.indifferentIcon)
      case PrValidationStatus.Failure =>
        ("x", "PR validation failed", Style.badIcon)
    }
    span(
      `class` := s"octicon octicon-$icon",
      style,
      title := titleText
    )
  }
}

object Style extends CascadingStyleSheet {
  import scalatags.Text.all._

  initStyleSheet()

  val avatar = cls(
    width := "24px",
    height := "24px",
    borderRadius := "4px",
    marginRight := "8px"
  )

  val good = cls(
    border := "2px solid green",
    padding := "1px"
  )

  val bad = cls(
    border := "2px solid red",
    padding := "1px"
  )

  val goodIcon = cls(
    color := "green"
  )

  val indifferentIcon = cls(
    color := "orange"
  )

  val badIcon = cls(
    color := "red"
  )

  val noWrap = cls(
    whiteSpace := "nowrap"
  )

  val issueLabel = cls(
    height := "20px",
    padding := "0.15em 4px",
    fontSize := "12px",
    fontWeight := "600",
    lineHeight := "15px",
    borderRadius := "2px",
    boxShadow := "inset 0 -1px 0 rgba(27,31,35,0.12)"
  )

  val empty = cls()
}
