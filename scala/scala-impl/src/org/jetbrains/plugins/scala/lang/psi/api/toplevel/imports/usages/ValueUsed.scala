package org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.usages

import com.intellij.psi.{PsiElement, PsiNamedElement, SmartPsiElementPointer}
import org.jetbrains.plugins.scala.extensions._

/**
 * @author Alexander Podkhalyuzin
 */
sealed trait ValueUsed {
  val pointer: SmartPsiElementPointer[PsiNamedElement]
  val reference: PsiElement

  protected val name: String

  def isValid: Boolean = pointer match {
    case ValidSmartPointer(_) if reference != null && reference.isValid => true
    case _ => false
  }

  override final def toString: String = {
    val maybeName = Option(pointer.getElement).map(_.name)
    s"$name(${maybeName.getOrElse("No element")})"
  }
}

object ValueUsed {

  def unapply(v: ValueUsed): Option[PsiNamedElement] = {
    Option(v.pointer.getElement)
  }
}

case class ReadValueUsed(
  override val pointer: SmartPsiElementPointer[PsiNamedElement],
  override val reference: PsiElement
) extends ValueUsed {
  override protected val name: String = "ValueRead"
}

object ReadValueUsed {
  def apply(e: PsiNamedElement, r: PsiElement): ReadValueUsed = ReadValueUsed(e.createSmartPointer, r)
}

case class WriteValueUsed(
  override val pointer: SmartPsiElementPointer[PsiNamedElement],
  override val reference: PsiElement
) extends ValueUsed {
  override protected val name: String = "ValueWrite"
}

object WriteValueUsed {
  def apply(e: PsiNamedElement, r: PsiElement): WriteValueUsed = WriteValueUsed(e.createSmartPointer, r)
}