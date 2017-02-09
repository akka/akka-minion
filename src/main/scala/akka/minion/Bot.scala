package akka.minion

import akka.actor.{Actor, ActorLogging, Props}

object Bot {

  def props(): Props = Props(new Bot)

}

class Bot extends Actor with ActorLogging {

  override def preStart(): Unit = {
    log.info("Minion bot started")
  }

  override def postStop(): Unit = {
    log.info("Minion bot stopped")
  }

  override def receive: Receive = {
    case App.ServicePing => sender() ! App.ServicePong
  }
}
