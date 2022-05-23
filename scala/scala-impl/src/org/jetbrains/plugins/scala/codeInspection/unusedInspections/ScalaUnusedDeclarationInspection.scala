package org.jetbrains.plugins.scala
package codeInspection
package unusedInspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.psi._
import com.intellij.psi.search._
import com.intellij.psi.search.searches.ReferencesSearch
import org.jdom.Element
import org.jetbrains.annotations.Nls
import org.jetbrains.plugins.scala.annotator.usageTracker.ScalaRefCountHolder
import org.jetbrains.plugins.scala.codeInspection.ui.InspectionOptions
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.lexer.ScalaModifier
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.{inNameContext, isOnlyVisibleInLocalFile, superValsSignatures}
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSelfTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScPatternList, ScStableCodeReference}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCase, ScFunction, ScFunctionDeclaration, ScFunctionDefinition, ScPatternDefinition, ScVariable}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScModifierListOwner, ScNamedElement}
import org.jetbrains.plugins.scala.lang.psi.api.{ScalaFile, ScalaPsiElement}
import org.jetbrains.plugins.scala.lang.psi.impl.search.ScalaOverridingMemberSearcher
import org.jetbrains.plugins.scala.project.{ModuleExt, ScalaLanguageLevel}
import org.jetbrains.plugins.scala.util.SAMUtil.PsiClassToSAMExt
import org.jetbrains.plugins.scala.util.{ScalaMainMethodUtil, ScalaUsageNamesUtil}

import javax.swing.JComponent
import scala.jdk.CollectionConverters._

class ScalaUnusedDeclarationInspection extends HighlightingPassInspection {

  private val reportPublicDeclarations =
    InspectionOptions(
      "reportPublicDeclarations",
      ScalaInspectionBundle.message("name.unused.declaration.report.public.declarations")
    )

  def setReportPublicDeclarationsEnabled(enabled: Boolean): Unit =
    reportPublicDeclarations.setChecked(enabled)

  override def writeSettings(node: Element): Unit = {
    reportPublicDeclarations.writeSettings(node)
    super.writeSettings(node)
  }

  override def readSettings(node: Element): Unit = {
    super.readSettings(node)
    reportPublicDeclarations.readSettings(node)
  }

  override def createOptionsPanel: JComponent =
    reportPublicDeclarations.checkBox

  import ScalaUnusedDeclarationInspection._

  override def isEnabledByDefault: Boolean = true

  override def getDisplayName: String = ScalaInspectionBundle.message("display.name.unused.declaration")

  private def isElementUsed(element: ScNamedElement, isOnTheFly: Boolean): Boolean = {

    val elementIsOnlyVisibleInLocalFile = isOnlyVisibleInLocalFile(element)

    if (elementIsOnlyVisibleInLocalFile && isOnTheFly) {
      localSearch(element)
    } else if (elementIsOnlyVisibleInLocalFile && !isOnTheFly) {
      referencesSearch(element)
    } else if (referencesSearch(element)) {
      true
    } else if (!reportPublicDeclarations.isEnabled(element)) {
      true
    } else if (checkIfEnumUsedOutsideScala(element)) {
      true
    } else if (ScalaPsiUtil.isImplicit(element)) {
      true
    } else {
      element match {
        case f: ScFunctionDefinition if !f.name.endsWith("_=") =>
          ReferencesSearch.search(f).findFirst != null
        case _ =>
          textSearch(element)
      }
    }
  }

  // this case is for elements accessible only in a local scope
  private def localSearch(element: ScNamedElement): Boolean = {
    //we can trust RefCounter because references are counted during highlighting
    val refCounter = ScalaRefCountHolder(element)

    var used = false
    val success = refCounter.runIfUnusedReferencesInfoIsAlreadyRetrievedOrSkip { () =>
      used = refCounter.isValueReadUsed(element) || refCounter.isValueWriteUsed(element)
    }

    !success || used // Return true also if runIfUnused... was a failure
  }

