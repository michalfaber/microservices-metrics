name := "latency-simulator"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.fasterxml.uuid" % "java-uuid-generator" % "3.1.4",
  "net.benmur" %% "riemann-scala-client" % "0.4.0"
)
