package akka.minion

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.minion.Dashboard._
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.ActorMaterializer
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
  ) extends Actor with ActorLogging {
  import context.dispatcher

  private implicit val materializer = ActorMaterializer()
  private var bindingFuture: Future[Http.ServerBinding] = _

  implicit val timeout = Timeout(3.seconds)


  private val route =
    pathSingleSlash {
      get {
        val ghStatus = serviceStatus(githubService)
        val botStatus = serviceStatus(bot)
        val dashboardStatus = serviceStatus(dashboard)

        complete(ghStatus.map(Template.servicesStatus(_)).map(Template(_)))
      }
    } ~
    path("overview") {
      get {
        val reportFuture =
          (dashboard ? GetMainDashboard).mapTo[MainDashboardReply]
          .map(reply => Template(Template.mainDashboard(reply.report)))

        complete(reportFuture)
      }
    } ~
    path("personal" / Segment) { person =>
      get {
        val reportFuture =
          (dashboard ? GetPersonalDashboard(person)).mapTo[PersonalDashboardReply]
            .map(reply => Template(Template.personalDashboard(reply.report)))

        complete(reportFuture)
      }
    }

  def serviceStatus(service: ActorRef): Future[String] = {

    (service ? App.ServicePing).mapTo[App.ServicePong.type]
      .map(_ => "Up")
      .recover {
        case _: AskTimeoutException => "Down"
      }
  }


  override def preStart(): Unit = {
    bindingFuture = Http(context.system).bindAndHandle(route, "localhost", settings.httpPort)
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


  def apply(content: TypedTag[String]): HttpEntity.Strict = {
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
        )
      ),
      body(
        div(`class`:="container", content)
      )
    )

    HttpEntity(ContentTypes.`text/html(UTF-8)`, tags.render)

  }


  def mainDashboard(data: Option[MainDashboardData]): TypedTag[String] = {
    data match {
      case None => alert(p("No dashboard data is available"), "info")
      case Some(report) =>
        val repoUrl = s"https://github.com/${report.repo}"

        div(
          h2(s"Report on ${report.repo}"),
          table(
            `class`:="table table-condensed",
            thead(
              tr(
                th("Title"), th("Last Updated"), th("People"), th("Last actor"), th("M"), th("S"), th("R")
              )
            ),
            tbody(
              for (pull <- report.pulls) yield {
                tr(
                  td(
                    person(pull.author),
                    a(href:=repoUrl + "/pull/" + pull.number, s"${pull.number}: ${pull.title}")
                  ),
                  td(Style.noWrap, pull.lastUpdated),
                  td(pull.people.map(involvment).toSeq),
                  td(Style.noWrap, performance(pull.lastActor)),
                  td(threeState(pull.mergeable)),
                  td(validationStatus(pull.status)),
                  td(reviewStatus(pull))
                )
              }
            )
          ),
          p(
            s"API call quota: ${report.usageStats.remaining}/${report.usageStats.limit}. Will reset ${report.usageStats.resetsIn}"
          )
        )
    }

  }

  def personalDashboard(data: Option[PersonalDashboard]): TypedTag[String] = {
    data match {
      case None => alert(p("No dashboard data is available"), "info")
      case Some(report) =>
        val repoUrl = s"https://github.com/${report.repo}"

        def renderAction(action: PrAction): TypedTag[String] = action match {
          case NoAction => p("N/A")
          case PleaseReview => p("Please review")
          case PleaseFix => p("Fix failure")
          case PleaseResolve => p("Act on review")
          case PleaseRebase => p("Rebase")
        }

        def mkTable(data: Seq[PersonalDashboardEntry]): TypedTag[String] =
          table(
            `class`:="table table-condensed",
            thead(
              tr(
                th("Title"), th("Action")
              )
            ),
            tbody(
              for (entry <- data if entry.action != NoAction) yield {
                tr(
                  td(a(href:=repoUrl + "/pull/" + entry.pr.number, s"${entry.pr.number}: ${entry.pr.title}")),
                  td(renderAction(entry.action))
                )
              }
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
  }

  def threeState(state: Option[Boolean]): TypedTag[String] = {
    state match {
      case None => p("")
      case Some(bool) => p(bool.toString())
    }
  }

  def alert(content: TypedTag[String], kind: String): TypedTag[String] =
    div(`class`:=s"alert alert-$kind", role:="alert", content)

  def panel(heading: String, content: TypedTag[String]): TypedTag[String] = {
    div(
      `class`:="panel panel-default",
      div(`class`:="panel-heading", heading),
      div(`class`:="panel-body", content)
    )
  }

  def servicesStatus(ghStatus: String): TypedTag[String] = {
    panel("Services Status",
      p("Github poller", ghStatus)
    )
  }

  def involvment(p: Performance): TypedTag[String] = {
    person(p.person, (p.action match {
      case Action.Approved => Seq(Style.good)
      case Action.RequestedChanges => Seq(Style.bad)
      case _ => Seq()
    }):_*)
  }

  def person(p: Person, styles: Cls*): TypedTag[String] = {
    img(
      Style.avatar,
      styles,
      src := p.avatarUrl,
      title := p.login
    )
  }

  def performance(perf: Performance): Seq[TypedTag[String]] = {
    val (icon, titleText) = perf.action match {
      case Action.Commented => ("comment", "Commented")
      case Action.Approved => ("check", "Approved PR")
      case Action.RequestedChanges => ("x", "Requested changes")
      case Action.OpenedPr => ("git-pull-request", "Opened PR")
    }

    Seq(
      person(perf.person),
      span(`class` := s"octicon octicon-$icon", title:=titleText)
    )
  }

  def reviewStatus(pull: MainDashboardEntry): TypedTag[String] = {
    val (icon, titleText, style) = pull match {
      case p if p.reviewedReject > 0 => ("x", "Changes requested", Style.badIcon)
      case p if p.reviewedOk >= 2 => ("check", "Pull Request approved", Style.goodIcon)
      case _ => ("", "", Style.empty)
    }
    span(
      `class`:=s"octicon octicon-$icon",
      style,
      title:=titleText
    )
  }

  def validationStatus(status: PrValidationStatus): TypedTag[String] = {
    val (icon, titleText, style) = status match {
      case PrValidationStatus.Success => ("check", "All PR validations passed", Style.goodIcon)
      case PrValidationStatus.Pending => ("clock", "PR validation in progress", Style.indifferentIcon)
      case PrValidationStatus.Failure => ("x", "PR validation failed", Style.badIcon)
    }
    span(
      `class`:=s"octicon octicon-$icon",
      style,
      title:=titleText
    )
  }
}

object Style extends CascadingStyleSheet {
  import scalatags.Text.all._

  initStyleSheet()

  val avatar = cls(
    width:="24px",
    height:="24px",
    borderRadius:="4px",
    marginRight:="8px"
  )

  val good = cls(
    border:="2px solid green",
    padding:="1px"
  )

  val bad = cls(
    border:="2px solid red",
    padding:="1px"
  )

  val goodIcon = cls(
    color:="green"
  )

  val indifferentIcon = cls(
    color:="orange"
  )

  val badIcon = cls(
    color:="red"
  )

  val noWrap = cls(
    whiteSpace:="nowrap"
  )

  val empty = cls()

}