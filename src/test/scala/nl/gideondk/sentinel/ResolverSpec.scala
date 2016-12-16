package nl.gideondk.sentinel

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Attributes, ClosedShape }
import akka.stream.scaladsl.{ Flow, GraphDSL, RunnableGraph, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import nl.gideondk.sentinel.protocol._
import org.scalatest._
import protocol.SimpleMessage._

import scala.concurrent._
import duration._

object ConsumerStageSpec {
  val eventFlow = Flow[Event[SimpleMessageFormat]].flatMapConcat {
    case x: StreamEvent[SimpleMessageFormat] => x.chunks
    case x: SingularEvent[SimpleMessageFormat] => Source.single(x.data)
  }
}

class ConsumerStageSpec extends AkkaSpec {
  "The ConsumerStage" should {
     "handle incoming events" in {
       implicit val materializer = ActorMaterializer()

       val resultSink = Sink.head[Event[SimpleMessageFormat]]
       val ignoreSink = Sink.ignore

       val stage = new ConsumerStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)

       val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b ⇒
         sink ⇒
           import GraphDSL.Implicits._

           val s = b.add(stage)

           Source.single(SimpleReply("")) ~> s.in
           s.out1 ~> sink.in
           s.out0 ~> ignoreSink

           ClosedShape
       })

       val response = g.run()
       Await.result(response, 300.millis) should be(SingularEvent(SimpleReply("")))
     }

     "handle multiple incoming events" in {
       implicit val materializer = ActorMaterializer()

       val resultSink = Sink.seq[Event[SimpleMessageFormat]]
       val ignoreSink = Sink.ignore

       val stage = new ConsumerStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)

       val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b ⇒
         sink ⇒
           import GraphDSL.Implicits._

           val s = b.add(stage)

           Source(List(SimpleReply("A"), SimpleReply("B"), SimpleReply("C"))) ~> s.in
           s.out1 ~> sink.in
           s.out0 ~> ignoreSink

           ClosedShape
       })

       val response = g.run()
       Await.result(response, 300.millis) should be(Vector(SingularEvent(SimpleReply("A")), SingularEvent(SimpleReply("B")), SingularEvent(SimpleReply("C"))))
     }

     "not lose demand that comes in while handling incoming streams" in {
       implicit val materializer = ActorMaterializer()

       val inProbe = TestPublisher.manualProbe[SimpleMessageFormat]()
       val responseProbe = TestSubscriber.manualProbe[Event[SimpleMessageFormat]]

       val ignoreSink = Sink.ignore

       val stage = new ConsumerStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)

       val g = RunnableGraph.fromGraph(GraphDSL.create(Sink.fromSubscriber(responseProbe)) { implicit b ⇒
         sink ⇒
           import GraphDSL.Implicits._

           val s = b.add(stage)

           Source.fromPublisher(inProbe) ~> s.in
           s.out1 ~> sink.in
           s.out0 ~> ignoreSink

           ClosedShape
       })

       g.withAttributes(Attributes.inputBuffer(1, 1)).run()

       val inSub = inProbe.expectSubscription()
       val responseSub = responseProbe.expectSubscription()

       // Pull first response
       responseSub.request(1)

       // Expect propagation towards source
       inSub.expectRequest(1)

       // Push one element into stream
       inSub.sendNext(SimpleStreamChunk("A"))

       // Expect element flow towards response output
       val response = responseProbe.expectNext()

       val entityProbe = TestSubscriber.manualProbe[SimpleMessageFormat]()
       response.asInstanceOf[StreamEvent[SimpleMessageFormat]].chunks.to(Sink.fromSubscriber(entityProbe)).run()

       // Expect a subscription is made for the sub-stream
       val entitySub = entityProbe.expectSubscription()

       // Request the initial element from the sub-source
       entitySub.request(1)

       //      // Pull is coming from merged stream for initial element
       //      inSub.expectRequest(1)

       // Expect initial element to be available
       entityProbe.expectNext()

       // Request an additional chunk
       entitySub.request(1)

       // Merged stream is empty, so expect demand to be propagated towards the source
       inSub.expectRequest(1)

       // Send successive element
       inSub.sendNext(SimpleStreamChunk("B"))

       // Expect the element to be pushed directly into the sub-source
       entityProbe.expectNext()

       responseSub.request(1)

       inSub.sendNext(SimpleStreamChunk(""))
       entityProbe.expectComplete()

       // and that demand should go downstream
       // since the chunk end was consumed by the stage
       inSub.expectRequest(1)
     }

    "correctly output stream responses" in {
      implicit val materializer = ActorMaterializer()

      val resultSink = Sink.seq[SimpleMessageFormat]
      val eventFlow = Flow[Event[SimpleMessageFormat]].flatMapConcat {
        case x: StreamEvent[SimpleMessageFormat] => x.chunks
      }

      val ignoreSink = Sink.ignore

      val stage = new ConsumerStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)
      val chunkSource = Source(List(SimpleStreamChunk("A"), SimpleStreamChunk("B"), SimpleStreamChunk("C"), SimpleStreamChunk("")))

      val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b ⇒
        sink ⇒
          import GraphDSL.Implicits._

          val s = b.add(stage)

          chunkSource ~> s.in
          s.out1 ~> ConsumerStageSpec.eventFlow ~> sink.in
          s.out0 ~> ignoreSink

          ClosedShape
      })

      Await.result(g.run(), 300.millis) should be(Seq(SimpleStreamChunk("A"), SimpleStreamChunk("B"), SimpleStreamChunk("C")))
    }

    "correctly output multiple stream responses" in {
      implicit val materializer = ActorMaterializer()

      val resultSink = Sink.seq[SimpleMessageFormat]
      val eventFlow = Flow[Event[SimpleMessageFormat]].flatMapConcat {
        case x: StreamEvent[SimpleMessageFormat] => x.chunks
      }

      val ignoreSink = Sink.ignore

      val stage = new ConsumerStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)
      val items = List.fill(10)(List(SimpleStreamChunk("A"), SimpleStreamChunk("B"), SimpleStreamChunk("C"), SimpleStreamChunk(""))).flatten
      val chunkSource = Source(items)

      val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b ⇒
        sink ⇒
          import GraphDSL.Implicits._

          val s = b.add(stage)

          chunkSource ~> s.in
          s.out1 ~> ConsumerStageSpec.eventFlow ~> sink.in
          s.out0 ~> ignoreSink

          ClosedShape
      })

      Await.result(g.run(), 300.millis) should be(items.filter(_.payload.length > 0))
    }

    "correctly handle asymmetrical message types" in {
      implicit val materializer = ActorMaterializer()

      val resultSink = Sink.seq[SimpleMessageFormat]

      val ignoreSink = Sink.ignore

      val stage = new ConsumerStage[SimpleMessageFormat, SimpleMessageFormat](SimpleClientHandler)
      val a = List(SimpleReply("A"), SimpleReply("B"), SimpleReply("C"))
      val b = List.fill(10)(List(SimpleStreamChunk("A"), SimpleStreamChunk("B"), SimpleStreamChunk("C"), SimpleStreamChunk(""))).flatten
      val c = List(SimpleReply("A"), SimpleReply("B"), SimpleReply("C"))
      val chunkSource = Source(a ++ b ++ c)

      val g = RunnableGraph.fromGraph(GraphDSL.create(resultSink) { implicit b ⇒
        sink ⇒
          import GraphDSL.Implicits._

          val s = b.add(stage)

          chunkSource ~> s.in
          s.out1 ~> ConsumerStageSpec.eventFlow ~> sink.in
          s.out0 ~> ignoreSink

          ClosedShape
      })

      Await.result(g.run(), 300.millis) should be(a ++ b.filter(_.payload.length > 0) ++ c)
    }
  }
}