  // this case is for elements accessible not only in a local scope, but within the same file
  private def referencesSearch(element: ScNamedElement): Boolean = {
    val elementsForSearch = element match {
      // if the element is an enum case, we also look for usage in a few synthetic methods generated for the enum class
      case enumCase: ScEnumCase =>
        val syntheticMembers =
          ScalaPsiUtil.getCompanionModule(enumCase.enumParent)
            .toSeq.flatMap(_.membersWithSynthetic)
            .collect {
              case n: ScNamedElement if ScalaUsageNamesUtil.enumSyntheticMethodNames.contains(n.name) => n
            }
        enumCase.getSyntheticCounterpart +: syntheticMembers
      case e: ScNamedElement => Seq(e)
    }

    val scope = new LocalSearchScope(element.getContainingFile)
    elementsForSearch.exists(ReferencesSearch.search(_, scope).findFirst() != null)
  }

  // if the element is an enum case, and the enum class is accessed from outside Scala, we assume the enum case is used
  private def checkIfEnumUsedOutsideScala(element: ScNamedElement): Boolean = {
    val scEnum = element match {
      case el: ScEnumCase => Some(el.enumParent)
      case el: ScEnum => Some(el)
      case _ => None
    }

    scEnum.exists { e =>
      var used = false

      val processor = new TextOccurenceProcessor {
        override def execute(e2: PsiElement, offsetInElement: Int): Boolean =
          inReadAction {
            if (e2.getContainingFile.isScala3File || e2.getContainingFile.isScala2File) {
              true
            } else {
              used = true
              false
            }
          }
      }

      PsiSearchHelper
        .getInstance(element.getProject)
        .processElementsWithWord(
          processor,
          element.getUseScope,
          e.getName, // for usage of enum methods through `EnumName.methodName(...)`
          (UsageSearchContext.IN_CODE | UsageSearchContext.IN_FOREIGN_LANGUAGES).toShort,
          true
        )

      if (!used) {
        PsiSearchHelper
          .getInstance(element.getProject)
          .processElementsWithWord(
            processor,
            element.getUseScope,
            s"${e.getName}$$.MODULE$$", // for usage of enum methods through `EnumName$.MODULE$.methodName(...)`
            (UsageSearchContext.IN_CODE | UsageSearchContext.IN_FOREIGN_LANGUAGES).toShort,
            true
          )
      }
      used
    }
  }

  // if the element is accessible from other files, we check that with a text search
  private def textSearch(element: ScNamedElement): Boolean = {
    val helper = PsiSearchHelper.getInstance(element.getProject)
    var used = false
    val processor = new TextOccurenceProcessor {
      override def execute(e2: PsiElement, offsetInElement: Int): Boolean = {
        inReadAction {
          if (element.getContainingFile == e2.getContainingFile) {
            true
          } else {
            used = (e2, Option(e2.getParent)) match {
              case (_: ScStableCodeReference, _) => true
              case (_: ScalaFile, _) => false
              case (_: ScTypeDefinition, _) => false
              case (_: ScExtendsBlock, _) => false
              case (_: ScTemplateBody, _) => false
              case (_: ScPatternDefinition, _) => false
              case (_: ScPatternList, _) => false
              case (_: ScReferencePattern, _) => false
              case (_: ScStringLiteral, _) => false
              case (_, Some(_: ScStringLiteral)) => false
              case (_, Some(_: ScReferencePattern)) => false
              case (_, Some(_: ScTypeDefinition)) => false
              case _ => true
            }
            !used
          }
        }
      }
    }

    ScalaUsageNamesUtil.getStringsToSearch(element).asScala.foreach { name =>
      if (!used) {
        helper.processElementsWithWord(
          processor,
          element.getUseScope,
          name,
          (UsageSearchContext.IN_CODE | UsageSearchContext.IN_FOREIGN_LANGUAGES).toShort,
          true
        )
      }
    }
    used
  }

