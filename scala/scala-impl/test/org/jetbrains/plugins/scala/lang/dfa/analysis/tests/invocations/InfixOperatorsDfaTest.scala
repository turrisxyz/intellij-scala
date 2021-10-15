package org.jetbrains.plugins.scala.lang.dfa.analysis.tests.invocations

import org.jetbrains.plugins.scala.lang.dfa.Messages._
import org.jetbrains.plugins.scala.lang.dfa.analysis.ScalaDfaTestBase

class InfixOperatorsDfaTest extends ScalaDfaTestBase {

  def testRelationalOperators(): Unit = test(codeFromMethodBody(returnType = "Boolean") {
    """
      |3 > 2
      |22 <= 3 * 7
      |""".stripMargin
  })(
    "3 > 2" -> ConditionAlwaysTrue,
    "22 <= 3 * 7" -> ConditionAlwaysFalse
  )

  def testLogicalOperatorsAndBooleanLiteralSuppression(): Unit =
    test(codeFromMethodBody(returnType = "Boolean") {
      """
        |2 <= 2 && 2 == 2 && true && 3 == 5 - 2
        |3 != 3 || false || 2 < 10 - 6 || 3 == 4
        |""".stripMargin
    })(
      "2 <= 2" -> ConditionAlwaysTrue,
      "2 == 2" -> ConditionAlwaysTrue,
      "3 == 5 - 2" -> ConditionAlwaysTrue,
      "2 <= 2 && 2 == 2 && true && 3 == 5 - 2" -> ConditionAlwaysTrue,
      "3 != 3" -> ConditionAlwaysFalse,
      "2 < 10 - 6" -> ConditionAlwaysTrue,
      "3 != 3 || false || 2 < 10 - 6 || 3 == 4" -> ConditionAlwaysTrue
    )
}