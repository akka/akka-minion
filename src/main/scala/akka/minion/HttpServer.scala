package akka.minion

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.minion.Dashboard.{GetMainDashboard, MainDashboardData, MainDashboardReply}
import akka.util.Timeout
import akka.pattern.ask
import akka.pattern.AskTimeoutException

import scala.concurrent.Future
import scala.concurrent.duration._
import scalatags.Text.TypedTag

object HttpServer {



  def props(ghService: ActorRef, bot: ActorRef, dashboard: ActorRef): Props =
    Props(new HttpServer(ghService, bot, dashboard))

}

class HttpServer(
    val githubService: ActorRef,
    val bot: ActorRef,
    val dashboard: ActorRef
  ) extends Actor with ActorLogging {
  import context.dispatcher

  private implicit val materializer = ActorMaterializer()
  private var bindingFuture: Future[Http.ServerBinding] = _
  private val port = context.system.settings.config.getInt("akka.minion.http-port")

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
    }

  def serviceStatus(service: ActorRef): Future[String] = {

    (service ? App.ServicePing).mapTo[App.ServicePong.type]
      .map(_ => "Up")
      .recover {
        case _: AskTimeoutException => "Down"
      }
  }


  override def preStart(): Unit = {
    bindingFuture = Http(context.system).bindAndHandle(route, "localhost", port)
    log.info(s"HTTP server started on port $port")
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
        div(
          h2(s"Report on ${report.repo}"),
          table(
            `class`:="table, table-bordered",
            thead(
              tr(
                th("Title"), th("Last Updated"), th("People"), th("Last actor"), th("M"), th("S"), th("R")
              )
            ),
            tbody(
              for (pull <- report.pulls) yield {
                tr(
                  td(s"${pull.number}: ${pull.title}"),
                  td(pull.lastUpdated),
                  td(pull.lastActor),
                  td(pull.people.mkString(", ")),
                  td(threeState(pull.mergeable)),
                  td(threeState(pull.statusOk)),
                  td(pull.reviewedOk)
                )
              }
            )
          )
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

}