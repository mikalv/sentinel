package nl.gideondk.sentinel

import akka.actor.{ Actor, ActorSystem }
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{ BidiFlow, Flow, Tcp }
import akka.util.ByteString

import scala.concurrent.Future

object Pipeline {
  //def apply(host: )
}

object ConnectionManager {
  case class Connect(host: String, port: Int)
}

class ConnectionManager[Cmd, Evt](protocol: BidiFlow[ByteString, Evt, Cmd, ByteString, Any], resolver: Resolver[Evt]) extends Actor {
  import ConnectionManager._

  import context.system

  def receive: Receive = {
    case Connect(host, port) ⇒
      val connection = Tcp().outgoingConnection(host, port)
      //      val c = connection.join(protocol).join(ConsumerStage.bidi(resolver))
      //      c
      //.join(ConsumerStage.bidi(resolver))
      ()
  }
}
