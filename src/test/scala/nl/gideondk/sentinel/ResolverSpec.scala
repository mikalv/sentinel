package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape}
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source}
import nl.gideondk.sentinel.protocol.{SimpleClientHandler, SimpleCommand, SimpleMessageFormat, SimpleReply}
import org.scalatest._
import protocol.SimpleMessage._

import scala.concurrent._
import duration._
import akka.testkit.{ImplicitSender, TestKit}

object ResolverSpec {
}

class ResolverStageSpec extends TestKit(ActorSystem("ResolverStageSpec")) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {
  implicit val materializer = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "A ResolveStage should handle incoming events" in {
    val resultSink = Sink.head[Response[SimpleMessageFormat]]
    val ignoreSink = Sink.ignore

    val stage = new ResolverStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)

    val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b => sink =>
      import GraphDSL.Implicits._

      val s = b.add(stage)

      Source.single(SimpleReply("")) ~> s.in
      s.out1 ~> sink.in
      s.out0 ~> ignoreSink

      ClosedShape
    })


    val response = g.run()
    Await.result(response, 300.millis) should be (SingularResponse(SimpleReply("")))
  }
}