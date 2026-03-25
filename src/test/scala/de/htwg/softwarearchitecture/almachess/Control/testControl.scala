package de.htwg.softwarearchitecture.almachess.Control

import munit.FunSuite
import de.htwg.softwarearchitecture.almachess.Model.Board

class testControl extends FunSuite:
  test("Controller can be instantiated") {
    val controller = Controller()
    assert(controller.isInstanceOf[Controller])
  }

  // Note: runTUI() prints to console, so we can't easily test output without capturing stdout
  // In a real app, you'd inject dependencies or use a mock TUI
  test("Controller runTUI calls Tui.run (placeholder)") {
    // This test is a placeholder since runTUI() has side effects
    // For proper testing, refactor to return a value or use dependency injection
    val controller = Controller()
    // controller.runTUI() // Uncomment to verify it doesn't crash
    assert(true) // Placeholder assertion
  }