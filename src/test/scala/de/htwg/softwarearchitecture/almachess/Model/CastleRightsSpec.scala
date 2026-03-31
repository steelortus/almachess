package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class CastleRightsSpec extends AnyWordSpec with Matchers:

  "CastleRights" should {
    "initialize with all rights" in {
      val rights = CastleRights()
      rights.whiteKingSide shouldBe true
      rights.whiteQueenSide shouldBe true
      rights.blackKingSide shouldBe true
      rights.blackQueenSide shouldBe true
    }

    "initialize with no rights" in {
      val rights = CastleRights(false, false, false, false)
      rights.whiteKingSide shouldBe false
      rights.whiteQueenSide shouldBe false
      rights.blackKingSide shouldBe false
      rights.blackQueenSide shouldBe false
    }

    "produce correct FEN string" in {
      CastleRights().fen shouldBe "KQkq"
      CastleRights(false, false, false, false).fen shouldBe "-"
      CastleRights(whiteKingSide = false).fen shouldBe "Qkq"
      CastleRights(whiteQueenSide = false).fen shouldBe "Kkq"
      CastleRights(blackKingSide = false).fen shouldBe "KQq"
      CastleRights(blackQueenSide = false).fen shouldBe "KQk"
    }
  }