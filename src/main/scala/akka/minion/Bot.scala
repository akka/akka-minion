package akka.minion

import akka.actor.{Actor, ActorLogging, Props}
import akka.minion.App.Settings
import akka.minion.GithubService.FullReport

object Bot {

  def props(settings: Settings): Props = Props(new Bot(settings))

}

class Bot(settingts: Settings) extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("Minion bot started")
  }

  override def postStop(): Unit = {
    log.info("Minion bot stopped")
  }

  override def receive: Receive = {
    case App.ServicePing => sender() ! App.ServicePong
    case report: FullReport =>
      log.info(s"Received fresh report for ${report.repo}")
  }
}
