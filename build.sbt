ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "AlmaChess",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-swing" % "3.0.0",
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      "com.lihaoyi" %% "upickle" % "3.3.1",
      "org.http4s" %% "http4s-dsl" % "1.0.0-M41",
      "org.http4s" %% "http4s-blaze-server" % "1.0.0-M41",
      "org.http4s" %% "http4s-circe" % "1.0.0-M41",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      "org.typelevel" %% "log4cats-core" % "2.6.0",
      "org.typelevel" %% "log4cats-slf4j" % "2.6.0",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    evictionErrorLevel := Level.Warn
  )