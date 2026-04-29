import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class MySuite extends AnyWordSpec with Matchers:
  "A simple test" should {
    "succeed" in {
      val obtained = 42
      val expected = 42
      obtained shouldBe expected
    }
  }
