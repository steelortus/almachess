package de.htwg.softwarearchitecture.almachess.util

import de.htwg.softwarearchitecture.almachess.model.GameState

enum GameEvent:
  case BoardChanged(state: GameState)
  case Status(message: String)
  case IllegalMove(message: String)
  case GameOver(message: String)
