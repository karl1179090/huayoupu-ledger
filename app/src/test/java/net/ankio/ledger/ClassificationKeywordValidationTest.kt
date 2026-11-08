package net.ankio.ledger

import net.ankio.ledger.core.*
import net.ankio.ledger.data.ClassificationCodec
import org.junit.Test

class ClassificationKeywordValidationTest {
    @Test fun merchantKeywordCanContainFormattingWhitespace() {
        ClassificationCodec.validateRule(ClassificationRule("","合成格式关键词",true,Direction.EXPENSE,"cat:expense:dining",0,
            listOf(ClassificationCondition(value="Ａ\t＋\nＢ"))))
    }
}
