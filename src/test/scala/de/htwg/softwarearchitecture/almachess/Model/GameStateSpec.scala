package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.parser.FenParser

class GameStateSpec extends AnyWordSpec with Matchers:

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).toOption.get

  "A GameState" should {
    "initialize with initial position" in {
      val state = GameState.initial
      state.board shouldBe Board.initial
      state.turn shouldBe Color.White
      state.castleRights shouldBe CastleRights()
      state.enPassantTarget shouldBe None
      state.halfMoveClock shouldBe 0
      state.fullMoveNumber shouldBe 1
      state.status shouldBe "White to move"
    }

    "produce correct FEN" in {
      val fen = GameState.initial.toFen
      fen shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    }

    "find legal moves from position" in {
      val state = GameState.initial
      val moves = state.legalMovesFrom(Pos(1, 4)) // e2 pawn
      moves should not be empty
      moves should contain(Move(Pos(1, 4), Pos(3, 4))) // e2-e4
      moves should contain(Move(Pos(1, 4), Pos(2, 4))) // e2-e3
    }

    "find all legal moves" in {
      val state = GameState.initial
      val moves = state.allLegalMoves()
      moves.length shouldBe 20 // 16 pawns (8 can move 1 or 2), 4 knights
    }

    "detect check" in {
      // Set up a position where black king is in check
      val board = Board.initial.updated(Pos(7, 4), None) // remove black king
        .updated(Pos(3, 4), Some(Piece(Color.Black, PieceType.King))) // place king on e4
        .updated(Pos(3, 3), Some(Piece(Color.White, PieceType.Queen))) // place white queen on d4 (same rank)
      val state = GameState(board, Color.Black)
      state.isCheck(Color.Black) shouldBe true
    }

    "detect checkmate" in {
      // Checkmate: king on h8, queen on g7, king on g8 blocking escape
      val board = Board.empty
        .updated(Pos(7, 7), Some(Piece(Color.Black, PieceType.King))) // h8
        .updated(Pos(6, 6), Some(Piece(Color.White, PieceType.Queen))) // g7
        .updated(Pos(7, 6), Some(Piece(Color.White, PieceType.King))) // g8
      val state = GameState(board, Color.Black)
      state.isCheckmate(Color.Black) shouldBe true
    }

    "apply valid moves" in {
      val state = GameState.initial
      val move = Move(Pos(1, 4), Pos(3, 4)) // e2-e4
      val result = state.applyMove(move)
      result shouldBe a[Right[_, _]]
      val newState = result.right.get
      newState.board.pieceAt(Pos(3, 4)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      newState.board.pieceAt(Pos(1, 4)) shouldBe None
      newState.turn shouldBe Color.Black
    }

    "reject invalid moves" in {
      val state = GameState.initial
      val move = Move(Pos(1, 4), Pos(4, 4)) // e2-e5 (illegal pawn move)
      val result = state.applyMove(move)
      result shouldBe a[Left[_, _]]
    }
  }

  "GameState en passant" should {
    "set the en passant target after a two-square pawn push" in {
      val after = GameState.initial.applyMove(Move(Pos(1, 4), Pos(3, 4))).toOption.get
      after.enPassantTarget shouldBe Some(Pos(2, 4)) // e3
    }

    "clear the en passant target after any other move" in {
      val s1 = GameState.initial.applyMove(Move(Pos(1, 4), Pos(3, 4))).toOption.get // e4
      val s2 = s1.applyMove(Move(Pos(6, 3), Pos(4, 3))).toOption.get                 // d5
      val s3 = s2.applyMove(Move(Pos(0, 6), Pos(2, 5))).toOption.get                 // Nf3
      s3.enPassantTarget shouldBe None
    }

    "allow capturing a pawn en passant and remove the captured pawn" in {
      val state = parseFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
      val result = state.applyMove(Move(Pos(4, 4), Pos(5, 3))) // exd6 e.p.
      result shouldBe a[Right[_, _]]
      val ns = result.toOption.get
      ns.board.pieceAt(Pos(5, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
      ns.board.pieceAt(Pos(4, 3)) shouldBe None // black pawn captured from d5
      ns.board.pieceAt(Pos(4, 4)) shouldBe None
    }

    "reject diagonal pawn capture when en passant target is missing" in {
      val state = parseFen("4k3/8/8/3pP3/8/8/8/4K3 w - - 0 1")
      state.applyMove(Move(Pos(4, 4), Pos(5, 3))) shouldBe a[Left[_, _]]
    }
  }

  "GameState castling rights" should {
    "lose both white castling rights when the white king moves" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
      val ns = state.applyMove(Move(Pos(0, 4), Pos(1, 4))).toOption.get // Ke1-e2
      ns.castleRights.whiteKingSide shouldBe false
      ns.castleRights.whiteQueenSide shouldBe false
    }

    "lose only kingside rights when the h1 rook moves" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
      val ns = state.applyMove(Move(Pos(0, 7), Pos(1, 7))).toOption.get // Rh1-h2
      ns.castleRights.whiteKingSide shouldBe false
      ns.castleRights.whiteQueenSide shouldBe true
    }

    "lose only queenside rights when the a1 rook moves" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K2R w KQ - 0 1")
      val ns = state.applyMove(Move(Pos(0, 0), Pos(1, 0))).toOption.get // Ra1-a2
      ns.castleRights.whiteKingSide shouldBe true
      ns.castleRights.whiteQueenSide shouldBe false
    }

    "lose castle rights when an opponent rook captures the white rook on h1" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K2r b KQ - 0 1")
      val ns = state.applyMove(Move(Pos(0, 7), Pos(0, 4))) // not legal: blocked by king
      // instead capture from h2 -> set up
      val s2 = parseFen("4k3/8/8/8/8/8/7r/R3K2R b KQ - 0 1")
      val ns2 = s2.applyMove(Move(Pos(1, 7), Pos(0, 7))).toOption.get
      ns2.castleRights.whiteKingSide shouldBe false
      ns2.castleRights.whiteQueenSide shouldBe true
    }

    "perform kingside castling and reposition the rook" in {
      val state = parseFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
      val ns = state.applyMove(Move(Pos(0, 4), Pos(0, 6))).toOption.get
      ns.board.pieceAt(Pos(0, 6)) shouldBe Some(Piece(Color.White, PieceType.King))
      ns.board.pieceAt(Pos(0, 5)) shouldBe Some(Piece(Color.White, PieceType.Rook))
      ns.board.pieceAt(Pos(0, 7)) shouldBe None
    }

    "perform queenside castling and reposition the rook" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1")
      val ns = state.applyMove(Move(Pos(0, 4), Pos(0, 2))).toOption.get
      ns.board.pieceAt(Pos(0, 2)) shouldBe Some(Piece(Color.White, PieceType.King))
      ns.board.pieceAt(Pos(0, 3)) shouldBe Some(Piece(Color.White, PieceType.Rook))
      ns.board.pieceAt(Pos(0, 0)) shouldBe None
    }

    "reject castling when the king is currently in check" in {
      val state = parseFen("4k3/8/8/8/4r3/8/8/4K2R w K - 0 1")
      state.applyMove(Move(Pos(0, 4), Pos(0, 6))) shouldBe a[Left[_, _]]
    }

    "reject castling when a square the king crosses is attacked" in {
      val state = parseFen("4k3/8/8/8/8/5r2/8/4K2R w K - 0 1")
      state.applyMove(Move(Pos(0, 4), Pos(0, 6))) shouldBe a[Left[_, _]]
    }

    "reject castling when castling rights are missing" in {
      val state = parseFen("4k3/8/8/8/8/8/8/4K2R w - - 0 1")
      state.applyMove(Move(Pos(0, 4), Pos(0, 6))) shouldBe a[Left[_, _]]
    }

    "reject castling through an occupied square" in {
      val state = parseFen("4k3/8/8/8/8/8/8/4KB1R w K - 0 1")
      state.applyMove(Move(Pos(0, 4), Pos(0, 6))) shouldBe a[Left[_, _]]
    }
  }

  "GameState promotion" should {
    "promote a pawn reaching the last rank to the requested piece" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      val ns = state.applyMove(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Knight))).toOption.get
      ns.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, PieceType.Knight))
    }

    "default to queen when no promotion piece is supplied" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      val ns = state.applyMove(Move(Pos(6, 4), Pos(7, 4))).toOption.get
      ns.board.pieceAt(Pos(7, 4)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    }

    "reject promotion to king or pawn" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      state.applyMove(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.King))) shouldBe a[Left[_, _]]
      state.applyMove(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Pawn))) shouldBe a[Left[_, _]]
    }

    "reject a promotion piece set on a non-promoting move" in {
      val state = GameState.initial
      state.applyMove(Move(Pos(1, 4), Pos(3, 4), Some(PieceType.Queen))) shouldBe a[Left[_, _]]
    }
  }

  "GameState terminal states" should {
    "detect stalemate (no legal moves, not in check)" in {
      // Classic stalemate: black king a8, white king c7, white queen c8 — but Qc8 would be check.
      // Use: black king h8, white king f7, white queen g6 — stalemate for black.
      val state = parseFen("7k/8/5KQ1/8/8/8/8/8 b - - 0 1")
      state.isStalemate(Color.Black) shouldBe true
      state.isCheckmate(Color.Black) shouldBe false
      state.allLegalMoves(Color.Black) shouldBe empty
    }

    "detect checkmate when defender has no legal moves and is in check" in {
      // Back-rank mate: Kg8, pawns f7,g7,h7, white queen on e8.
      val state = parseFen("4Q1k1/5ppp/8/8/8/8/8/4K3 b - - 0 1")
      state.isCheck(Color.Black) shouldBe true
      state.isCheckmate(Color.Black) shouldBe true
      state.allLegalMoves(Color.Black) shouldBe empty
    }

    "set status to checkmate after delivering mate" in {
      val state = parseFen("6k1/5ppp/8/8/8/8/8/4Q1K1 w - - 0 1")
      val ns = state.applyMove(Move(Pos(0, 4), Pos(7, 4))).toOption.get // Qe8#
      ns.status should startWith("checkmate")
    }

    "set status to stalemate when the side to move has no moves" in {
      // White Kf7, Qb6, Black Kh8 — Qb6-g6 produces stalemate for Black.
      val before = parseFen("7k/5K2/1Q6/8/8/8/8/8 w - - 0 1")
      val ns = before.applyMove(Move(Pos(5, 1), Pos(5, 6))).toOption.get
      ns.status shouldBe "stalemate"
    }

    "set status to 'in check' when the move gives check without mating" in {
      val state = parseFen("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1")
      val ns = state.applyMove(Move(Pos(1, 4), Pos(6, 4))).toOption.get // Re7+
      ns.status should include("check")
      ns.status should not startWith "checkmate"
    }
  }

  "GameState illegal-move rejection" should {
    "reject a move that leaves the own king in check (pinned piece)" in {
      // White king e1, white bishop e2 pinned by black rook e8. Bishop cannot move off the e-file.
      val state = parseFen("4r3/8/8/8/8/8/4B3/4K3 w - - 0 1")
      state.applyMove(Move(Pos(1, 4), Pos(2, 5))) shouldBe a[Left[_, _]] // Bf3 exposes king
    }

    "reject moving when no piece is on the source square" in {
      val state = GameState.initial
      state.applyMove(Move(Pos(3, 3), Pos(4, 4))) shouldBe a[Left[_, _]]
    }

    "reject moving the opponent's piece" in {
      val state = GameState.initial // white to move
      state.applyMove(Move(Pos(6, 4), Pos(4, 4))) shouldBe a[Left[_, _]] // black e7-e5
    }

    "reject a move whose source equals its destination" in {
      val state = GameState.initial
      state.applyMove(Move(Pos(1, 4), Pos(1, 4))) shouldBe a[Left[_, _]]
    }

    "reject a king move into an attacked square" in {
      // Black rook on d8 controls the d-file; Ke1-d2 would walk into check.
      val state = parseFen("3rk3/8/8/8/8/8/8/4K3 w - - 0 1")
      state.applyMove(Move(Pos(0, 4), Pos(1, 3))) shouldBe a[Left[_, _]]
    }

    "filter out moves leaving own king in check from legalMovesFrom" in {
      val state = parseFen("4r3/8/8/8/8/8/4B3/4K3 w - - 0 1")
      val moves = state.legalMovesFrom(Pos(1, 4)) // bishop e2
      moves shouldBe empty
    }
  }