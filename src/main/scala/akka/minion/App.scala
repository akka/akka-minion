package akka.minion

import java.util.concurrent.TimeUnit

import akka.ConfigurationException
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}
import com.typesafe.config.ConfigList

import scala.io.StdIn
import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.concurrent.Await

object App {

  case object ServicePing
  case object ServicePong

  case class Settings(
    httpPort: Int,
    pollInterval: FiniteDuration,
    token: String,
    apiCallPerHour: Int,
    teamMembers: Set[String],
    bots: Set[String]
  )

  def props(settings: Settings): Props = Props(new App(settings))

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("akka-minion")

    try {

      val config = system.settings.config

      def asList(cfg: ConfigList): Seq[String] = {
        val itr = cfg.iterator()
        var result = Vector.empty[String]
        while (itr.hasNext) result :+= itr.next().unwrapped().asInstanceOf[String]
        result
      }

      val settings = Settings(
        httpPort = config.getInt("akka.minion.http-port"),
        pollInterval = Duration(config.getDuration("akka.minion.poll-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
        token = config.getString("akka.minion.api-key"),
        apiCallPerHour = config.getInt("akka.minion.max-api-calls-per-hour"),
        teamMembers = asList(config.getList("akka.minion.team-members")).toSet,
        bots = asList(config.getList("akka.minion.bots")).toSet
      )

      system.actorOf(App.props(settings), "minion-supervisor")

      Await.result(system.whenTerminated, Duration.Inf)
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


  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, 3.seconds) {
    case _: ConfigurationException => Stop
    case NonFatal(_) => Restart
  }

  override def receive: Receive = Actor.emptyBehavior

  override def preStart(): Unit = {
    val bot = context.watch(context.actorOf(Bot.props(settings), "bot"))
    val dashboard = context.watch(context.actorOf(Dashboard.props(settings), "dashboard"))
    val ghService = context.watch(context.actorOf(GithubService.props(settings, List(bot, dashboard)), "github-service"))
    context.watch(context.actorOf(HttpServer.props(settings, ghService, bot, dashboard), "http-server"))
  }
}
