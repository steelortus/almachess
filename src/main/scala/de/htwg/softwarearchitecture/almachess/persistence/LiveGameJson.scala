package de.htwg.softwarearchitecture.almachess.persistence

import spray.json.*

object LiveGameJson:

  private given RootJsonFormat[GameSaveDto] = new RootJsonFormat[GameSaveDto]:
    def write(dto: GameSaveDto): JsValue = JsObject(
      "gameId"     -> JsString(dto.gameId),
      "currentFen" -> JsString(dto.currentFen),
      "initialFen" -> JsString(dto.initialFen),
      "pgn"        -> JsString(dto.pgn),
      "moves"      -> JsArray(dto.moves.map(JsString.apply).toVector),
      "status"     -> JsString(dto.status),
      "savedAt"    -> JsNumber(dto.savedAt)
    )
    def read(json: JsValue): GameSaveDto =
      val fields = json.asJsObject.fields
      def str(k: String, default: String = ""): String = fields.get(k) match
        case Some(JsString(s)) => s
        case _                 => default
      def long(k: String): Long = fields.get(k) match
        case Some(JsNumber(n)) => n.toLong
        case _                 => 0L
      val moves = fields.get("moves") match
        case Some(JsArray(elems)) => elems.collect { case JsString(s) => s }.toList
        case _                    => Nil
      GameSaveDto(
        gameId     = str("gameId"),
        currentFen = str("currentFen"),
        pgn        = str("pgn"),
        moves      = moves,
        status     = str("status"),
        savedAt    = long("savedAt"),
        initialFen = str("initialFen")
      )

  def encode(dto: GameSaveDto): String  = dto.toJson.compactPrint
  def decode(json: String): GameSaveDto = json.parseJson.convertTo[GameSaveDto]
