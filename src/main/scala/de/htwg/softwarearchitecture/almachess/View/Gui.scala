package de.htwg.softwarearchitecture.almachess.view

import de.htwg.softwarearchitecture.almachess.control.Controller
import de.htwg.softwarearchitecture.almachess.model.{Color, Piece, PieceType, Pos}
import scala.swing.*
import scala.swing.Dialog.Message
import scala.swing.event.{ButtonClicked, MouseDragged, MousePressed, MouseReleased}
import java.awt.{AlphaComposite, Color as AwtColor, Font, Graphics2D, RenderingHints}
import java.io.{File, PrintWriter}
import java.nio.file.Files
import javax.swing.{BorderFactory, JFileChooser, UIManager}
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.ColorUIResource

class Gui(controller: Controller) extends MainFrame:

  // ── Dark colour palette ───────────────────────────────────────────────────
  private val C_BG       = new AwtColor( 24,  24,  24)
  private val C_BG2      = new AwtColor( 36,  36,  36)
  private val C_TEXT     = new AwtColor(210, 210, 210)
  private val C_MUTED    = new AwtColor(140, 140, 140)
  private val C_BTN      = new AwtColor( 55,  55,  55)
  private val C_FIELD    = new AwtColor( 42,  42,  42)
  private val C_BORDER   = new AwtColor( 68,  68,  68)
  private val C_SEL_BG   = new AwtColor( 60,  98, 150)
  private val C_SEL_FG   = new AwtColor(240, 240, 240)
  private val C_OK       = new AwtColor(110, 190, 110)
  private val C_ERR      = new AwtColor(210,  90,  90)

  // ── Push dark defaults into UIManager before any component is created ─────
  locally {
    val pairs = Seq(
      "Panel.background"              -> C_BG,
      "OptionPane.background"         -> C_BG2,
      "Label.foreground"              -> C_TEXT,
      "Label.background"              -> C_BG,
      "Button.background"             -> C_BTN,
      "Button.foreground"             -> C_TEXT,
      "Button.select"                 -> C_BTN,
      "TextField.background"          -> C_FIELD,
      "TextField.foreground"          -> C_TEXT,
      "TextField.caretForeground"     -> C_TEXT,
      "TextField.selectionBackground" -> C_SEL_BG,
      "TextField.selectionForeground" -> C_SEL_FG,
      "TextArea.background"           -> C_FIELD,
      "TextArea.foreground"           -> C_TEXT,
      "TextArea.caretForeground"      -> C_TEXT,
      "TextArea.selectionBackground"  -> C_SEL_BG,
      "TextArea.selectionForeground"  -> C_SEL_FG,
      "ComboBox.background"           -> C_BTN,
      "ComboBox.foreground"           -> C_TEXT,
      "ComboBox.selectionBackground"  -> C_SEL_BG,
      "ComboBox.selectionForeground"  -> C_SEL_FG,
      "ScrollPane.background"         -> C_BG,
      "Viewport.background"           -> C_FIELD,
      "ScrollBar.background"          -> C_BG2,
      "ScrollBar.thumb"               -> new AwtColor(72, 72, 72),
      "ScrollBar.track"               -> C_BG2,
      "List.background"               -> C_FIELD,
      "List.foreground"               -> C_TEXT,
      "List.selectionBackground"      -> C_SEL_BG,
      "List.selectionForeground"      -> C_SEL_FG,
      "PopupMenu.background"          -> C_BG2,
      "MenuItem.background"           -> C_BG2,
      "MenuItem.foreground"           -> C_TEXT,
    )
    pairs.foreach { case (k, c) =>
      UIManager.put(k, new ColorUIResource(c.getRed, c.getGreen, c.getBlue))
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def mkButton(label: String): Button = new Button(label):
    background = C_BTN
    foreground = C_TEXT

  private def mkLabel(text: String, bold: Boolean = false): Label = new Label(text):
    foreground = C_TEXT
    if bold then font = new Font("SansSerif", Font.BOLD, 13)

  // ── Controls ──────────────────────────────────────────────────────────────
  private val statusLabel    = mkLabel(controller.state.status)
  private val fenField       = new TextField:
    columns    = 44
    text       = controller.toFen
    background = C_FIELD
    foreground = C_TEXT
  private val loadFenButton  = mkButton("FEN laden")
  private val resetButton    = mkButton("Reset")
  private val undoButton     = mkButton("Undo")
  private val redoButton     = mkButton("Redo")
  private val promotionBox   = new ComboBox(Seq("Q", "R", "B", "N")):
    selection.item = "Q"
    background     = C_BTN
    foreground     = C_TEXT

  // ── PGN panel ─────────────────────────────────────────────────────────────
  private val pgnArea = new TextArea:
    font       = new Font("Monospaced", Font.PLAIN, 12)
    background = C_FIELD
    foreground = C_TEXT
    lineWrap   = true
    wordWrap   = true
    text       = controller.exportPgn()
    peer.setCaretColor(C_TEXT)
    peer.setSelectionColor(C_SEL_BG)
    peer.setSelectedTextColor(C_SEL_FG)
    peer.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6))

  private val loadPgnButton  = mkButton("Laden")
  private val savePgnButton  = mkButton("Speichern")
  private val loadJsonButton = mkButton("JSON laden")
  private val saveJsonButton = mkButton("JSON speichern")

  // ── refreshUi ─────────────────────────────────────────────────────────────
  private def refreshUi(): Unit =
    fenField.text      = controller.toFen
    statusLabel.text   = controller.state.status
    statusLabel.foreground = C_TEXT
    undoButton.enabled = controller.canUndo
    redoButton.enabled = controller.canRedo
    pgnArea.text       = controller.exportPgn()
    boardPanel.repaint()

  private def setStatus(msg: String, isError: Boolean = false): Unit =
    statusLabel.text      = msg
    statusLabel.foreground = if isError then C_ERR else C_TEXT

  // ── Chess board panel ─────────────────────────────────────────────────────
  private class ChessBoardPanel extends Panel:
    preferredSize = new Dimension(640, 640)
    focusable     = true
    background    = C_BG

    private var draggingFrom:  Option[Pos]   = None
    private var draggingPiece: Option[Piece] = None
    private var dragMouseX:    Int           = 0
    private var dragMouseY:    Int           = 0
    private var hoverSquare:   Option[Pos]   = None

    listenTo(mouse.clicks, mouse.moves)

    reactions += {
      case e: MousePressed =>
        if !controller.isGameOver then
          requestFocus()
          squareAt(e.point.x, e.point.y).foreach { pos =>
            controller.state.board.pieceAt(pos) match
              case Some(piece) if piece.color == controller.state.turn =>
                draggingFrom  = Some(pos)
                draggingPiece = Some(piece)
                dragMouseX    = e.point.x
                dragMouseY    = e.point.y
                repaint()
              case _ =>
          }

      case e: MouseDragged =>
        if draggingPiece.nonEmpty then
          dragMouseX  = e.point.x
          dragMouseY  = e.point.y
          hoverSquare = squareAt(e.point.x, e.point.y)
          repaint()

      case e: MouseReleased =>
        val dropSquare = squareAt(e.point.x, e.point.y)
        (draggingFrom, draggingPiece, dropSquare) match
          case (Some(from), Some(piece), Some(to)) =>
            val needsPromotion = piece.tpe == PieceType.Pawn && (to.rank == 0 || to.rank == 7)
            val promo          = if needsPromotion then Some(promotionBox.selection.item) else None
            controller.move(from.toAlgebraic, to.toAlgebraic, promo) match
              case Left(err) => setStatus(s"Fehler: $err", isError = true)
              case Right(_)  =>
                refreshUi()
                if controller.isGameOver then showGameOverDialog(controller.state.status)
          case _ =>
        draggingFrom  = None
        draggingPiece = None
        hoverSquare   = None
        repaint()
    }

    override def paintComponent(g: Graphics2D): Unit =
      super.paintComponent(g)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

      val boardSize = math.min(size.width, size.height - 1)
      val cell      = boardSize / 8
      val offsetX   = (size.width  - cell * 8) / 2
      val offsetY   = (size.height - cell * 8) / 2

      // Fill outer area with dark background
      g.setColor(C_BG)
      g.fillRect(0, 0, size.width, size.height)

      for
        displayRow <- 0 until 8
        file       <- 0 until 8
      do
        val rank = 7 - displayRow
        val x    = offsetX + file * cell
        val y    = offsetY + displayRow * cell
        val pos  = Pos(rank, file)

        val isLight = (rank + file) % 2 == 0
        val baseColor =
          if isLight then new AwtColor(185, 152, 112)   // warm tan
          else             new AwtColor( 90,  57,  30)  // dark walnut

        val squareColor =
          if draggingFrom.contains(pos)     then new AwtColor(200, 200,  70)
          else if hoverSquare.contains(pos) then new AwtColor( 90, 170,  90)
          else baseColor

        g.setColor(squareColor)
        g.fillRect(x, y, cell, cell)

        controller.state.board.pieceAt(pos) match
          case Some(piece) if !draggingFrom.contains(pos) => drawPiece(g, piece, x, y, cell)
          case _ =>

      drawCoordinates(g, offsetX, offsetY, cell)

      draggingPiece.foreach { piece =>
        drawPiece(g, piece, dragMouseX - cell / 2, dragMouseY - cell / 2, cell, 0.85f)
      }

    private def drawCoordinates(g: Graphics2D, offsetX: Int, offsetY: Int, cell: Int): Unit =
      g.setFont(new Font("SansSerif", Font.BOLD, math.max(10, cell / 7)))
      for file <- 0 until 8 do
        val onLight = (0 + file) % 2 == 0
        g.setColor(if onLight then new AwtColor(90, 57, 30) else new AwtColor(185, 152, 112))
        g.drawString(('a' + file).toChar.toString, offsetX + file * cell + cell - 13, offsetY + 8 * cell - 4)
      for ri <- 0 until 8 do
        val rank    = 7 - ri
        val onLight = (rank + 0) % 2 == 0
        g.setColor(if onLight then new AwtColor(90, 57, 30) else new AwtColor(185, 152, 112))
        g.drawString((rank + 1).toString, offsetX + 4, offsetY + ri * cell + 16)

    private def drawPiece(g: Graphics2D, piece: Piece, x: Int, y: Int, cell: Int, alpha: Float = 1.0f): Unit =
      val old      = g.getComposite
      g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha))
      val sym      = piece.unicode
      val sz       = (cell * 0.72).toInt.max(18)
      g.setFont(new Font("SansSerif", Font.PLAIN, sz))
      val m        = g.getFontMetrics
      val tx       = x + (cell - m.stringWidth(sym)) / 2
      val ty       = y + (cell + m.getAscent) / 2 - 6
      val fg       = if piece.color == Color.White then new AwtColor(248, 248, 248) else new AwtColor(16, 16, 16)
      val outline  = if piece.color == Color.White then new AwtColor(40, 40, 40)   else new AwtColor(215, 215, 215)
      g.setColor(outline)
      for (dx, dy) <- Seq((-1,0),(1,0),(0,-1),(0,1)) do g.drawString(sym, tx + dx, ty + dy)
      g.setColor(fg)
      g.drawString(sym, tx, ty)
      g.setComposite(old)

    private def squareAt(mx: Int, my: Int): Option[Pos] =
      val boardSize = math.min(size.width, size.height - 1)
      val cell      = boardSize / 8
      val ox        = (size.width  - cell * 8) / 2
      val oy        = (size.height - cell * 8) / 2
      val lx        = mx - ox
      val ly        = my - oy
      if lx < 0 || ly < 0 || lx >= cell * 8 || ly >= cell * 8 then None
      else Some(Pos(7 - ly / cell, lx / cell))

  private val boardPanel = new ChessBoardPanel

  // ── PGN right panel ───────────────────────────────────────────────────────

  private val pgnScrollPane = new ScrollPane(pgnArea):
    preferredSize = new Dimension(270, 520)
    background    = C_BG2
    border        = BorderFactory.createLineBorder(C_BORDER, 1)
    peer.getVerticalScrollBar.setUnitIncrement(16)

  private val rightPanel = new BorderPanel:
    background    = C_BG2
    preferredSize = new Dimension(290, 640)
    border        = BorderFactory.createMatteBorder(0, 1, 0, 0, C_BORDER)

    layout(
      new BoxPanel(Orientation.Vertical):
        background = C_BG2
        border     = Swing.EmptyBorder(10, 10, 6, 10)
        contents  += mkLabel("PGN", bold = true)
    ) = BorderPanel.Position.North

    layout(pgnScrollPane) = BorderPanel.Position.Center

    layout(
      new BoxPanel(Orientation.Vertical):
        background = C_BG2
        border     = Swing.EmptyBorder(8, 10, 10, 10)

        contents += new BoxPanel(Orientation.Horizontal):
          background = C_BG2
          contents  += loadPgnButton
          contents  += Swing.HStrut(6)
          contents  += savePgnButton

        contents += Swing.VStrut(6)

        contents += new BoxPanel(Orientation.Horizontal):
          background = C_BG2
          contents  += loadJsonButton
          contents  += Swing.HStrut(6)
          contents  += saveJsonButton

        contents += Swing.VStrut(4)

        contents += new Label("Paste PGN → Laden"):
          foreground = C_MUTED
          font       = new Font("SansSerif", Font.PLAIN, 11)
    ) = BorderPanel.Position.South

  // ── Bottom control strip ──────────────────────────────────────────────────

  private val controlPanel = new BoxPanel(Orientation.Vertical):
    background = C_BG2
    border     = BorderFactory.createCompoundBorder(
      BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
      Swing.EmptyBorder(8, 10, 10, 10)
    )

    contents += new BoxPanel(Orientation.Horizontal):
      background = C_BG2
      contents  += mkLabel("Promotion:")
      contents  += Swing.HStrut(6)
      contents  += promotionBox
      contents  += Swing.HStrut(18)
      contents  += resetButton
      contents  += Swing.HStrut(6)
      contents  += undoButton
      contents  += Swing.HStrut(6)
      contents  += redoButton

    contents += Swing.VStrut(8)

    contents += new BoxPanel(Orientation.Horizontal):
      background = C_BG2
      contents  += mkLabel("FEN:")
      contents  += Swing.HStrut(6)
      contents  += fenField
      contents  += Swing.HStrut(6)
      contents  += loadFenButton

    contents += Swing.VStrut(8)
    contents += statusLabel

  // ── Root layout ───────────────────────────────────────────────────────────

  title         = "AlmaChess"
  preferredSize = new Dimension(1040, 760)

  contents = new BorderPanel:
    background = C_BG
    layout(boardPanel)   = BorderPanel.Position.Center
    layout(rightPanel)   = BorderPanel.Position.East
    layout(controlPanel) = BorderPanel.Position.South

  // ── Initial state ─────────────────────────────────────────────────────────
  undoButton.enabled = false
  redoButton.enabled = false

  // ── Reactions ─────────────────────────────────────────────────────────────
  listenTo(loadFenButton, resetButton, undoButton, redoButton,
           loadPgnButton, savePgnButton, loadJsonButton, saveJsonButton)

  reactions += {

    case ButtonClicked(`loadFenButton`) =>
      controller.loadFen(fenField.text.trim) match
        case Left(err)  => setStatus(s"Fehler: $err", isError = true)
        case Right(msg) =>
          refreshUi(); setStatus(msg)
          if controller.isGameOver then showGameOverDialog(controller.state.status)

    case ButtonClicked(`resetButton`) =>
      controller.reset()
      refreshUi()
      setStatus("Spiel zurückgesetzt.")

    case ButtonClicked(`undoButton`) =>
      controller.undo() match
        case Left(err) => setStatus(s"Fehler: $err", isError = true)
        case Right(_)  => refreshUi()

    case ButtonClicked(`redoButton`) =>
      controller.redo() match
        case Left(err) => setStatus(s"Fehler: $err", isError = true)
        case Right(_)  => refreshUi()

    // PGN: load from text area (paste & load)
    case ButtonClicked(`loadPgnButton`) =>
      controller.importPgn(pgnArea.text) match
        case Left(err)  => setStatus(s"PGN Fehler: $err", isError = true)
        case Right(msg) =>
          refreshUi(); setStatus(msg)
          if controller.isGameOver then showGameOverDialog(controller.state.status)

    // PGN: save to file
    case ButtonClicked(`savePgnButton`) =>
      val ch = new JFileChooser()
      ch.setDialogTitle("PGN speichern")
      ch.setFileFilter(new FileNameExtensionFilter("PGN files", "pgn"))
      ch.setSelectedFile(new File("game.pgn"))
      if ch.showSaveDialog(null) == JFileChooser.APPROVE_OPTION then
        val f  = ch.getSelectedFile
        val p  = if f.getName.endsWith(".pgn") then f else new File(f.getPath + ".pgn")
        val pw = new PrintWriter(p)
        try pw.write(controller.exportPgn()) finally pw.close()
        setStatus(s"PGN gespeichert: ${p.getName}")

    // JSON: load from file
    case ButtonClicked(`loadJsonButton`) =>
      val ch = new JFileChooser()
      ch.setDialogTitle("JSON laden")
      ch.setFileFilter(new FileNameExtensionFilter("JSON files", "json"))
      if ch.showOpenDialog(null) == JFileChooser.APPROVE_OPTION then
        val content = new String(Files.readAllBytes(ch.getSelectedFile.toPath))
        controller.importJson(content) match
          case Left(err)  => setStatus(s"Fehler: $err", isError = true)
          case Right(msg) => refreshUi(); setStatus(msg)

    // JSON: save to file
    case ButtonClicked(`saveJsonButton`) =>
      val ch = new JFileChooser()
      ch.setDialogTitle("JSON speichern")
      ch.setFileFilter(new FileNameExtensionFilter("JSON files", "json"))
      ch.setSelectedFile(new File("game.json"))
      if ch.showSaveDialog(null) == JFileChooser.APPROVE_OPTION then
        val f  = ch.getSelectedFile
        val p  = if f.getName.endsWith(".json") then f else new File(f.getPath + ".json")
        val pw = new PrintWriter(p)
        try pw.write(controller.exportJson()) finally pw.close()
        setStatus(s"JSON gespeichert: ${p.getName}")
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def showGameOverDialog(status: String): Unit =
    val text =
      if status.startsWith("checkmate") then s"Schachmatt.\n$status"
      else if status == "stalemate"     then "Patt."
      else status
    Dialog.showMessage(parent = this, message = text, title = "Spiel beendet", messageType = Message.Info)
