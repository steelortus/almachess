package de.htwg.softwarearchitecture.almachess.parser

import fastparse.*
import SingleLineWhitespace.*

/**
 * Algebraic data type describing every command the CLI understands.
 * Using typed case classes (instead of raw strings) gives us compile-time
 * safety and makes the Tui a thin dispatch layer.
 */
sealed trait Command
object Command:
  case class MoveCmd(from: String, to: String, promotion: Option[String]) extends Command
  case object Undo                                                        extends Command
  case object Redo                                                        extends Command
  case object Show                                                        extends Command
  case object ShowFen                                                     extends Command
  case object ShowPgn                                                     extends Command
  case object Exit                                                        extends Command
  case object Help                                                        extends Command
  case class LoadFen(fen: String)                                         extends Command
  case class ExportPgn(path: String)                                      extends Command
  case class ImportPgn(path: String)                                      extends Command
  case class ExportJson(path: String)                                     extends Command
  case class ImportJson(path: String)                                     extends Command
  case class SetAiDepth(depth: Int)                                       extends Command
  case class EnableAi(color: String)                                      extends Command
  case object DisableAi                                                   extends Command

/**
 * Recursive-descent command parser for the AlmaChess CLI, built on top of
 * fastparse (same library the PGN parser uses).
 *
 * Grammar (EBNF-ish):
 *   command    := move | undo | redo | show | fen | pgn
 *               | load | export | import
 *               | setAiDepth | enableAi | disableAi
 *               | help | exit
 *
 *   move       := "move" square square promo?
 *   square     := [a-h][1-8]
 *   promo      := [qrbnQRBN]
 *
 *   load       := "load" "fen"? fenString
 *   export     := "export" ("pgn" | "json") path
 *   import     := "import" ("pgn" | "json") path
 *   setAiDepth := "set" "ai" "depth" integer
 *   enableAi   := "ai" "on" ("white" | "black")
 *   disableAi  := "ai" "off"
 *
 *   path       := quotedString | nonWhitespaceToken
 *   fenString  := quotedString | restOfLine
 */
object CommandParser:
  import Command.*

  // --- Primitive tokens -----------------------------------------------------

  private def square[$: P]: P[String] =
    P(CharIn("a-h") ~ CharIn("1-8")).!

  private def promoChar[$: P]: P[String] =
    P(CharIn("QRBNqrbn")).!

  private def integer[$: P]: P[Int] =
    P(CharsWhileIn("0-9", 1)).!.map(_.toInt)

  private def quoted[$: P]: P[String] =
    P("\"" ~ CharsWhile(_ != '"', 0).! ~ "\"")

  /** A single whitespace-free token — used for plain file paths. */
  private def bareToken[$: P]: P[String] =
    P(CharsWhile(c => !c.isWhitespace && c != '"', 1)).!

  /** Accepts either "quoted path with spaces" or bareToken. */
  private def pathArg[$: P]: P[String] = P(quoted | bareToken)

  /** For FEN: greedily consume the remainder of the line (FEN has internal spaces). */
  private def fenArg[$: P]: P[String] =
    P(quoted | AnyChar.rep(1).!).map(_.trim)

  // --- Individual commands --------------------------------------------------

  private def moveCmd[$: P]: P[Command] =
    P("move" ~ square ~ square ~ promoChar.?).map {
      case (from, to, promo) => MoveCmd(from, to, promo)
    }

  private def undoCmd[$: P]: P[Command] = P("undo").map(_ => Undo)
  private def redoCmd[$: P]: P[Command] = P("redo").map(_ => Redo)
  private def showCmd[$: P]: P[Command] = P("show").map(_ => Show)
  private def fenCmd[$: P]: P[Command]  = P("fen").map(_ => ShowFen)
  private def pgnCmd[$: P]: P[Command]  = P("pgn").map(_ => ShowPgn)
  private def exitCmd[$: P]: P[Command] = P(StringIn("exit", "quit")).map(_ => Exit)
  private def helpCmd[$: P]: P[Command] = P(StringIn("help", "?")).map(_ => Help)

  private def loadCmd[$: P]: P[Command] =
    P("load" ~ "fen".? ~ fenArg).map(LoadFen(_))

  private def exportPgnCmd[$: P]: P[Command] =
    P("export" ~ "pgn" ~ pathArg).map(ExportPgn(_))

  private def exportJsonCmd[$: P]: P[Command] =
    P("export" ~ "json" ~ pathArg).map(ExportJson(_))

  private def importPgnCmd[$: P]: P[Command] =
    P("import" ~ "pgn" ~ pathArg).map(ImportPgn(_))

  private def importJsonCmd[$: P]: P[Command] =
    P("import" ~ "json" ~ pathArg).map(ImportJson(_))

  private def setAiDepthCmd[$: P]: P[Command] =
    P("set" ~ "ai" ~ "depth" ~ integer).map(SetAiDepth(_))

  private def enableAiCmd[$: P]: P[Command] =
    P("ai" ~ "on" ~ StringIn("white", "black").!).map(EnableAi(_))

  private def disableAiCmd[$: P]: P[Command] =
    P("ai" ~ "off").map(_ => DisableAi)

  // --- Top-level --------------------------------------------------------
  // Note: alternatives are tried in order; fastparse backtracks on failure,
  // so overlapping prefixes (e.g. "ai on" vs "ai off") are resolved correctly.
  private def command[$: P]: P[Command] =
    P(
      moveCmd        | undoCmd         | redoCmd         |
      showCmd        | fenCmd          | pgnCmd          |
      exportPgnCmd   | exportJsonCmd   |
      importPgnCmd   | importJsonCmd   |
      setAiDepthCmd  | enableAiCmd     | disableAiCmd    |
      loadCmd        | helpCmd         | exitCmd
    )

  private def fullCommand[$: P]: P[Command] =
    P(Start ~ command ~ End)

  /**
   * Parses a raw CLI line into a typed Command.
   * Returns Left with a human-readable error message on failure.
   */
  def parse(input: String): Either[String, Command] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left("empty command")
    else
      fastparse.parse(trimmed, fullCommand(_)) match
        case Parsed.Success(cmd, _) => Right(cmd)
        case _: Parsed.Failure      => Left(s"unknown command: $trimmed")
