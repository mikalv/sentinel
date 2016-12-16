package nl.gideondk.sentinel

import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Merge, Source}
import akka.stream.stage.GraphStageLogic._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import nl.gideondk.sentinel.Command.Tell
import nl.gideondk.sentinel.ConsumerAction._
import nl.gideondk.sentinel.Registration.SingularResponseRegistration

import scala.concurrent.{ExecutionContext, Future, Promise}

class ConsumerStage[Evt, Cmd](resolver: Resolver[Evt]) extends GraphStage[FanOutShape2[Evt, (Evt, ProducerAction[Evt, Cmd]), Event[Evt]]] {

  private val eventIn = Inlet[Evt]("EventIn")
  private val actionsOut = Outlet[(Evt, ProducerAction[Evt, Cmd])]("ActionOut")
  private val signalOut = Outlet[Event[Evt]]("SignalOut")

  val shape = new FanOutShape2(eventIn, actionsOut, signalOut)

  override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
    private var chunkSource: SubSourceOutlet[Evt] = _

    private def chunkSubStreamStarted = chunkSource != null

    private def idle = this

    def setInitialHandlers(): Unit = setHandlers(eventIn, signalOut, idle)

    /*
    *
    * Substream Logic
    *
    * */

    val pullThroughHandler = new OutHandler {
      override def onPull() = {
        pull(eventIn)
      }
    }

    val substreamHandler = new InHandler with OutHandler {
      def endStream(): Unit = {
        chunkSource.complete()
        chunkSource = null

        if (isAvailable(signalOut)) pull(eventIn)
        setInitialHandlers()
      }

      override def onPush(): Unit = {
        val chunk = grab(eventIn)
        resolver.process(chunk) match {
          case ConsumeStreamChunk ⇒
            chunkSource.push(chunk)

          case EndStream ⇒
            endStream()

          case ConsumeChunkAndEndStream ⇒
            chunkSource.push(chunk)
            endStream()

          case Ignore ⇒ ()
        }
      }

      override def onPull(): Unit = {
        pull(eventIn)
      }

      override def onUpstreamFinish(): Unit = {
        chunkSource.complete()
        completeStage()
      }

      override def onUpstreamFailure(reason: Throwable): Unit = {
        chunkSource.fail(reason)
        failStage(reason)
      }
    }

    def startStream(initialChunk: Option[Evt]): Unit = {
      chunkSource = new SubSourceOutlet[Evt]("ChunkSource")
      chunkSource.setHandler(pullThroughHandler)
      setHandler(eventIn, substreamHandler)
      setHandler(signalOut, substreamHandler)

      initialChunk match {
        case Some(x) ⇒ push(signalOut, StreamEvent(Source.single(x) ++ Source.fromGraph(chunkSource.source)))
        case None ⇒ push(signalOut, StreamEvent(Source.fromGraph(chunkSource.source)))
      }
    }

    def onPush(): Unit = {
      val evt = grab(eventIn)

      resolver.process(evt) match {
        case x: ProducerAction[Evt, Cmd] ⇒ emit(actionsOut, (evt, x))

        case AcceptSignal ⇒ push(signalOut, SingularEvent(evt))

        case AcceptError ⇒ push(signalOut, SingularErrorEvent(evt))

        case StartStream ⇒ startStream(None)

        case ConsumeStreamChunk ⇒ startStream(Some(evt))

        case ConsumeChunkAndEndStream ⇒ push(signalOut, StreamEvent(Source.single(evt)))

        case Ignore ⇒ ()
      }
    }

    def onPull(): Unit = {
      if (!chunkSubStreamStarted) pull(eventIn)
    }

    setHandler(actionsOut, EagerTerminateOutput)

    setInitialHandlers()
  }
}

//object ConsumerStage {
//  def bidi[Evt, Cmd](resolver: Resolver[Evt], producerParallism: Int)(implicit ec: ExecutionContext) = {
//
//    val consumerStage = new ConsumerStage[Evt, Cmd](resolver)
//    val producerStage = new ProducerStage[Evt, Cmd]()
//
//    val functionApply = Flow[(Evt, ProducerAction[Evt, Cmd])].mapAsync[Command[Cmd, Evt]](producerParallism) {
//      case (evt, x: ProducerAction.Signal[Evt, Cmd]) ⇒ x.f(evt).map(x ⇒ Tell[Cmd, Evt](x, SingularResponseRegistration(Promise[SingularEvent[Evt]]())))
//    }
//
//    BidiFlow.fromGraph(GraphDSL.create() { b ⇒
//      import GraphDSL.Implicits._
//
//      val producer = b.add(producerStage)
//      val consumer = b.add(consumerStage)
//
//      val commandIn = Inlet[Cmd]("CommandInput")
//      val inMerge = Merge[Command[Cmd, Evt]](2)
//
//      // commandIn ~> inMerge ~> producer.in
//      // consumer.out0 ~> functionApply ~> inMerge
//
//      BidiShape(commandIn, producer.out, consumer.in, consumer.out1)
//    })
//  }
//}
