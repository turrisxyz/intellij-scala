package org.jetbrains.plugins.scala.refactoring.introduceVariable

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.{CharsetToolkit, LocalFileSystem}
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.base.ScalaLightPlatformCodeInsightTestCaseAdapter
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.refactoring.introduceVariable.{ScopeItem, ScopeSuggester}
import org.junit.Assert

import java.io.File
import scala.annotation.nowarn

/**
 * Created by user 
 * on 10/15/15
 */
@nowarn("msg=ScalaLightPlatformCodeInsightTestCaseAdapter")
abstract class AbstractScopeSuggesterTest extends ScalaLightPlatformCodeInsightTestCaseAdapter {
  val BEGIN_MARKER: String = "/*begin*/"
  val END_MARKER: String = "/*end*/"

  protected def folderPath = baseRootPath + "introduceVariable/scopeSuggester/"

  protected def doTest(suggestedScopesNames: Seq[String]): Unit = {
    val filePath = folderPath + getTestName(false) + ".scala"
    val file = LocalFileSystem.getInstance.findFileByPath(filePath.replace(File.separatorChar, '/'))
    assert(file != null, "file " + filePath + " not found")

    val fileText = StringUtil.convertLineSeparators(FileUtil.loadFile(new File(file.getCanonicalPath), CharsetToolkit.UTF8))
    configureFromFileTextAdapter(getTestName(false) + ".scala", fileText)

    val startOffset = fileText.indexOf(BEGIN_MARKER) + BEGIN_MARKER.length
    val endOffset = fileText.indexOf(END_MARKER)

    assert(startOffset != -1, "Not specified caret marker in test case. Use /*caret*/ in scala file for this.")

    @nowarn("cat=deprecation")
    val editor = CommonDataKeys.EDITOR.getData(DataManager.getInstance().getDataContextFromFocus.getResult)

    editor.getSelectionModel.setSelection(startOffset, endOffset)

    val scalaFile = getFileAdapter.asInstanceOf[ScalaFile]
    @nowarn("cat=deprecation")
    var element = CommonDataKeys.PSI_ELEMENT.getData(DataManager.getInstance().getDataContextFromFocus.getResult)
    if (element == null) {
      element = PsiTreeUtil.findElementOfClassAtRange(scalaFile, startOffset, endOffset, classOf[PsiElement])
    }

    assert(element.isInstanceOf[ScTypeElement], "Selected element should be ScTypeElement")

    val scopes: Array[ScopeItem] = ScopeSuggester.suggestScopes(element.asInstanceOf[ScTypeElement])
    Assert.assertEquals(scopes.map(_.name).sorted.mkString(", "), suggestedScopesNames.sorted.mkString(", "))
  }
}