  override def invoke(element: PsiElement, isOnTheFly: Boolean): Seq[ProblemInfo] =
    if (!shouldProcessElement(element)) {
      Seq.empty
    } else {
      val elements: Seq[PsiElement] = element match {
        case named: ScNamedElement => Seq(named)
        case _ => Seq.empty
      }
      elements.flatMap {
        case inNameContext(holder: PsiAnnotationOwner) if hasUnusedAnnotation(holder) =>
          Seq.empty
        case named: ScNamedElement if !isElementUsed(named, isOnTheFly) =>

          val dontReportPublicDeclarationsQuickFix =
            if (isOnlyVisibleInLocalFile(named)) None else Some(new DontReportPublicDeclarationsQuickFix(named))

          val addScalaAnnotationUnusedQuickFix = if (named.scalaLanguageLevelOrDefault < ScalaLanguageLevel.Scala_2_13)
            None else Some(new AddScalaAnnotationUnusedQuickFix(named))

          Seq(
            ProblemInfo(
              named.nameId,
              ScalaUnusedDeclarationInspection.annotationDescription,
              ProblemHighlightType.LIKE_UNUSED_SYMBOL,
              DeleteUnusedElementFix.quickfixesFor(named) ++
                dontReportPublicDeclarationsQuickFix ++
                addScalaAnnotationUnusedQuickFix
            )
          )
        case _ =>
          Seq.empty
      }
    }

  override def shouldProcessElement(elem: PsiElement): Boolean = elem match {
    case e if !isOnlyVisibleInLocalFile(e) && TestSourcesFilter.isTestSources(e.getContainingFile.getVirtualFile, e.getProject) => false
    case _: ScSelfTypeElement => false
    case e: ScalaPsiElement if e.module.exists(_.isBuildModule) => false
    case e: PsiElement if UnusedDeclarationInspectionBase.isDeclaredAsEntryPoint(e) && !ScalaPsiUtil.isImplicit(e) => false
    case obj: ScObject if ScalaMainMethodUtil.hasScala2MainMethod(obj) => false
    case t: ScTypeDefinition if t.isSAMable => false
    case n: ScNamedElement if n.nameId == null || n.name == "_" || isOverridingOrOverridden(n) => false
    case n: ScNamedElement =>
      n match {
        case p: ScModifierListOwner if hasOverrideModifier(p) => false
        case fd: ScFunctionDefinition if ScalaMainMethodUtil.isMainMethod(fd) => false
        case f: ScFunction if f.isSpecial || isOverridingFunction(f) => false
        case p: ScClassParameter if p.isCaseClassVal || p.isEnumVal || p.isEnumCaseVal => false
        case p: ScParameter =>
          p.parent.flatMap(_.parent.flatMap(_.parent)) match {
            case Some(f: ScFunctionDeclaration) if ScalaOverridingMemberSearcher.search(f).nonEmpty => false
            case Some(f: ScFunctionDefinition) if ScalaOverridingMemberSearcher.search(f).nonEmpty ||
              isOverridingFunction(f) || ScalaMainMethodUtil.isMainMethod(f) => false
            case _ => true
          }
        case _ => true
      }
    case _ => false
  }
}

object ScalaUnusedDeclarationInspection {
  @Nls
  val annotationDescription: String = ScalaInspectionBundle.message("declaration.is.never.used")

  private def hasOverrideModifier(member: ScModifierListOwner): Boolean =
    member.hasModifierPropertyScala(ScalaModifier.OVERRIDE)

  private def isOverridingOrOverridden(element: PsiNamedElement): Boolean =
    superValsSignatures(element, withSelfType = true).nonEmpty || isOverridden(element)

  private def isOverridingFunction(func: ScFunction): Boolean =
    hasOverrideModifier(func) || func.superSignatures.nonEmpty || isOverridden(func)

  private def isOverridden(member: PsiNamedElement): Boolean =
    ScalaOverridingMemberSearcher.search(member, deep = false, withSelfType = true).nonEmpty

  private def hasUnusedAnnotation(holder: PsiAnnotationOwner): Boolean =
    holder.hasAnnotation("scala.annotation.unused") ||
      // not entirely correct, but if we find @nowarn here in this situation
      // we can assume that it is directed at the unusedness of the symbol
      holder.hasAnnotation("scala.annotation.nowarn")
}
