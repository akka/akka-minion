organization := "akka"
name := "akka-minion"

scalaVersion := "2.13.1"
scalacOptions ++= List(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-encoding",
  "UTF-8"
)

val AkkaVersion = "2.6.9"
val AkkaHttpVersion = "10.2.1"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-actor"           % AkkaVersion,
  "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
  "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
  "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.lihaoyi"        %% "scalatags"            % "0.9.2",
  "io.spray"           %% "spray-json"           % "1.3.5",
  "com.github.blemale" %% "scaffeine"            % "4.0.1"
)

scalafmtOnCompile := true

enablePlugins(JavaAppPackaging)
