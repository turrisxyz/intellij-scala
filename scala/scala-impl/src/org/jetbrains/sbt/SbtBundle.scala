package org.jetbrains.sbt

import com.intellij.DynamicBundle
import org.jetbrains.annotations.{Nls, PropertyKey}

import scala.annotation.varargs

object SbtBundle {
    private final val BUNDLE = "messages.ScalaSbtBundle"
    private object INSTANCE extends DynamicBundle(BUNDLE)

    //noinspection ReferencePassedToNls
    @Nls
    @varargs
    def message(@PropertyKey(resourceBundle = BUNDLE) key: String, params: Any*): String =
        INSTANCE.getMessage(key, params.map(_.toString): _*)
}
