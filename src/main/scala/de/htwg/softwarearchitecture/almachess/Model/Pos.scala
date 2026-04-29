package de.htwg.softwarearchitecture.almachess.model

case class Pos(rank: Int, file: Int):
  def isInside: Boolean = rank >= 0 && rank < 8 && file >= 0 && file < 8

  def +(delta: (Int, Int)): Pos = Pos(rank + delta._1, file + delta._2)

  def toAlgebraic: String =
    s"${('a' + file).toChar}${('1' + rank).toChar}"

  def map(f: Int => Int): Pos = Pos(f(rank), f(file))
  def flatMap(f: Pos => Pos): Pos = f(this)
  def foreach(f: Int => Unit): Unit =
    f(rank)
    f(file)

object Pos:
  def fromAlgebraic(square: String): Option[Pos] =
    if square.length != 2 then None
    else
      val f = square(0).toLower - 'a'
      val r = square(1) - '1'
      val pos = Pos(r, f)
      Option.when(pos.isInside)(pos)
