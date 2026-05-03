package de.htwg.softwarearchitecture.almachess.model

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import de.htwg.softwarearchitecture.almachess.parser.FenParser

class SanSpec extends AnyWordSpec with Matchers:

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).toOption.get

  "San.toSan" should {
    "render a simple pawn push" in {
      San.toSan(Move(Pos(1, 4), Pos(3, 4)), GameState.initial) shouldBe "e4"
    }

    "render a knight development move" in {
      San.toSan(Move(Pos(0, 6), Pos(2, 5)), GameState.initial) shouldBe "Nf3"
    }

    "render kingside castling" in {
      val state = parseFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
      San.toSan(Move(Pos(0, 4), Pos(0, 6)), state) shouldBe "O-O"
    }

    "render queenside castling" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1")
      San.toSan(Move(Pos(0, 4), Pos(0, 2)), state) shouldBe "O-O-O"
    }

    "render a piece capture with x" in {
      val state = parseFen("4k3/8/8/3n4/8/2N5/8/4K3 w - - 0 1")
      San.toSan(Move(Pos(2, 2), Pos(4, 3)), state) shouldBe "Nxd5"
    }

    "render a pawn capture with the source file" in {
      val state = parseFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")
      San.toSan(Move(Pos(3, 4), Pos(4, 3)), state) shouldBe "exd5"
    }

    "render an en passant capture" in {
      val state = parseFen("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1")
      San.toSan(Move(Pos(4, 4), Pos(5, 3)), state) shouldBe "exd6"
    }

    "render pawn promotion to queen" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      San.toSan(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen)), state) shouldBe "e8=Q"
    }

    "render pawn promotion to rook, bishop, knight" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      San.toSan(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Rook)),   state) shouldBe "e8=R"
      San.toSan(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Bishop)), state) shouldBe "e8=B"
      San.toSan(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Knight)), state) shouldBe "e8=N"
    }

    "append + when the move gives check" in {
      val state = parseFen("4k3/8/8/8/8/8/4R3/4K3 w - - 0 1")
      San.toSan(Move(Pos(1, 4), Pos(6, 4)), state) shouldBe "Re7+"
    }

    "append # when the move is checkmate" in {
      val state = parseFen("6k1/5ppp/8/8/8/8/8/4Q1K1 w - - 0 1")
      San.toSan(Move(Pos(0, 4), Pos(7, 4)), state) shouldBe "Qe8#"
    }

    "disambiguate by file when two pieces share a rank" in {
      val state = parseFen("4k3/8/8/8/8/1N3N2/8/4K3 w - - 0 1")
      San.toSan(Move(Pos(2, 1), Pos(3, 3)), state) shouldBe "Nbd4"
      San.toSan(Move(Pos(2, 5), Pos(3, 3)), state) shouldBe "Nfd4"
    }

    "disambiguate by rank when two pieces share a file" in {
      val state = parseFen("4k3/8/8/4N3/8/8/8/4N1K1 w - - 0 1")
      San.toSan(Move(Pos(0, 4), Pos(2, 3)), state) shouldBe "N1d3"
    }

    "return ? when no piece is at the source square" in {
      San.toSan(Move(Pos(3, 3), Pos(4, 4)), GameState.initial) shouldBe "?"
    }
  }

  "San.fromSan" should {
    "parse a simple pawn push" in {
      San.fromSan("e4", GameState.initial) shouldBe Right(Move(Pos(1, 4), Pos(3, 4)))
    }

    "parse a knight move" in {
      San.fromSan("Nf3", GameState.initial) shouldBe Right(Move(Pos(0, 6), Pos(2, 5)))
    }

    "parse O-O kingside castling" in {
      val state = parseFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
      San.fromSan("O-O", state) shouldBe Right(Move(Pos(0, 4), Pos(0, 6)))
    }

    "accept the 0-0 alternative castling notation" in {
      val state = parseFen("4k3/8/8/8/8/8/8/4K2R w K - 0 1")
      San.fromSan("0-0", state) shouldBe Right(Move(Pos(0, 4), Pos(0, 6)))
    }

    "parse O-O-O queenside castling" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1")
      San.fromSan("O-O-O", state) shouldBe Right(Move(Pos(0, 4), Pos(0, 2)))
    }

    "accept the 0-0-0 alternative castling notation" in {
      val state = parseFen("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1")
      San.fromSan("0-0-0", state) shouldBe Right(Move(Pos(0, 4), Pos(0, 2)))
    }

    "strip + and # suffixes before parsing" in {
      San.fromSan("Nf3+", GameState.initial) shouldBe Right(Move(Pos(0, 6), Pos(2, 5)))
      San.fromSan("Nf3#", GameState.initial) shouldBe Right(Move(Pos(0, 6), Pos(2, 5)))
    }

    "parse a pawn capture with file disambiguation" in {
      val state = parseFen("4k3/8/8/3p4/4P3/8/8/4K3 w - - 0 1")
      San.fromSan("exd5", state) shouldBe Right(Move(Pos(3, 4), Pos(4, 3)))
    }

    "parse explicit pawn promotion =Q" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      San.fromSan("e8=Q", state) shouldBe Right(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen)))
    }

    "default promotion to queen when the suffix is missing" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      San.fromSan("e8", state) shouldBe Right(Move(Pos(6, 4), Pos(7, 4), Some(PieceType.Queen)))
    }

    "parse all promotion targets" in {
      val state = parseFen("8/4P3/8/8/8/8/8/k3K3 w - - 0 1")
      San.fromSan("e8=R", state).toOption.get.promotion shouldBe Some(PieceType.Rook)
      San.fromSan("e8=B", state).toOption.get.promotion shouldBe Some(PieceType.Bishop)
      San.fromSan("e8=N", state).toOption.get.promotion shouldBe Some(PieceType.Knight)
    }

    "report an ambiguous SAN as Left" in {
      val state = parseFen("4k3/8/8/8/8/1N3N2/8/4K3 w - - 0 1")
      San.fromSan("Nd4", state) shouldBe a[Left[_, _]]
    }

    "resolve an ambiguous SAN with file disambiguation" in {
      val state = parseFen("4k3/8/8/8/8/1N3N2/8/4K3 w - - 0 1")
      San.fromSan("Nbd4", state) shouldBe Right(Move(Pos(2, 1), Pos(3, 3)))
      San.fromSan("Nfd4", state) shouldBe Right(Move(Pos(2, 5), Pos(3, 3)))
    }

    "resolve an ambiguous SAN with rank disambiguation" in {
      val state = parseFen("4k3/8/8/4N3/8/8/8/4N1K1 w - - 0 1")
      San.fromSan("N1d3", state) shouldBe Right(Move(Pos(0, 4), Pos(2, 3)))
      San.fromSan("N5d3", state) shouldBe Right(Move(Pos(4, 4), Pos(2, 3)))
    }

    "reject SAN that has no legal matching move" in {
      San.fromSan("e5", GameState.initial) shouldBe a[Left[_, _]]
    }

    "reject SAN with an invalid destination square" in {
      San.fromSan("Z9", GameState.initial) shouldBe a[Left[_, _]]
    }

    "reject SAN that is too short to contain a destination" in {
      San.fromSan("a", GameState.initial) shouldBe a[Left[_, _]]
    }
  }
