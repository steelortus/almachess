package de.htwg.softwarearchitecture.almachess.view

import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.{Color, Piece, PieceType, Pos}
import scala.swing.*
import scala.swing.Dialog.Message
import scala.swing.event.{ButtonClicked, MouseDragged, MousePressed, MouseReleased}
import java.awt.{AlphaComposite, Color as AwtColor, Font, Graphics2D, RenderingHints}

class Gui(controller: Controller) extends MainFrame:

  title = "AlmaChess"
  preferredSize = new Dimension(980, 760)

  private val statusLabel = new Label(controller.state.status)

  private val fenField = new TextField:
    columns = 60
    text = controller.toFen

  private val loadFenButton = new Button("FEN laden")
  private val resetButton = new Button("Reset")

  private val promotionBox = new ComboBox(Seq("Q", "R", "B", "N")):
    selection.item = "Q"

  private def showGameOverDialog(status: String): Unit =
    val text =
      if status.startsWith("checkmate") then s"Schachmatt.\n$status"
      else if status == "stalemate" then "Patt."
      else status

    Dialog.showMessage(
      parent = this,
      message = text,
      title = "Spiel beendet",
      messageType = Message.Info
    )

  private class ChessBoardPanel extends Panel:
    preferredSize = new Dimension(640, 640)
    focusable = true

    private var draggingFrom: Option[Pos] = None
    private var draggingPiece: Option[Piece] = None
    private var dragMouseX: Int = 0
    private var dragMouseY: Int = 0
    private var hoverSquare: Option[Pos] = None

    listenTo(mouse.clicks, mouse.moves)

    reactions += {
      case e: MousePressed =>
        if !controller.isGameOver then
          requestFocus()
          squareAt(e.point.x, e.point.y).foreach { pos =>
            controller.state.board.pieceAt(pos) match
              case Some(piece) if piece.color == controller.state.turn =>
                draggingFrom = Some(pos)
                draggingPiece = Some(piece)
                dragMouseX = e.point.x
                dragMouseY = e.point.y
                repaint()
              case _ =>
          }

      case e: MouseDragged =>
        if draggingPiece.nonEmpty then
          dragMouseX = e.point.x
          dragMouseY = e.point.y
          hoverSquare = squareAt(e.point.x, e.point.y)
          repaint()

      case e: MouseReleased =>
        val dropSquare = squareAt(e.point.x, e.point.y)

        (draggingFrom, draggingPiece, dropSquare) match
          case (Some(from), Some(piece), Some(to)) =>
            val needsPromotion =
              piece.tpe == PieceType.Pawn && (to.rank == 0 || to.rank == 7)

            val promo =
              if needsPromotion then Some(promotionBox.selection.item)
              else None

            controller.move(from.toAlgebraic, to.toAlgebraic, promo) match
              case Left(err) =>
                statusLabel.text = s"Fehler: $err"

              case Right(msg) =>
                statusLabel.text = msg
                fenField.text = controller.toFen

                if controller.isGameOver then
                  showGameOverDialog(controller.state.status)

          case _ =>

        draggingFrom = None
        draggingPiece = None
        hoverSquare = None
        repaint()
    }

    override def paintComponent(g: Graphics2D): Unit =
      super.paintComponent(g)

      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

      val boardSize = math.min(size.width, size.height - 1)
      val cell = boardSize / 8
      val offsetX = (size.width - cell * 8) / 2
      val offsetY = (size.height - cell * 8) / 2

      for
        displayRow <- 0 until 8
        file <- 0 until 8
      do
        val rank = 7 - displayRow
        val x = offsetX + file * cell
        val y = offsetY + displayRow * cell
        val pos = Pos(rank, file)

        val isLight = (rank + file) % 2 == 0
        val baseColor =
          if isLight then new AwtColor(240, 217, 181)
          else new AwtColor(181, 136, 99)

        val squareColor =
          if draggingFrom.contains(pos) then new AwtColor(246, 246, 105)
          else if hoverSquare.contains(pos) then new AwtColor(170, 210, 120)
          else baseColor

        g.setColor(squareColor)
        g.fillRect(x, y, cell, cell)

        g.setColor(new AwtColor(60, 60, 60))
        g.drawRect(x, y, cell, cell)

        controller.state.board.pieceAt(pos) match
          case Some(piece) if !draggingFrom.contains(pos) =>
            drawPiece(g, piece, x, y, cell)
          case _ =>

      drawCoordinates(g, offsetX, offsetY, cell)

      draggingPiece.foreach { piece =>
        val drawX = dragMouseX - cell / 2
        val drawY = dragMouseY - cell / 2
        drawPiece(g, piece, drawX, drawY, cell, 0.85f)
      }

    private def drawCoordinates(g: Graphics2D, offsetX: Int, offsetY: Int, cell: Int): Unit =
      g.setColor(new AwtColor(40, 40, 40))
      g.setFont(new Font("SansSerif", Font.BOLD, math.max(12, cell / 6)))

      for file <- 0 until 8 do
        val text = ('a' + file).toChar.toString
        val x = offsetX + file * cell + cell - 14
        val y = offsetY + 8 * cell - 6
        g.drawString(text, x, y)

      for rankIdx <- 0 until 8 do
        val text = (8 - rankIdx).toString
        val x = offsetX + 6
        val y = offsetY + rankIdx * cell + 16
        g.drawString(text, x, y)

    private def drawPiece(
        g: Graphics2D,
        piece: Piece,
        x: Int,
        y: Int,
        cell: Int,
        alpha: Float = 1.0f
    ): Unit =
      val oldComposite = g.getComposite
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha))

      val symbol = unicodePiece(piece)
      val fontSize = (cell * 0.72).toInt.max(18)
      g.setFont(new Font("SansSerif", Font.PLAIN, fontSize))

      val metrics = g.getFontMetrics
      val textWidth = metrics.stringWidth(symbol)
      val textHeight = metrics.getAscent

      val pieceColor =
        if piece.color == Color.White then new AwtColor(250, 250, 250)
        else new AwtColor(25, 25, 25)

      val outlineColor =
        if piece.color == Color.White then new AwtColor(40, 40, 40)
        else new AwtColor(220, 220, 220)

      val tx = x + (cell - textWidth) / 2
      val ty = y + (cell + textHeight) / 2 - 6

      g.setColor(outlineColor)
      g.drawString(symbol, tx - 1, ty)
      g.drawString(symbol, tx + 1, ty)
      g.drawString(symbol, tx, ty - 1)
      g.drawString(symbol, tx, ty + 1)

      g.setColor(pieceColor)
      g.drawString(symbol, tx, ty)

      g.setComposite(oldComposite)

    private def unicodePiece(piece: Piece): String =
      (piece.color, piece.tpe) match
        case (Color.White, PieceType.King)   => "♔"
        case (Color.White, PieceType.Queen)  => "♕"
        case (Color.White, PieceType.Rook)   => "♖"
        case (Color.White, PieceType.Bishop) => "♗"
        case (Color.White, PieceType.Knight) => "♘"
        case (Color.White, PieceType.Pawn)   => "♙"
        case (Color.Black, PieceType.King)   => "♚"
        case (Color.Black, PieceType.Queen)  => "♛"
        case (Color.Black, PieceType.Rook)   => "♜"
        case (Color.Black, PieceType.Bishop) => "♝"
        case (Color.Black, PieceType.Knight) => "♞"
        case (Color.Black, PieceType.Pawn)   => "♟"

    private def squareAt(mouseX: Int, mouseY: Int): Option[Pos] =
      val boardSize = math.min(size.width, size.height - 1)
      val cell = boardSize / 8
      val offsetX = (size.width - cell * 8) / 2
      val offsetY = (size.height - cell * 8) / 2

      val localX = mouseX - offsetX
      val localY = mouseY - offsetY

      if localX < 0 || localY < 0 || localX >= cell * 8 || localY >= cell * 8 then None
      else
        val file = localX / cell
        val displayRow = localY / cell
        val rank = 7 - displayRow
        Some(Pos(rank, file))

  private val boardPanel = new ChessBoardPanel

  contents = new BorderPanel:
    layout(boardPanel) = BorderPanel.Position.Center

    layout(
      new BoxPanel(Orientation.Vertical):
        border = Swing.EmptyBorder(10, 10, 10, 10)

        contents += new BoxPanel(Orientation.Horizontal):
          contents += new Label("Promotion:")
          contents += Swing.HStrut(8)
          contents += promotionBox
          contents += Swing.HStrut(20)
          contents += resetButton

        contents += Swing.VStrut(14)

        contents += new BoxPanel(Orientation.Horizontal):
          contents += new Label("FEN:")
          contents += Swing.HStrut(8)
          contents += fenField
          contents += Swing.HStrut(8)
          contents += loadFenButton

        contents += Swing.VStrut(14)
        contents += statusLabel
    ) = BorderPanel.Position.South

  listenTo(loadFenButton, resetButton)

  reactions += {
    case ButtonClicked(`loadFenButton`) =>
      controller.loadFen(fenField.text.trim) match
        case Left(err) =>
          statusLabel.text = s"Fehler: $err"
        case Right(msg) =>
          statusLabel.text = msg
          fenField.text = controller.toFen
          boardPanel.repaint()

          if controller.isGameOver then
            showGameOverDialog(controller.state.status)

    case ButtonClicked(`resetButton`) =>
      controller.reset()
      statusLabel.text = "Spiel zurückgesetzt."
      fenField.text = controller.toFen
      boardPanel.repaint()
  }