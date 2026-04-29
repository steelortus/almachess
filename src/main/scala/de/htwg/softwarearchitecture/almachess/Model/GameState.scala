package de.htwg.softwarearchitecture.almachess.model

case class GameState(
    board: Board = Board.initial,
    turn: Color = Color.White,
    castleRights: CastleRights = CastleRights(),
    enPassantTarget: Option[Pos] = None,
    halfMoveClock: Int = 0,
    fullMoveNumber: Int = 1,
    status: String = "White to move"
):

  def toFen: String =
    s"${board.toFenPlacement} ${if turn == Color.White then "w" else "b"} ${castleRights.fen} ${enPassantTarget.map(_.toAlgebraic).getOrElse("-")} $halfMoveClock $fullMoveNumber"

  def legalMovesFrom(from: Pos): Vector[Move] =
    board.pieceAt(from).filter(_.color == turn).toVector.flatMap { piece =>
      board.positions.flatMap { to =>
        val base = Move(from, to, None)

        val candidateMoves =
          piece match
            case Piece(_, PieceType.Pawn) if to.rank == 0 || to.rank == 7 =>
              Vector(
                base.copy(promotion = Some(PieceType.Queen)),
                base.copy(promotion = Some(PieceType.Rook)),
                base.copy(promotion = Some(PieceType.Bishop)),
                base.copy(promotion = Some(PieceType.Knight))
              )
            case _ =>
              Vector(base)

        candidateMoves.filter { move =>
          if from == to then false
          else if !pseudoLegal(piece, from, to, move.promotion) then false
          else
            val next = executeUnchecked(piece, move)
            next.board.findKing(turn).nonEmpty && !next.isCheck(turn)
        }
      }
    }

  def allLegalMoves(color: Color = turn): Vector[Move] =
    board.positions.flatMap { p =>
      board.pieceAt(p) match
        case Some(piece) if piece.color == color =>
          if color == turn then legalMovesFrom(p)
          else copy(turn = color).legalMovesFrom(p)
        case _ => Vector.empty
    }

  def isCheck(color: Color): Boolean =
    board.findKing(color).exists { kingPos =>
      board.positions.exists { pos =>
        board.pieceAt(pos).exists(p => p.color != color && attacksSquare(pos, kingPos, p))
      }
    }

  def isCheckmate(color: Color): Boolean =
    copy(turn = color).isCheck(color) && copy(turn = color).allLegalMoves(color).isEmpty

  def isStalemate(color: Color): Boolean =
    !copy(turn = color).isCheck(color) && copy(turn = color).allLegalMoves(color).isEmpty

  def applyMove(move: Move): Either[String, GameState] =
    val from = move.from
    val to = move.to

    board.pieceAt(from) match
      case None =>
        Left(s"no piece at ${from.toAlgebraic}")

      case Some(piece) if piece.color != turn =>
        Left(s"it is ${turn.toString.toLowerCase}'s turn")

      case Some(piece) =>
        if from == to then Left("source and target are identical")
        else if !pseudoLegal(piece, from, to, move.promotion) then Left("illegal move for piece")
        else
          val next = executeUnchecked(piece, move)
          if next.board.findKing(turn).isEmpty then Left("own king must remain on board")
          else if next.isCheck(turn) then Left("move leaves king in check")
          else Right(next.finishMove())

  private def finishMove(): GameState =
    val defender = turn
    val attacker = turn.opposite

    val baseStatus =
      if isCheckmate(defender) then s"checkmate - ${attacker} wins"
      else if isStalemate(defender) then "stalemate"
      else if isCheck(defender) then s"${defender} is in check"
      else s"${defender} to move"

    copy(status = baseStatus)

  private def executeUnchecked(piece: Piece, move: Move): GameState =
    val from = move.from
    val to = move.to
    val targetPiece = board.pieceAt(to)

    val isEnPassantCapture =
      piece.tpe == PieceType.Pawn &&
        enPassantTarget.contains(to) &&
        targetPiece.isEmpty &&
        from.file != to.file

    val capturedPawnPos = Pos(from.rank, to.file)

    val clearedBoard =
      val removedSource = board.updated(from, None)
      if isEnPassantCapture then removedSource.updated(capturedPawnPos, None)
      else removedSource

    val promotedPiece =
      if piece.tpe == PieceType.Pawn && (to.rank == 0 || to.rank == 7) then
        Piece(piece.color, move.promotion.getOrElse(PieceType.Queen))
      else piece

    val movedBoard = clearedBoard.updated(to, Some(promotedPiece))

    val castledBoard =
      if piece.tpe == PieceType.King && math.abs(to.file - from.file) == 2 then
        if to.file == 6 then
          val rookFrom = Pos(from.rank, 7)
          val rookTo = Pos(from.rank, 5)
          movedBoard.updated(rookFrom, None).updated(rookTo, movedBoard.pieceAt(rookFrom))
        else
          val rookFrom = Pos(from.rank, 0)
          val rookTo = Pos(from.rank, 3)
          movedBoard.updated(rookFrom, None).updated(rookTo, movedBoard.pieceAt(rookFrom))
      else movedBoard

    val nextCastleRights = updateCastleRights(piece, from, to, targetPiece)

    val nextEnPassant =
      if piece.tpe == PieceType.Pawn && math.abs(to.rank - from.rank) == 2 then
        Some(Pos((from.rank + to.rank) / 2, from.file))
      else None

    val resetHalfmove =
      piece.tpe == PieceType.Pawn || targetPiece.nonEmpty || isEnPassantCapture

    GameState(
      board = castledBoard,
      turn = turn.opposite,
      castleRights = nextCastleRights,
      enPassantTarget = nextEnPassant,
      halfMoveClock = if resetHalfmove then 0 else halfMoveClock + 1,
      fullMoveNumber = if turn == Color.Black then fullMoveNumber + 1 else fullMoveNumber,
      status = status
    )

  private def updateCastleRights(piece: Piece, from: Pos, to: Pos, captured: Option[Piece]): CastleRights =
    val removedByMove = piece match
      case Piece(Color.White, PieceType.King) => castleRights.copy(whiteKingSide = false, whiteQueenSide = false)
      case Piece(Color.Black, PieceType.King) => castleRights.copy(blackKingSide = false, blackQueenSide = false)
      case Piece(Color.White, PieceType.Rook) if from == Pos(0, 0) => castleRights.copy(whiteQueenSide = false)
      case Piece(Color.White, PieceType.Rook) if from == Pos(0, 7) => castleRights.copy(whiteKingSide = false)
      case Piece(Color.Black, PieceType.Rook) if from == Pos(7, 0) => castleRights.copy(blackQueenSide = false)
      case Piece(Color.Black, PieceType.Rook) if from == Pos(7, 7) => castleRights.copy(blackKingSide = false)
      case _ => castleRights

    captured match
      case Some(Piece(Color.White, PieceType.Rook)) if to == Pos(0, 0) => removedByMove.copy(whiteQueenSide = false)
      case Some(Piece(Color.White, PieceType.Rook)) if to == Pos(0, 7) => removedByMove.copy(whiteKingSide = false)
      case Some(Piece(Color.Black, PieceType.Rook)) if to == Pos(7, 0) => removedByMove.copy(blackQueenSide = false)
      case Some(Piece(Color.Black, PieceType.Rook)) if to == Pos(7, 7) => removedByMove.copy(blackKingSide = false)
      case _ => removedByMove

  private def pseudoLegal(piece: Piece, from: Pos, to: Pos, promotion: Option[PieceType]): Boolean =
    if !to.isInside then false
    else if board.pieceAt(to).exists(_.color == piece.color) then false
    else
      piece.tpe match
        case PieceType.Pawn =>
          legalPawnMove(piece.color, from, to, promotion)

        case PieceType.Knight =>
          val dr = math.abs(to.rank - from.rank)
          val df = math.abs(to.file - from.file)
          (dr == 2 && df == 1) || (dr == 1 && df == 2)

        case PieceType.Bishop =>
          math.abs(to.rank - from.rank) == math.abs(to.file - from.file) && board.clearPath(from, to)

        case PieceType.Rook =>
          (from.rank == to.rank || from.file == to.file) && board.clearPath(from, to)

        case PieceType.Queen =>
          ((from.rank == to.rank || from.file == to.file) ||
            math.abs(to.rank - from.rank) == math.abs(to.file - from.file)) &&
            board.clearPath(from, to)

        case PieceType.King =>
          val dr = math.abs(to.rank - from.rank)
          val df = math.abs(to.file - from.file)
          (math.max(dr, df) == 1) || legalCastle(piece.color, from, to)

  private def legalPawnMove(color: Color, from: Pos, to: Pos, promotion: Option[PieceType]): Boolean =
    val dir = if color == Color.White then 1 else -1
    val startRank = if color == Color.White then 1 else 6

    val oneStep =
      to.file == from.file &&
        to.rank == from.rank + dir &&
        board.isEmpty(to)

    val twoStep =
      to.file == from.file &&
        from.rank == startRank &&
        to.rank == from.rank + 2 * dir &&
        board.isEmpty(Pos(from.rank + dir, from.file)) &&
        board.isEmpty(to)

    val capture =
      math.abs(to.file - from.file) == 1 &&
        to.rank == from.rank + dir && (
          board.isEnemy(to, color) || enPassantTarget.contains(to)
        )

    val promotionOk =
      if to.rank == 0 || to.rank == 7 then
        promotion.forall(p => p != PieceType.King && p != PieceType.Pawn)
      else promotion.isEmpty

    promotionOk && (oneStep || twoStep || capture)

  private def legalCastle(color: Color, from: Pos, to: Pos): Boolean =
    val kingHome = if color == Color.White then Pos(0, 4) else Pos(7, 4)

    if from != kingHome || isCheck(color) || to.rank != from.rank then false
    else
      val rightsOk = (color, to.file) match
        case (Color.White, 6) => castleRights.whiteKingSide
        case (Color.White, 2) => castleRights.whiteQueenSide
        case (Color.Black, 6) => castleRights.blackKingSide
        case (Color.Black, 2) => castleRights.blackQueenSide
        case _                => false

      val rookFrom =
        if to.file == 6 then Pos(from.rank, 7) else Pos(from.rank, 0)

      val between =
        if to.file == 6 then Vector(Pos(from.rank, 5), Pos(from.rank, 6))
        else Vector(Pos(from.rank, 1), Pos(from.rank, 2), Pos(from.rank, 3))

      val kingPath =
        if to.file == 6 then Vector(Pos(from.rank, 5), Pos(from.rank, 6))
        else Vector(Pos(from.rank, 3), Pos(from.rank, 2))

      rightsOk &&
      board.pieceAt(rookFrom).contains(Piece(color, PieceType.Rook)) &&
      between.forall(board.isEmpty) &&
      kingPath.forall(p => !squareAttacked(p, color.opposite))

  private def attacksSquare(from: Pos, target: Pos, piece: Piece): Boolean =
    piece.tpe match
      case PieceType.Pawn =>
        val dir = if piece.color == Color.White then 1 else -1
        target.rank == from.rank + dir && math.abs(target.file - from.file) == 1

      case PieceType.Knight =>
        val dr = math.abs(target.rank - from.rank)
        val df = math.abs(target.file - from.file)
        (dr == 2 && df == 1) || (dr == 1 && df == 2)

      case PieceType.Bishop =>
        math.abs(target.rank - from.rank) == math.abs(target.file - from.file) &&
          board.clearPath(from, target)

      case PieceType.Rook =>
        (from.rank == target.rank || from.file == target.file) &&
          board.clearPath(from, target)

      case PieceType.Queen =>
        ((from.rank == target.rank || from.file == target.file) ||
          math.abs(target.rank - from.rank) == math.abs(target.file - from.file)) &&
          board.clearPath(from, target)

      case PieceType.King =>
        math.max(math.abs(target.rank - from.rank), math.abs(target.file - from.file)) == 1

  private def squareAttacked(square: Pos, byColor: Color): Boolean =
    board.positions.exists { from =>
      board.pieceAt(from).exists(piece => piece.color == byColor && attacksSquare(from, square, piece))
    }

object GameState:
  val initial: GameState = GameState()