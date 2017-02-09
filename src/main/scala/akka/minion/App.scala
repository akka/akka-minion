package akka.minion

import akka.ConfigurationException
import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{Actor, ActorSystem, OneForOneStrategy, Props, SupervisorStrategy}

import scala.io.StdIn
import scala.util.control.NonFatal
import scala.concurrent.duration._

object App {

  case object ServicePing
  case object ServicePong

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("akka-minion")

    try {

      system.actorOf(Props(new App), "minion-supervisor")

      println("Press Enter to stop")
      StdIn.readLine()
    } catch {
      case e: Throwable =>
        println("Minion lost.")
        e.printStackTrace()
    } finally {
      system.terminate()
    }

  }

}

class App extends Actor {


  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, 3.seconds) {
    case _: ConfigurationException => Stop
    case NonFatal(_) => Restart
  }

  override def receive: Receive = Actor.emptyBehavior

  override def preStart(): Unit = {
    val ghService = context.watch(context.actorOf(GithubService.props(), "github-service"))
    val bot = context.watch(context.actorOf(Bot.props(), "bot"))
    val dashboard = context.watch(context.actorOf(Dashboard.props(), "dashboard"))
    context.watch(context.actorOf(HttpServer.props(ghService, bot, dashboard), "http-server"))
  }
}
