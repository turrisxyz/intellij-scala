package org.jetbrains.plugins.scala.compilationCharts.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.UIUtil.{FontColor, FontSize}

import java.awt.Color

object Common {

  final val ProgressRowHeight = new JBTable().getRowHeight * 1.5

  final val DashLength = ProgressRowHeight / 5

  final val NormalFont = UIUtil.getLabelFont(FontSize.NORMAL)
  final val SmallFont = UIUtil.getLabelFont(FontSize.SMALL)

  final val TextColor = UIUtil.getLabelFontColor(FontColor.NORMAL)
  final val LineColor = UIUtil.getInactiveTextColor

  def diagramBackgroundColor: Color =
    EditorColorsManager.getInstance().getSchemeForCurrentUITheme.getDefaultBackground

  final val TestModuleColor = new Color(98, 181, 67, 153)
  final val ProdModuleColor = new Color(64, 182, 224, 153)
  final val MemoryLineColor = new Color(231, 45, 45)

  private final val ThickStroke = new LineStroke(1.5F)
  private final val ThinStroke = new LineStroke(0.5F)

  final val BorderStroke = ThinStroke
  final val DashStroke = ThinStroke
  final val MemoryLineStroke = ThickStroke
  final val ProgressLineStroke = ThinStroke

}
