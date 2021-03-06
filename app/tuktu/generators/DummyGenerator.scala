package tuktu.generators

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import akka.actor.ActorRef
import akka.actor.Cancellable
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.Enumeratee
import play.api.libs.json._
import akka.actor.Props
import akka.actor.ActorLogging
import akka.actor.Actor
import akka.pattern.ask
import tuktu.api._
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicInteger
import play.api.Logger

object DummyHelper {
    def valToType(value: String, outputType: String) = outputType match {
        case "double" => value.toDouble
        case "int" => value.toInt
        case "boolean" => value.toBoolean
        case "jsobject" => Json.parse(value).as[JsObject]
        case "jsarray" => Json.parse(value).as[JsArray]
        case _ => value
    }
}

/**
 * Just generates dummy strings every tick
 */
class DummyGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var schedulerActor: Cancellable = null
    var message: String = null
    var maxAmount: Option[Int] = None
    var amountSent = new AtomicInteger(0)
    var outputType: String = _
    
    override def _receive = {
        case config: JsValue => {
            // Get the ticking frequency
            val tickInterval = (config \ "interval").as[Int]
            // Get the message to send
            message = (config \ "message").as[String]

            // See if we need to stop at some point
            maxAmount = (config \ "max_amount").asOpt[Int]

            outputType = (config \ "type").asOpt[String].getOrElse("string").toLowerCase

            // Determine initial waiting time before sending
            val initialDelay = {
              if((config \ "send_immediately").asOpt[Boolean].getOrElse(false))
                Duration.Zero
              else
                tickInterval milliseconds
            }

            // Set up the scheduler
            schedulerActor = Akka.system.scheduler.schedule(
                    initialDelay,
                    tickInterval milliseconds,
                    self,
                    message)
        }
        case sp: StopPacket => {
            schedulerActor.cancel
            cleanup
        }
        case msg: String => {
            maxAmount match {
                // check if we need to stop
                case Some(amnt) => {
                    if (amountSent.getAndIncrement >= amnt)
                            self ! new StopPacket
                    else
                        channel.push(DataPacket(List(Map(resultName -> DummyHelper.valToType(message, outputType)))))
                }
                case None => {
                    channel.push(DataPacket(List(Map(resultName -> DummyHelper.valToType(message, outputType)))))
                }
            }
        }
    }
}

class RandomActor(maxNum: Int) extends Actor with ActorLogging {
    val r = scala.util.Random
    def receive() = {
        case _ => sender ! r.nextInt(maxNum)
    }
}

/**
 * Generates random numbers
 */
class RandomGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var schedulerActor: Cancellable = null
    var maxNum = 0
    var randomActor: ActorRef = null
    
    override def _receive = {
        case config: JsValue => {
            // Get the ticking frequency
            val tickInterval = (config \ "interval").as[Int]
            maxNum = (config \ "max").as[Int]

            // Set up actor that will make random numbers
            randomActor = Akka.system.actorOf(Props(classOf[RandomActor], maxNum))

            // Set up the scheduler
            schedulerActor = Akka.system.scheduler.schedule(
                    tickInterval milliseconds,
                    tickInterval milliseconds,
                    self,
                    1)
        }
        case sp: StopPacket => {
            schedulerActor.cancel
            cleanup
        }
        case one: Int => {
            val fut = randomActor ? one
            fut.onSuccess {
                case num: Int => channel.push(DataPacket(List(Map(resultName -> num))))
            }
        }
    }
}

/**
 * Generates a list of values
 */
class ListGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var randomActor: ActorRef = null
    var vals = List[String]()
    var separate = true
    var outputType: String = _

    override def _receive = {
        case config: JsValue => {
            // Get the values
            vals = (config \ "values").as[List[String]]
            separate = (config \ "separate").asOpt[Boolean].getOrElse(true)
            outputType = (config \ "type").asOpt[String].getOrElse("string").toLowerCase
            
            // Send message to self
            self ! 0
        }
        case num: Int => {
            if(separate) {
              channel.push(DataPacket(List(Map(resultName -> DummyHelper.valToType(vals(num), outputType)))))
              // See if we're done or not
              if (num < vals.size - 1) self ! (num + 1)
              else self ! new StopPacket
            } else {
                channel.push(DataPacket(vals.map(v => Map(resultName -> DummyHelper.valToType(v, outputType)))))
                self ! new StopPacket
            }
        }
    }
}

/**
 * Generates a custom data packet every tick
 */
class CustomPacketGenerator(resultName: String, processors: List[Enumeratee[DataPacket, DataPacket]], senderActor: Option[ActorRef]) extends BaseGenerator(resultName, processors, senderActor) {
    var schedulerActor: Cancellable = null
    var packet: DataPacket = _
    var maxAmount: Option[Int] = _
    var amountSent = new AtomicInteger(0)

    override def _receive = {
        case config: JsValue => {
            // Get the ticking frequency
            val tickInterval = (config \ "interval").as[Int]
            // Get the packet to send
            val tpkt = (config \ "packet").as[String]
            val js = (config \ "json").asOpt[Boolean].getOrElse(true)
            val jpkt = Json.parse( tpkt ).as[List[JsObject]]
            packet = DataPacket(jpkt.map{ jobj => if(js) { jobj.as[Map[String,JsValue]]} else { tuktu.api.utils.JsObjectToMap(jobj) } } )

            // See if we need to stop at some point
            maxAmount = (config \ "max_amount").asOpt[Int]

            // Determine initial waiting time before sending
            val initialDelay = {
              if((config \ "send_immediately").asOpt[Boolean].getOrElse(false))
                Duration.Zero
              else
                tickInterval milliseconds
            }

            // Set up the scheduler
            schedulerActor = Akka.system.scheduler.schedule(
                    initialDelay,
                    tickInterval milliseconds,
                    self,
                    packet)
        }
        case sp: StopPacket => {
            schedulerActor.cancel
            cleanup
        }
        case pkt: DataPacket => {
            maxAmount match {
                // check if we need to stop
                case Some(amnt) => {
                    if (amountSent.getAndIncrement >= amnt)
                            self ! new StopPacket
                    else
                        channel.push(pkt)
                }
                case None => {
                    channel.push(pkt)
                }
            }
        }
    }
}