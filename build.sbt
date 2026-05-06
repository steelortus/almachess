ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"

lazy val akkaVersion     = "2.8.8"
lazy val akkaHttpVersion = "10.5.3"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "AlmaChess",
    coverageExcludedPackages := "de\\.htwg\\.softwarearchitecture\\.almachess\\.Main\\$?;de\\.htwg\\.softwarearchitecture\\.almachess\\.CliMain\\$?;de\\.htwg\\.softwarearchitecture\\.almachess\\.api\\.Server\\$?;de\\.htwg\\.softwarearchitecture\\.almachess\\.view\\..*",
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
      "com.typesafe.slick" %% "slick"          % "3.5.1",
      "com.typesafe.slick" %% "slick-hikaricp" % "3.5.1",
      "org.postgresql"      % "postgresql"     % "42.7.4",
      ("org.mongodb.scala" %% "mongo-scala-driver" % "4.11.1").cross(CrossVersion.for3Use2_13),
      "io.lettuce"          % "lettuce-core"       % "6.3.2.RELEASE",
      ("com.typesafe.akka" %% "akka-http-testkit"  % akkaHttpVersion % Test).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test).cross(CrossVersion.for3Use2_13),
      "org.scalatest" %% "scalatest" % "3.2.17" % Test
    ),
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    evictionErrorLevel := Level.Warn
  )

// JMH micro-benchmarks. Depends on root so we can benchmark FenParser directly.
lazy val bench = (project in file("bench"))
  .enablePlugins(JmhPlugin)
  .dependsOn(root)
  .settings(
    name := "almachess-bench",
    scalaVersion := "3.3.4",
    publish / skip := true,
    Jmh / fork := true
  )

// Gatling load tests. Separate subproject so its Scala 2.13 / akka transitive deps
// don't collide with the Scala 3 main build.
lazy val gatlingTests = (project in file("perf/gatling"))
  .enablePlugins(GatlingPlugin)
  .settings(
    name := "almachess-gatling",
    scalaVersion := "2.13.14",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.11.5" % "test",
      "io.gatling"            % "gatling-test-framework"    % "3.11.5" % "test"
    )
  )
