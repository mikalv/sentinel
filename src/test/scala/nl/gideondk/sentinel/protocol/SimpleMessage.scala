package nl.gideondk.sentinel.protocol

import nl.gideondk.sentinel._

import scala.concurrent.Future

trait SimpleMessageFormat {
  def payload: String
}

case class SimpleCommand(cmd: Int, payload: String) extends SimpleMessageFormat // 1
case class SimpleReply(payload: String) extends SimpleMessageFormat // 2
case class SimpleStreamChunk(payload: String) extends SimpleMessageFormat // 3
case class SimpleError(payload: String) extends SimpleMessageFormat // 4

trait DefaultSimpleMessageHandler extends Resolver[SimpleMessageFormat] {
  def process = {
    case SimpleStreamChunk(x) ⇒ if (x.length > 0) ConsumerAction.ConsumeStreamChunk else ConsumerAction.EndStream
    case x: SimpleError       ⇒ ConsumerAction.AcceptError
    case x: SimpleReply       ⇒ ConsumerAction.AcceptSignal
  }
}

object SimpleMessage {
  val PING_PONG = 1
  val TOTAL_CHUNK_SIZE = 2
  val GENERATE_NUMBERS = 3
  val CHUNK_LENGTH = 4
  val ECHO = 5
}

import SimpleMessage._

object SimpleClientHandler extends DefaultSimpleMessageHandler

//object SimpleServerHandler extends DefaultSimpleMessageHandler {
//
//  override def process = super.process orElse {
//    case SimpleCommand(PING_PONG, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply("PONG")) }
//    case SimpleCommand(TOTAL_CHUNK_SIZE, payload) ⇒ ProducerAction.ConsumeStream { x: SimpleCommand ⇒
//      s: Enumerator[SimpleStreamChunk] ⇒
//        s |>>> Iteratee.fold(0) { (b, a) ⇒ b + a.payload.length } map (x ⇒ SimpleReply(x.toString))
//    }
//    case SimpleCommand(GENERATE_NUMBERS, payload) ⇒ ProducerAction.ProduceStream { x: SimpleCommand ⇒
//      val count = payload.toInt
//      Future((Enumerator(List.range(0, count): _*) &> Enumeratee.map(x ⇒ SimpleStreamChunk(x.toString))) >>> Enumerator(SimpleStreamChunk("")))
//    }
//    case SimpleCommand(ECHO, payload) ⇒ ProducerAction.Signal { x: SimpleCommand ⇒ Future(SimpleReply(x.payload)) }
//  }
//}