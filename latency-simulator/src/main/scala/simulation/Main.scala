package simulation

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import com.typesafe.config.Config
import net.benmur.riemann.client.RiemannClient.{riemannConnectAs, Unreliable}
import net.benmur.riemann.client.UnreliableIO._
import net.benmur.riemann.client.EventSenderDSL._
import akka.actor.ActorSystem
import akka.util.Timeout
import net.benmur.riemann.client.EventDSL._
import scala.concurrent.{Future, Await, Promise}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.Random

object Main extends App {
  implicit val system = ActorSystem()
  implicit val timeout = Timeout(5.seconds)
  implicit val executor = system.dispatcher

  val config: Config = system.settings.config
  val scheduler = system.scheduler

  val metricsName = "event_XYZ_latencies"

  val maxEvents = 1000
  val maxTRad = 6 * 2 * Math.PI
  val maxTMillisec = 4*60000
  val maxLatency = 3000 //millisec

  val metricsDestination = riemannConnectAs[Unreliable] to
    new InetSocketAddress(config.getString("riemann.host"), 5555) withValues host("localhost")

  println(s"Generating samples...")

  val times = (1 to maxEvents).map(_ => {
    // generate sample (start time delay and latencies) within a 4 seconds window
    val startTimeDelay = Random.nextInt(maxTMillisec)
    val startTimeDelayRad = startTimeDelay * maxTRad / maxTMillisec

    // latency1 = sin(startTime) ms
    val latency1 = ((Math.sin(startTimeDelayRad) + 1) / 2) * maxLatency + Random.nextInt(200)

    // latency2 = sin(startTime + shift) ms
    val shift = 17000 * maxTRad / maxTMillisec
    val latency2 =  ((Math.sin(startTimeDelayRad + shift) + 1) / 2) * maxLatency + Random.nextInt(200)

    // latency3 = 1000 ms
    val latency3 =  1000 + Random.nextInt(200)

    (startTimeDelay, latency1.toInt, latency2.toInt, latency3.toInt)
  }).toList


  def metricsSavepoint(segment: String, position: String, correlationId: String) = {
    service(segment) |
      state(position) |
      metric(System.currentTimeMillis()) |
      attributes(("correlation_id", correlationId)) |
      ttl(60) |>> metricsDestination
  }

  def simulation = times.map { case (startTimeDelay, latency1, latency2, latency3) =>
    val correlationId = UUIDGenerator.generate
    val prom = Promise[Int]()

    val delay = Duration(startTimeDelay, TimeUnit.MILLISECONDS)
    scheduler.scheduleOnce(delay, runnable = new Runnable {
      override def run(): Unit = {
        println(s"start event processing at 0time+$startTimeDelay ms (end of simulation at 0time+$maxTMillisec ms)")

        // IN SEVICE A

        // event arrived in the service A - start measurement
        metricsSavepoint(metricsName, "latency A -> B", correlationId)

        val receiveTime = Duration(latency1, TimeUnit.MILLISECONDS)
        scheduler.scheduleOnce(receiveTime, runnable = new Runnable {
          override def run(): Unit = {

            // IN SEVICE B

            // event received in service B
            metricsSavepoint(metricsName, "latency A -> B", correlationId)

            // two sub events created and sent in parallel to sevices C and D
            metricsSavepoint(metricsName, "latency B -> C", correlationId)
            metricsSavepoint(metricsName, "latency B -> D", correlationId)

            val promS1 = Promise[Int]()
            val promS2 = Promise[Int]()
            val receiveTime2 = Duration(latency2, TimeUnit.MILLISECONDS)
            scheduler.scheduleOnce(receiveTime2, runnable = new Runnable {
              override def run(): Unit = {

                // IN SEVICE C

                // event received and successfully processed in service C
                metricsSavepoint(metricsName, "latency B -> C", correlationId)

                promS1.success(0)
              }
            })
            val receiveTime3 = Duration(latency3, TimeUnit.MILLISECONDS)
            scheduler.scheduleOnce(receiveTime3, runnable = new Runnable {
              override def run(): Unit = {

                // IN SEVICE D

                // event received and successfully processed in service D
                metricsSavepoint(metricsName, "latency B -> D", correlationId)

                promS2.success(0)
              }
            })

            Future.sequence(List(promS1.future, promS2.future)).onSuccess {

              // end of processing - event fully processed
              case _ => prom.success(0)
            }
          }
        })
      }
    })
    prom.future
  }

  Await.result(Future.sequence(simulation),5 minute)

  println(s"End of simulation")

  system.shutdown()
}
