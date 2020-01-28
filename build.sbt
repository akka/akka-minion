organization := "akka"
name := "akka-minion"

scalaVersion := "2.12.9"
scalacOptions ++= List(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-encoding",
  "UTF-8"
)

val AkkaVersion = "2.6.2"
val AkkaHttpVersion = "10.1.11"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-actor"           % AkkaVersion,
  "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
  "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
  "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.lihaoyi"        %% "scalatags"            % "0.8.4",
  "io.spray"           %% "spray-json"           % "1.3.5",
  "com.github.blemale" %% "scaffeine"            % "3.1.0"
)

scalafmtOnCompile := true

enablePlugins(JavaAppPackaging)
