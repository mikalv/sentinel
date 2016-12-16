package nl.gideondk.sentinel

import akka.actor.ActorRef
import akka.stream.scaladsl.Source

import scala.concurrent.Promise

trait Event[A]

case class SingularEvent[A](data: A) extends Event[A]

case class SingularErrorEvent[A](data: A) extends Event[A]

case class StreamEvent[A](chunks: Source[A, Any]) extends Event[A]

trait Registration[A, E <: Event[A]] {
  def promise: Promise[E]
}

object Registration {

  case class SingularResponseRegistration[A](promise: Promise[SingularEvent[A]]) extends Registration[A, SingularEvent[A]]

  case class StreamReplyRegistration[A](promise: Promise[StreamEvent[A]]) extends Registration[A, StreamEvent[A]]

}

trait Command[Out, In] {
  def registration: Registration[In, _]
}

trait ServerCommand[Out, In]

trait ServerMetric

//trait Command[Out]

object Command {

  import Registration._

  case class Ask[Out, In](payload: Out, registration: SingularResponseRegistration[In]) extends Command[Out, In]

  case class Tell[Out, In](payload: Out, registration: SingularResponseRegistration[In]) extends Command[Out, In]

  case class AskStream[Out, In](payload: Out, registration: StreamReplyRegistration[In]) extends Command[Out, In]

  case class SendStream[Out, In](stream: Source[Out, Any], registration: StreamReplyRegistration[In]) extends Command[Out, In]

}

object ServerCommand {

  case class AskAll[Cmd, Evt](payload: Cmd, promise: Promise[List[Evt]]) extends ServerCommand[Cmd, Evt]

  case class AskAllHosts[Cmd, Evt](payload: Cmd, promise: Promise[List[Evt]]) extends ServerCommand[Cmd, Evt]

  case class AskAny[Cmd, Evt](payload: Cmd, promise: Promise[Evt]) extends ServerCommand[Cmd, Evt]

}

object ServerMetric {

  case object ConnectedSockets extends ServerMetric

  case object ConnectedHosts extends ServerMetric

}

//object Reply {
//
//  case class Response[Cmd](payload: Cmd) extends Reply[Cmd]
//
//  case class StreamResponseChunk[Cmd](payload: Cmd) extends Reply[Cmd]
//
//}

object Management {

  trait ManagementMessage

  case class RegisterTcpHandler(h: ActorRef) extends ManagementMessage

}

