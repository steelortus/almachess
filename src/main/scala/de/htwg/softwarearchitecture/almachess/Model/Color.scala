package de.htwg.softwarearchitecture.almachess.model

enum Color:
  case White, Black

  def opposite: Color = this match
    case White => Black
    case Black => White
