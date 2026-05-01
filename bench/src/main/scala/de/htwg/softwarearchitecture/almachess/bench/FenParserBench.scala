package de.htwg.softwarearchitecture.almachess.bench

import de.htwg.softwarearchitecture.almachess.parser.FenParser
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-XX:+UseG1GC", "-Xms512m", "-Xmx512m"))
class FenParserBench:

  // Standard starting position — exercises the full 8x8 board, all piece types.
  val startFen: String =
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  // Mid-game position with empty squares mixed in — forces the digit-expansion path.
  val midFen: String =
    "r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P4/2PBPN2/PP3PPP/RNBQ1RK1 w - - 2 7"

  // Pathological: all 8 ranks are "8" (empty) — stresses the digit branch only.
  val emptyFen: String =
    "8/8/8/8/8/8/8/8 w - - 0 1"

  @Benchmark
  def parseStart(bh: Blackhole): Unit =
    bh.consume(FenParser.parse(startFen))

  @Benchmark
  def parseMid(bh: Blackhole): Unit =
    bh.consume(FenParser.parse(midFen))

  @Benchmark
  def parseEmpty(bh: Blackhole): Unit =
    bh.consume(FenParser.parse(emptyFen))
