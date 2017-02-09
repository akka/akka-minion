name := "akka-minion"

organization := "akka"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.8"

val AkkaVersion = "2.4.16"

val AkkaHttpVersion = "10.0.3"

resolvers += Resolver.typesafeRepo("releases")

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-stream" % AkkaVersion

libraryDependencies += "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion

libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion

libraryDependencies += "com.lihaoyi" %% "scalatags" % "0.6.3"

libraryDependencies += "io.spray" %%  "spray-json" % "1.3.3"

enablePlugins(JavaAppPackaging)
