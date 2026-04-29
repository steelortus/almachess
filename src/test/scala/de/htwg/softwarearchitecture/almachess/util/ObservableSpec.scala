package de.htwg.softwarearchitecture.almachess.util

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ListBuffer

class ObservableSpec extends AnyWordSpec with Matchers:

  class TestObservable extends Observable

  class TestObserver extends Observer:
    val events = ListBuffer[GameEvent]()
    def update(e: GameEvent): Unit = events += e

  "An Observable" should {
    "allow adding observers" in {
      val observable = TestObservable()
      val observer = TestObserver()
      observable.add(observer)
      // Can't directly test internal state, but notify should work
      observable.notifyObservers(GameEvent.Status("test"))
      observer.events should contain(GameEvent.Status("test"))
    }

    "allow removing observers" in {
      val observable = TestObservable()
      val observer = TestObserver()
      observable.add(observer)
      observable.remove(observer)
      observable.notifyObservers(GameEvent.Status("test"))
      observer.events shouldBe empty
    }

    "notify all observers" in {
      val observable = TestObservable()
      val observer1 = TestObserver()
      val observer2 = TestObserver()
      observable.add(observer1)
      observable.add(observer2)
      observable.notifyObservers(GameEvent.Status("test"))
      observer1.events should contain(GameEvent.Status("test"))
      observer2.events should contain(GameEvent.Status("test"))
    }
  }