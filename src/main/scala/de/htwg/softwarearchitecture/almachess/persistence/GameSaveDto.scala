package de.htwg.softwarearchitecture.almachess.persistence

case class GameSaveDto(
    gameId: String,
    currentFen: String,
    pgn: String,
    moves: List[String],
    status: String,
    savedAt: Long = 0L,
    initialFen: String = ""
)

case class GameListEntry(gameId: String, savedAt: Long)
