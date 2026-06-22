ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.4"

lazy val akkaVersion     = "2.8.8"
lazy val akkaHttpVersion = "10.5.3"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .settings(
    name := "AlmaChess",
    coverageExcludedPackages := "de\\.htwg\\.softwarearchitecture\\.almachess\\.Main\\$?;de\\.htwg\\.softwarearchitecture\\.almachess\\.CliMain\\$?;de\\.htwg\\.softwarearchitecture\\.almachess\\.api\\.Server\\$?;de\\.htwg\\.softwarearchitecture\\.almachess\\.view\\..*;de\\.htwg\\.softwarearchitecture\\.almachess\\.tools\\..*",
    Compile / mainClass := Some("de.htwg.softwarearchitecture.almachess.api.Server"),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-swing" % "3.0.0",
      "com.lihaoyi" %% "fastparse" % "3.1.1",
      "com.lihaoyi" %% "upickle" % "3.3.1",
      ("com.typesafe.akka" %% "akka-actor-typed"     % akkaVersion).cross(CrossVersion.for3Use2_13),
      ("com.typesafe.akka" %% "akka-stream"          % akkaVersion).cross(CrossVersion.for3Use2_13),
      // Alpakka Kafka — connects Akka Streams to Kafka topics. Producer.plainSink
      // / Consumer.plainSource bridge between our reactive pipelines and the
      // broker, with end-to-end backpressure.
      ("com.typesafe.akka" %% "akka-stream-kafka"    % "4.0.2").cross(CrossVersion.for3Use2_13),
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

// Spark analytics over the `almachess.moves` Kafka topic (and its JSONL file
// dump). Separate Scala 2.13 subproject because Spark publishes no Scala 3
// artifacts — same pattern as the Gatling subproject below. Deliberately does
// NOT dependOn(root): analytics is coupled to the rest of the system only via
// the event format on the topic.
//
// Spark 3.5 supports Java 8/11/17. On newer JDKs set ANALYTICS_JAVA_HOME to a
// JDK 17 installation; the forked run picks it up.
lazy val analytics = (project in file("analytics/spark"))
  .settings(
    name := "almachess-analytics",
    scalaVersion := "2.13.14",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql"            % "3.5.6",
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "3.5.6"
    ),
    Compile / run / fork := true,
    Compile / run / javaHome := sys.env.get("ANALYTICS_JAVA_HOME").map(file),
    // Fork from the repo root so the default `analytics/data/moves.jsonl`
    // path resolves regardless of the subproject's base directory.
    Compile / run / forkOptions := (Compile / run / forkOptions).value
      .withWorkingDirectory((ThisBuild / baseDirectory).value),
    // Spark needs reflective access to JDK internals on Java 17.
    Compile / run / javaOptions ++= Seq(
      "-Xmx1g",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
      "--add-opens=java.base/java.io=ALL-UNNAMED",
      "--add-opens=java.base/java.net=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
      "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
      "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED"
    ),
    // Windows: streaming checkpoints need Hadoop's winutils.exe + hadoop.dll.
    // hadoop.dll is loaded via java.library.path, which does not include
    // %HADOOP_HOME%\bin by default — point it there when HADOOP_HOME is set.
    Compile / run / javaOptions ++=
      sys.env.get("HADOOP_HOME").map(h => s"-Djava.library.path=$h${java.io.File.separator}bin").toSeq,
    evictionErrorLevel := Level.Warn
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
