package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class MoveSpec extends AnyWordSpec with Matchers:

  "A Move" should {
    "store from and to positions" in {
      val move = Move(Pos(1, 4), Pos(3, 4))
      move.from shouldBe Pos(1, 4)
      move.to shouldBe Pos(3, 4)
      move.promotion shouldBe None
    }

    "store promotion when provided" in {
      val move = Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen))
      move.promotion shouldBe Some(PieceType.Queen)
    }
  }

  "Move.parse" should {
    "parse simple moves" in {
      Move.parse("e2e4") shouldBe Right(Move(Pos(1, 4), Pos(3, 4)))
      Move.parse("a1h8") shouldBe Right(Move(Pos(0, 0), Pos(7, 7)))
    }

    "parse moves with promotion" in {
      Move.parse("e7e8q") shouldBe Right(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen)))
      Move.parse("a7a8r") shouldBe Right(Move(Pos(6, 0), Pos(7, 0), Some(PieceType.Rook)))
      Move.parse("b7b8b") shouldBe Right(Move(Pos(6, 1), Pos(7, 1), Some(PieceType.Bishop)))
      Move.parse("c7c8n") shouldBe Right(Move(Pos(6, 2), Pos(7, 2), Some(PieceType.Knight)))
    }

    "reject invalid moves" in {
      Move.parse("") shouldBe a[Left[_, _]]
      Move.parse("e2") shouldBe a[Left[_, _]]
      Move.parse("e2e") shouldBe a[Left[_, _]]
      Move.parse("i2e4") shouldBe a[Left[_, _]]
      Move.parse("e2i4") shouldBe a[Left[_, _]]
      Move.parse("e2e4p") shouldBe a[Left[_, _]] // invalid promotion
    }
  }