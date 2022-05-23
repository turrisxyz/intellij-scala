package scala.meta.collections

/**
  * @author mucianm 
  * @since 03.06.16.
  */

trait OverloadHack1
object OverloadHack1 { implicit object Instance extends OverloadHack1 }

trait OverloadHack2
object OverloadHack2 { implicit object Instance extends OverloadHack2 }
