package akka.minion

import akka.actor.{Actor, ActorLogging, Props}

object Dashboard {

  def props(): Props = Props(new Dashboard)

}

class Dashboard extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("Dashboard started")
  }

  override def postStop(): Unit = {
    log.info("Dashboard stopped")
  }

  override def receive: Receive = {
    case App.ServicePing => sender() ! App.ServicePong
  }
}
