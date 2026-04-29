package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class PosSpec extends AnyWordSpec with Matchers:

  "A Pos" should {
    "check if inside board bounds" in {
      Pos(0, 0).isInside shouldBe true
      Pos(7, 7).isInside shouldBe true
      Pos(3, 4).isInside shouldBe true
      Pos(-1, 0).isInside shouldBe false
      Pos(0, 8).isInside shouldBe false
      Pos(8, 0).isInside shouldBe false
    }

    "add deltas correctly" in {
      val pos = Pos(1, 2)
      (pos + (1, 1)) shouldBe Pos(2, 3)
      (pos + (-1, 0)) shouldBe Pos(0, 2)
      (pos + (0, -2)) shouldBe Pos(1, 0)
    }

    "convert to algebraic notation" in {
      Pos(0, 0).toAlgebraic shouldBe "a1"
      Pos(7, 7).toAlgebraic shouldBe "h8"
      Pos(3, 4).toAlgebraic shouldBe "e4"
      Pos(1, 1).toAlgebraic shouldBe "b2"
    }

    "support map operation" in {
      val pos = Pos(1, 2)
      pos.map(_ * 2) shouldBe Pos(2, 4)
    }

    "support flatMap operation" in {
      val pos = Pos(1, 2)
      pos.flatMap(p => Pos(p.rank + 1, p.file + 1)) shouldBe Pos(2, 3)
    }

    "support foreach operation" in {
      var sum = 0
      val pos = Pos(3, 4)
      pos.foreach(sum += _)
      sum shouldBe 7
    }
  }

  "Pos.fromAlgebraic" should {
    "parse valid algebraic notation" in {
      Pos.fromAlgebraic("a1") shouldBe Some(Pos(0, 0))
      Pos.fromAlgebraic("h8") shouldBe Some(Pos(7, 7))
      Pos.fromAlgebraic("e4") shouldBe Some(Pos(3, 4))
      Pos.fromAlgebraic("b2") shouldBe Some(Pos(1, 1))
    }

    "reject invalid notation" in {
      Pos.fromAlgebraic("") shouldBe None
      Pos.fromAlgebraic("a") shouldBe None
      Pos.fromAlgebraic("a9") shouldBe None
      Pos.fromAlgebraic("i1") shouldBe None
      Pos.fromAlgebraic("aa") shouldBe None
      Pos.fromAlgebraic("a1b") shouldBe None
    }
  }