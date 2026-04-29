package de.htwg.softwarearchitecture.almachess.model

object San:

  // Converts an internal Move to SAN notation, given the GameState BEFORE the move.
  def toSan(move: Move, state: GameState): String =
    state.board.pieceAt(move.from) match
      case None        => "?"
      case Some(piece) => toSanPiece(piece, move, state)

  private def toSanPiece(piece: Piece, move: Move, state: GameState): String =
    val isEnPassant =
      piece.tpe == PieceType.Pawn &&
        state.enPassantTarget.contains(move.to) &&
        move.from.file != move.to.file

    val isCapture =
      state.board.pieceAt(move.to).nonEmpty || isEnPassant

    val nextStateOpt = state.applyMove(move).toOption
    val suffix = nextStateOpt match
      case Some(ns) if ns.status.startsWith("checkmate") => "#"
      case Some(ns) if ns.status.contains("check")       => "+"
      case _                                              => ""

    // Castling
    if piece.tpe == PieceType.King && math.abs(move.to.file - move.from.file) == 2 then
      if move.to.file == 6 then s"O-O$suffix" else s"O-O-O$suffix"
    else
      val dest       = move.to.toAlgebraic
      val captureStr = if isCapture then "x" else ""
      val promoStr = move.promotion.map {
        case PieceType.Queen  => "=Q"
        case PieceType.Rook   => "=R"
        case PieceType.Bishop => "=B"
        case PieceType.Knight => "=N"
        case _                => ""
      }.getOrElse("")

      if piece.tpe == PieceType.Pawn then
        val fromFile = if isCapture then ('a' + move.from.file).toChar.toString else ""
        s"$fromFile$captureStr$dest$promoStr$suffix"
      else
        val pieceLetter = piece.tpe match
          case PieceType.Knight => "N"
          case PieceType.Bishop => "B"
          case PieceType.Rook   => "R"
          case PieceType.Queen  => "Q"
          case PieceType.King   => "K"
          case _                => ""
        val ambig = disambiguate(piece, move, state)
        s"$pieceLetter$ambig$captureStr$dest$promoStr$suffix"

  private def disambiguate(piece: Piece, move: Move, state: GameState): String =
    val others = state.board.positions.filter { p =>
      p != move.from &&
        state.board.pieceAt(p).contains(piece) &&
        state.legalMovesFrom(p).exists(_.to == move.to)
    }
    if others.isEmpty then ""
    else if others.forall(_.file != move.from.file) then ('a' + move.from.file).toChar.toString
    else if others.forall(_.rank != move.from.rank) then (move.from.rank + 1).toString
    else move.from.toAlgebraic

  // Converts a SAN string to an internal Move, given the GameState BEFORE the move.
  def fromSan(san: String, state: GameState): Either[String, Move] =
    val s = san.stripSuffix("+").stripSuffix("#").trim

    // Castling
    if s == "O-O-O" || s == "0-0-0" then
      val from = if state.turn == Color.White then Pos(0, 4) else Pos(7, 4)
      val to   = if state.turn == Color.White then Pos(0, 2) else Pos(7, 2)
      Right(Move(from, to))
    else if s == "O-O" || s == "0-0" then
      val from = if state.turn == Color.White then Pos(0, 4) else Pos(7, 4)
      val to   = if state.turn == Color.White then Pos(0, 6) else Pos(7, 6)
      Right(Move(from, to))
    else
      parseSanMove(s, state)

  private def parseSanMove(san: String, state: GameState): Either[String, Move] =
    var s = san

    // Parse promotion (e.g. "=Q")
    val (promotion, sNoPromo) =
      val eqIdx = s.indexOf('=')
      if eqIdx >= 0 && eqIdx + 1 < s.length then
        val promo = s(eqIdx + 1) match
          case 'Q' | 'q' => Some(PieceType.Queen)
          case 'R' | 'r' => Some(PieceType.Rook)
          case 'B' | 'b' => Some(PieceType.Bishop)
          case 'N' | 'n' => Some(PieceType.Knight)
          case _         => None
        (promo, s.take(eqIdx))
      else (None, s)
    s = sNoPromo

    // Parse leading piece letter (uppercase = non-pawn piece)
    val (pieceType, sNoPiece) =
      if s.nonEmpty && s.head.isUpper then
        val pt = s.head match
          case 'N' => PieceType.Knight
          case 'B' => PieceType.Bishop
          case 'R' => PieceType.Rook
          case 'Q' => PieceType.Queen
          case 'K' => PieceType.King
          case _   => PieceType.Pawn
        (pt, s.tail)
      else (PieceType.Pawn, s)
    s = sNoPiece

    // Remove capture marker
    s = s.replace("x", "")

    // Remaining: optional disambiguation + 2-char destination
    if s.length < 2 then return Left(s"invalid SAN: $san")

    val destStr  = s.takeRight(2)
    val disambig = s.dropRight(2)

    Pos.fromAlgebraic(destStr).toRight(s"invalid destination in SAN: $san").flatMap { to =>
      val fromFile: Option[Int] = disambig.find(_.isLower).map(_ - 'a')
      val fromRank: Option[Int] = disambig.find(_.isDigit).map(_ - '1')

      // Default promotion to Queen when pawn reaches last rank without explicit =
      val promoToMatch =
        if pieceType == PieceType.Pawn && (to.rank == 0 || to.rank == 7) then
          promotion.orElse(Some(PieceType.Queen))
        else promotion

      val candidates = state.allLegalMoves().filter { m =>
        m.to == to &&
          state.board.pieceAt(m.from).exists(_.tpe == pieceType) &&
          fromFile.forall(_ == m.from.file) &&
          fromRank.forall(_ == m.from.rank) &&
          m.promotion == promoToMatch
      }

      candidates match
        case Vector(m) => Right(m)
        case Vector()  => Left(s"no legal move matches SAN: $san")
        case _         => Left(s"ambiguous SAN: $san")
    }
