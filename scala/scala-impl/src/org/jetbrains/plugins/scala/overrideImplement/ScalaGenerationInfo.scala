package org.jetbrains.plugins.scala
package overrideImplement

import com.intellij.codeInsight.generation.GenerationInfoBase
import com.intellij.ide.fileTemplates.{FileTemplate, FileTemplateManager}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.pom.Navigatable
import com.intellij.psi._
import com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.plugins.scala.actions.ScalaFileTemplateUtil
import org.jetbrains.plugins.scala.codeInspection.targetNameAnnotation.addTargetNameAnnotationIfNeeded
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.TypeAdjuster
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScBlockExpr
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScClassParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScTemplateDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory._
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, ScTypeExt}
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.overrideImplement.ScalaGenerationInfo._
import org.jetbrains.plugins.scala.settings.ScalaApplicationSettings
import org.jetbrains.plugins.scala.util.TypeAnnotationUtil

import java.util.Properties

/**
 * Nikolay.Tropin
 * 12/25/13
 */
class ScalaGenerationInfo(classMember: ClassMember)
        extends GenerationInfoBase {

  private var myMember: PsiMember = classMember.getElement

  override def getPsiMember: PsiMember = myMember

  override def insert(aClass: PsiClass, anchor: PsiElement, before: Boolean): Unit = {
    val templDef = aClass match {
      case td: ScTemplateDefinition => td
      case _ => return
    }


    val comment = if (ScalaApplicationSettings.getInstance().COPY_SCALADOC)
      Option(classMember.getElement.getDocComment).map(_.getText).getOrElse("") else ""

    classMember match {
      case member: ScMethodMember => myMember = insertMethod(member, templDef, anchor)
      case ScAliasMember(alias, substitutor, isOverride) =>
        val needsOverride = isOverride || toAddOverrideToImplemented
        val m = createOverrideImplementType(alias, substitutor, needsOverride, comment)(alias.getManager)

        val added = templDef.addMember(m, Option(anchor))
        addTargetNameAnnotationIfNeeded(added, alias)
        myMember = added
        TypeAdjuster.markToAdjust(added)
      case member: ScValueOrVariableMember[_] =>
        val m: ScMember = createVariable(comment, classMember)
        val added = templDef.addMember(m, Option(anchor))
        addTargetNameAnnotationIfNeeded(added, if (member.element.is[ScClassParameter]) member.element else member.getElement)
        myMember = added
        TypeAdjuster.markToAdjust(added)
      case _ =>
    }
  }

  override def findInsertionAnchor(aClass: PsiClass, leaf: PsiElement): PsiElement = {
    aClass match {
      case td: ScTemplateDefinition => ScalaOIUtil.getAnchor(leaf.getTextRange.getStartOffset, td).orNull
      case _ => super.findInsertionAnchor(aClass, leaf)
    }
  }

  override def positionCaret(editor: Editor, toEditMethodBody: Boolean): Unit = {
    val element = getPsiMember
    ScalaGenerationInfo.positionCaret(editor, element)
  }
}

object ScalaGenerationInfo {
  def defaultValue: String = "???"

