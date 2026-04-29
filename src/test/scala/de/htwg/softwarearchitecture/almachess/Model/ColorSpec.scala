package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class ColorSpec extends AnyWordSpec with Matchers:

  "Color.White" should {
    "have opposite Color.Black" in {
      Color.White.opposite shouldBe Color.Black
    }
  }

  "Color.Black" should {
    "have opposite Color.White" in {
      Color.Black.opposite shouldBe Color.White
    }
  }