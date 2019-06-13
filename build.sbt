organization := "akka"
name := "akka-minion"

scalaVersion := "2.12.6"
scalacOptions ++= List(
  "-unchecked",
  "-deprecation",
  "-language:_",
  "-encoding",
  "UTF-8"
)

val AkkaVersion = "2.5.23"
val AkkaHttpVersion = "10.1.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka"  %% "akka-actor"           % AkkaVersion,
  "com.typesafe.akka"  %% "akka-stream"          % AkkaVersion,
  "com.typesafe.akka"  %% "akka-http"            % AkkaHttpVersion,
  "com.typesafe.akka"  %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.lihaoyi"        %% "scalatags"            % "0.7.0",
  "io.spray"           %% "spray-json"           % "1.3.5",
  "com.github.blemale" %% "scaffeine"            % "2.6.0"
)

scalafmtOnCompile in ThisBuild := true

enablePlugins(JavaAppPackaging)