  def positionCaret(editor: Editor, element: PsiMember): Unit = {
    //hack for postformatting IDEA bug.
    val member =
      try CodeStyleManager.getInstance(element.getProject).reformat(element)
      catch { case _: AssertionError => /*¯\_(ツ)_/¯*/  element }
    //Setting selection
    val body: PsiElement = member match {
      case ta: ScTypeAliasDefinition => ta.aliasedTypeElement match {
        case Some(x) => x
        case None => return
      }
      case ScPatternDefinition.expr(expr) => expr
      case ScVariableDefinition.expr(expr) => expr
      case method: ScFunctionDefinition => method.body match {
        case Some(x) => x
        case None => return
      }
      case _ => return
    }

    val offset = member.getTextRange.getStartOffset
    val point = editor.visualPositionToXY(editor.offsetToVisualPosition(offset))
    if (!editor.getScrollingModel.getVisibleArea.contains(point)) {
      member match {
        case n: Navigatable => n.navigate(true)
        case _ =>
      }
    }

    body match {
      case e: ScBlockExpr =>
        val statements = e.statements
        if (statements.isEmpty) {
          editor.getCaretModel.moveToOffset(body.getTextRange.getStartOffset + 1)
        } else {
          val range = new TextRange(statements.head.getTextRange.getStartOffset, statements.last.getTextRange.getEndOffset)
          editor.getCaretModel.moveToOffset(range.getStartOffset)
          editor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)
        }
      case _ =>
        val range = body.getTextRange
        editor.getCaretModel.moveToOffset(range.getStartOffset)
        editor.getSelectionModel.setSelection(range.getStartOffset, range.getEndOffset)
    }
  }

  private def callSuperText(td: ScTemplateDefinition, method: PsiMethod): String = {
    val superOrSelfQual: String = td.selfType match {
      case None => "super."
      case Some(st: ScType) =>
        val psiClass = st.extractClass.getOrElse(return "super.")

        def nonStrictInheritor(base: PsiClass, inheritor: PsiClass): Boolean = {
          if (base == null || inheritor == null) false
          else base == inheritor || inheritor.isInheritorDeep(base, null)
        }

        if (nonStrictInheritor(method.containingClass, psiClass))
          td.selfTypeElement.get.name + "."
        else "super."
    }
    def paramText(param: PsiParameter) = {
      val name = ScalaNamesUtil.escapeKeyword(param.name).toOption.getOrElse("")
      val whitespace = if (name.endsWith("_")) " " else ""
      name + (if (param.isVarArgs) whitespace + ": _*" else "")
    }
    val methodName = ScalaNamesUtil.escapeKeyword(method.name)
    val parametersText: String = {
      method match {
        case fun: ScFunction =>
          val clauses = fun.paramClauses.clauses.filter(!_.isImplicit)
          clauses.map(_.parameters.map(_.name).mkString("(", ", ", ")")).mkString
        case method: PsiMethod =>
          if (method.isAccessor && method.getParameterList.getParametersCount == 0) ""
          else method.parameters.map(paramText).mkString("(", ", ", ")")
      }
    }
    superOrSelfQual + methodName + parametersText
  }

  def getMethodBody(member: ScMethodMember, td: ScTemplateDefinition, isImplement: Boolean):String = {
    val templateName =
      if (isImplement) ScalaFileTemplateUtil.SCALA_IMPLEMENTED_METHOD_TEMPLATE
      else ScalaFileTemplateUtil.SCALA_OVERRIDDEN_METHOD_TEMPLATE

    val template = FileTemplateManager.getInstance(td.getProject).getCodeTemplate(templateName)

    val properties = new Properties()

    val returnType = member.scType

    val standardValue = getStandardValue(returnType)

    val method = member.getElement

    properties.setProperty(FileTemplate.ATTRIBUTE_RETURN_TYPE, returnType.presentableText(method))
    properties.setProperty(FileTemplate.ATTRIBUTE_DEFAULT_RETURN_VALUE, standardValue)
    properties.setProperty(FileTemplate.ATTRIBUTE_CALL_SUPER, callSuperText(td, method))
    properties.setProperty("Q_MARK", ScalaGenerationInfo.defaultValue)

    ScalaFileTemplateUtil.setClassAndMethodNameProperties(properties, method.containingClass, method)

    template.getText(properties)
  }

  def insertMethod(member: ScMethodMember, td: ScTemplateDefinition, anchor: PsiElement): ScFunction = {
    val method: PsiMethod = member.getElement
    val ScMethodMember(signature, isOverride) = member

    val body = getMethodBody(member, td, !isOverride)

    val needsOverride = isOverride || toAddOverrideToImplemented

    val m = createOverrideImplementMethod(signature, needsOverride, body,
      withComment = ScalaApplicationSettings.getInstance().COPY_SCALADOC, withAnnotation = false)(method.getManager)

    val added = td.addMember(m, Option(anchor))
    addTargetNameAnnotationIfNeeded(added, method)
    TypeAnnotationUtil.removeTypeAnnotationIfNeeded(added, typeAnnotationsPolicy)
    TypeAdjuster.markToAdjust(added)
    added.asInstanceOf[ScFunction]
  }

  def createVariable(comment: String, classMember: ClassMember): ScMember = {
    val isVal = classMember.is[ScValueMember]

    val value = classMember match {
      case x: ScValueMember => x.element
      case x: ScVariableMember => x.element
      case _ => ???
    }
    val (substitutor, needsOverride) = classMember match {
      case x: ScValueMember => (x.substitutor, x.isOverride)
      case x: ScVariableMember => (x.substitutor, x.isOverride)
      case _ => ???
    }
    val addOverride = needsOverride || toAddOverrideToImplemented
    val m = createOverrideImplementVariable(value, substitutor, addOverride, isVal, comment)(value.getManager)

    TypeAnnotationUtil.removeTypeAnnotationIfNeeded(m, typeAnnotationsPolicy)
    m
  }

  def toAddOverrideToImplemented: Boolean =
    if (ApplicationManager.getApplication.isUnitTestMode) false
    else ScalaApplicationSettings.getInstance.ADD_OVERRIDE_TO_IMPLEMENTED

  def typeAnnotationsPolicy: ScalaApplicationSettings.ReturnTypeLevel =
    ScalaApplicationSettings.getInstance().SPECIFY_RETURN_TYPE_EXPLICITLY
}
