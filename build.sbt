ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"

lazy val akkaVersion     = "2.8.8"
lazy val akkaHttpVersion = "10.5.3"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "AlmaChess",
    Compile / mainClass := Some("de.htwg.softwarearchitecture.almachess.api.Server"),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-swing" % "3.0.0",
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      "com.lihaoyi" %% "upickle" % "3.3.1",
      ("com.typesafe.akka" %% "akka-actor-typed"     % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-stream"          % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-http"            % akkaHttpVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-slf4j"           % akkaVersion).cross(CrossVersion.for3Use2_13),
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      ("com.typesafe.akka" %% "akka-http-testkit"  % akkaHttpVersion % Test).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test).cross(CrossVersion.for3Use2_13),
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    evictionErrorLevel := Level.Warn
  )
