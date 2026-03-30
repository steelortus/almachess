package de.htwg.softwarearchitecture.almachess.model

import de.htwg.softwarearchitecture.almachess.parser.FenParser

object Fen:
  def parse(fen: String): Either[String, GameState] = FenParser.parse(fen)
  def render(state: GameState): String = state.toFen
