This is an example of instrumentation microservices to measure all latencies during end to end processing of an event. It requires Riemann, Influxdb and Grafana.  

* Riemann – tool for monitoring distributed systems, aggregates events from systems, process them and send further.
* Influxdb – storage for time-series data.
* Grafana – metrics & analytics dashboards.

Here is a hypothetical flow for which we want to measure all sub-latencies.

![alt text](https://github.com/michalfaber/microservices-metrics/raw/images/pic1a.png "Flow diagram")

Service A pulls message from an external/legacy system (RabbitMQ), performs preprocessing and sends a modified event to service B. The flow splits and new messages are being sent in parallel to service C and service D for further processing. 


### How metrics are collected

After a new message arrives, Service A generates correlation id and submits tracing event to Riemann (begin of a segment). This correlation id has to be attached to a message before sending further to Kafka.

```scala
service(“segment1”) |
      metric(System.currentTimeMillis()) |
      attributes(("correlation_id", correlationId)) |
      ttl(60) |>> metricsDestination
```
After receiving a message from Kafka, service B submits tracing event to Riemann (end of a segment). correlationId travels with a message and is used to pair both ends of a segment.

```scala
service(“segment1”) |
      metric(System.currentTimeMillis()) |
      attributes(("correlation_id", correlationId)) |
      ttl(60) |>> metricsDestination
```

Riemann receives 2 almost identical events with the same correlation id, service name but different timestamp. The difference between those 2 timestamps (both ends can arrive in any order) is a latency which can be either reinjected to Riemann as a new event or directly enqueued for sending to influxdb.

Here is a piece of riemann.config responsible for pairing events and calculating diff.

```clojure
...
(where (service #"event_XYZ_latencies")
    (by [:correlation_id :state]
        (fixed-event-window 2
          (smap (fn [events]
              (let [fst (nth events 0)
                    lst (nth events 1)
                    dif (Math/abs (-  (:metric fst) (:metric lst)))]
               (event {:service (:service lst) :host (:host lst) :state (:state lst) :metric dif :time (:metric lst)})))
                influx-sender)
          )))
...          
```


### Grafana dashboard

Influxdb stores time series data which can be interpreted on Grafana dashboards. 
Grafana offers a nice property of stacking metrics in a graph, useful aggregation functions, sampling. We can sum up all latencies, get max of some set of latencies etc. 

Here is the result of running latency-simulator for 4 minutes.  For the purpose of the simulation two latencies are represented by a sin function and one as a constant around 1000ms. 

![alt text](https://github.com/michalfaber/microservices-metrics/raw/images/pic2.png "Grafana simulation dashboard")

