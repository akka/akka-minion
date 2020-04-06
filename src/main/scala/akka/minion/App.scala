package akka.minion

import java.util.concurrent.TimeUnit

import akka.ConfigurationException
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object App {
  case object ServicePing
  case object ServicePong

  case class Settings(
      httpPort: Int,
      pollInterval: FiniteDuration,
      token: String,
      apiCallPerHour: Int,
      teamMembers: Set[String],
      bots: Set[String],
      teamRepos: Map[String, Set[String]],
      statsRepos: List[String]
  ) {
    val repos: Set[String] = teamRepos.flatMap {
      case (_, repos) => repos
    }.toSet
  }

  def props(settings: Settings): Props = Props(new App(settings))

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("akka-minion")

    try {
      val config = system.settings.config.getConfig("akka.minion")
      val teamRepos: Map[String, Set[String]] = {
        config
          .getObjectList("team-repos")
          .asScala
          .map { configObject =>
            val c = configObject.toConfig
            val name = c.getString("team")
            val repos = c.getStringList("repos").asScala.toSet
            (name, repos)
          }
          .toMap
      }

      val settings = Settings(
        httpPort = config.getInt("http-port"),
        pollInterval = Duration(config.getDuration("poll-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
        token = config.getString("access-token"),
        apiCallPerHour = config.getInt("max-api-calls-per-hour"),
        teamMembers = config.getStringList("team-members").asScala.toSet,
        bots = config.getStringList("bots").asScala.toSet,
        teamRepos = teamRepos,
        statsRepos = config.getStringList("stats-repos").asScala.toList
      )

      system.actorOf(App.props(settings), "minion-supervisor")
      Await.result(system.whenTerminated, Duration.Inf)

//      new Graphql(settings).call()
    } catch {
      case e: Throwable =>
        println("Minion lost.")
        e.printStackTrace()
    } finally {
      system.terminate()
    }
  }
}

class App(settings: App.Settings) extends Actor {
  override def supervisorStrategy: SupervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, 3.seconds) {
      case _: ConfigurationException => Stop
      case NonFatal(_) => Restart
    }

  override def receive: Receive = Actor.emptyBehavior

  override def preStart(): Unit = {
    val bot = context.watch(context.actorOf(Bot.props(settings), "bot"))
    val dashboard =
      context.watch(context.actorOf(Dashboard.props(settings), "dashboard"))
    val ghService =
      context.watch(context.actorOf(GithubService.props(settings, List(bot, dashboard)), "github-service"))
    context.watch(context.actorOf(HttpServer.props(settings, ghService, bot, dashboard), "http-server"))
  }
}
