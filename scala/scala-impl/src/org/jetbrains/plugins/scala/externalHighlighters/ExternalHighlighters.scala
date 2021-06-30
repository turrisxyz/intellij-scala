package org.jetbrains.plugins.scala.externalHighlighters

import com.intellij.codeInsight.daemon.impl.{HighlightInfo, HighlightInfoType, UpdateHighlightersUtil}
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.{Document, Editor, EditorFactory}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi._
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.plugins.scala.annotator.UnresolvedReferenceFixProvider
import org.jetbrains.plugins.scala.editor.DocumentExt
import org.jetbrains.plugins.scala.extensions.{PsiElementExt, inReadAction, invokeLater}
import org.jetbrains.plugins.scala.externalHighlighters.ExternalHighlighting.Pos
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReference
import org.jetbrains.plugins.scala.settings.ProblemSolverUtils

import java.util.Collections
import scala.jdk.CollectionConverters._

object ExternalHighlighters {

  // A random number of highlighters group to avoid conflicts with standard groups.
  private final val ScalaCompilerPassId = 979132998
  
  def applyHighlighting(project: Project,
                        editor: Editor,
                        state: HighlightingState): Unit =
    if (ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(project)) {
      val document = editor.getDocument
      for {
        virtualFile <- document.virtualFile
        psiFile <- Option(inReadAction(PsiManager.getInstance(project).findFile(virtualFile)))
        if ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(psiFile)
      } invokeLater {
        val externalHighlights = state.externalHighlightings(virtualFile)
        val highlightInfos = externalHighlights.flatMap(toHighlightInfo(_, document, psiFile))
        UpdateHighlightersUtil.setHighlightersToEditor(
          project,
          document, 0, document.getTextLength,
          highlightInfos.toSeq.asJava,
          editor.getColorsScheme,
          ScalaCompilerPassId
        )
      }
    }
  
  def eraseAllHighlightings(project: Project): Unit = {
    for {
      editor <- EditorFactory.getInstance.getAllEditors
      editorProject <- Option(editor.getProject)
      if editorProject == project
    } invokeLater {
      if (!project.isDisposed) {
        val document = editor.getDocument
        UpdateHighlightersUtil.setHighlightersToEditor(
          project,
          document, 0, document.getTextLength,
          Collections.emptyList(),
          editor.getColorsScheme,
          ScalaCompilerPassId
        )
      }
    }
    ProblemSolverUtils.clearAllProblemsFromExternalSource(project, this)
  }

  def informWolf(project: Project, state: HighlightingState): Unit =
    if (ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(project)) {
      val errorTypes = Set(HighlightInfoType.ERROR, HighlightInfoType.WRONG_REF)
      ProblemSolverUtils.clearAllProblemsFromExternalSource(project, this)
      val wolf = WolfTheProblemSolver.getInstance(project)
      val errorFiles = state.filesWithHighlightings(errorTypes)
      errorFiles.foreach(wolf.reportProblemsFromExternalSource(_, this))
    }

  private def toHighlightInfo(highlighting: ExternalHighlighting, document: Document, psiFile: PsiFile): Option[HighlightInfo] = {
    val message = highlighting.message
    //noinspection ReferencePassedToNls
    for {
      startOffset <- convertToOffset(highlighting.from, message, document)
      highlightRange <- calculateRangeToHighlight(startOffset, highlighting.to, message, document, psiFile)
      description = message.trim.stripSuffix(lineText(message))
    } yield {
      val highlightInfo = HighlightInfo
        .newHighlightInfo(highlighting.highlightType)
        .range(highlightRange)
        .description(description)
        .escapedToolTip(escapeHtmlWithNewLines(description))
        .group(ScalaCompilerPassId)
        .create()
      val fixes = findQuickFixes(psiFile, highlightRange, highlighting.highlightType)
      fixes.foreach(highlightInfo.registerFix(_, null, null, highlightRange, null))
      highlightInfo
    }
  }

  private def escapeHtmlWithNewLines(unescapedTooltip: String): String = {
    val escaped0 = XmlStringUtil.escapeString(unescapedTooltip)
    val escaped1 = escaped0.replace("\n", "<br>")
    val escaped2 = XmlStringUtil.wrapInHtml(escaped1)
    escaped2
  }

  private def calculateRangeToHighlight(startOffset: Int,
                                        to: Pos,
                                        message: String,
                                        document: Document,
                                        psiFile: PsiFile): Option[TextRange] =
    convertToOffset(to, message, document)
      .filter(_ != startOffset)
      .map { endOffset => TextRange.create(startOffset, endOffset) }
      .orElse(guessRangeToHighlight(psiFile, startOffset))

  private def guessRangeToHighlight(psiFile: PsiFile, startOffset: Int): Option[TextRange] =
    elementToHighlight(psiFile, startOffset).map(_.getTextRange)

  private def elementToHighlight(file: PsiFile, offset: Int): Option[PsiElement] =
    Option(file.findElementAt(offset)).flatMap {
      case whiteSpace: PsiWhiteSpace =>
        whiteSpace.prevElementNotWhitespace
      case javaToken: PsiJavaToken if javaToken.getTokenType == JavaTokenType.DOT =>
        javaToken.nextElementNotWhitespace
      case other =>
        Some(other)
    }

  private def convertToOffset(pos: Pos,
                              message: String,
                              document: Document): Option[Int] = pos match {
    case Pos.LineColumn(l, c) =>
      val line = l - 1
      val column = (c - 1).max(0)
      if (line < 0) {
        None
      } else {
        val lineTextFromMessage = lineText(message)
        // TODO: dotc and scalac report different lines in their messages :(
        val actualLine =
          Seq(line, line - 1, line + 1)
            .find { lineNumber =>
              documentLine(document, lineNumber).contains(lineTextFromMessage)
            }
        actualLine.map(line => document.getLineStartOffset(line) + column)
      }
    case Pos.Offset(offset) =>
      Some(offset)
  }

  private def lineText(messageText: String): String = {
    val trimmed = messageText.trim
    val lastLineSeparator = trimmed.lastIndexOf('\n')
    if (lastLineSeparator > 0) trimmed.substring(lastLineSeparator).trim else ""
  }

  private def documentLine(document: Document, line: Int): Option[String] =
    if (line >= 0 && line < document.getLineCount) {
      val lineStart = document.getLineStartOffset(line)
      val lineEnd = document.getLineEndOffset(line)
      Some(document.getText(TextRange.create(lineStart, lineEnd)).trim)
    } else {
      None
    }

  private def findQuickFixes(file: PsiFile,
                             range: TextRange,
                             highlightInfoType: HighlightInfoType): Seq[IntentionAction] = {
    val ref = PsiTreeUtil.findElementOfClassAtRange(file, range.getStartOffset, range.getEndOffset, classOf[ScReference])

    if (ref != null && highlightInfoType == HighlightInfoType.WRONG_REF && ref.multiResolveScala(false).isEmpty)
      UnresolvedReferenceFixProvider.fixesFor(ref)
    else Seq.empty
  }
}
