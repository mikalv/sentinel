package nl.gideondk.sentinel

import akka.stream._
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Source}
import akka.stream.stage.GraphStageLogic._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import nl.gideondk.sentinel.Command.{Ask, Tell}
import nl.gideondk.sentinel.ConsumerAction._

class ProducerStage[In, Out] extends GraphStage[FlowShape[Command[Out, In], Out]] {
  private val in = Inlet[Command[Out, In]]("CommandIn")
  private val out = Outlet[Out]("CommandOut")

  val shape = new FlowShape(in, out)

  override def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
    setHandler(in, new InHandler {
      override def onPush(): Unit = grab(in) match {
        case x: Ask[Out, In] ⇒ push(out, x.payload)
        case x: Tell[Out, In] ⇒ push(out, x.payload)
      }
    })
    setHandler(out, eagerTerminateOutput)
  }

}
