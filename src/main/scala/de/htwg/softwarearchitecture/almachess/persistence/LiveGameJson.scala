package de.htwg.softwarearchitecture.almachess.persistence

import spray.json.*
import spray.json.DefaultJsonProtocol.*

object LiveGameJson extends DefaultJsonProtocol:
  given RootJsonFormat[GameSaveDto] = jsonFormat6(GameSaveDto.apply)

  def encode(dto: GameSaveDto): String = dto.toJson.compactPrint
  def decode(json: String): GameSaveDto = json.parseJson.convertTo[GameSaveDto]
