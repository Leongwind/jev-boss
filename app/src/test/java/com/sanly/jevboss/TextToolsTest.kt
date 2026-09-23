package com.sanly.jevboss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    @Test fun detectsJobAndChat() {
        assertEquals(PageKind.JOB, TextTools.pageKind("职位描述\n负责 Android 开发\n任职要求 Kotlin"))
        assertEquals(PageKind.CHAT, TextTools.pageKind("聊天\n您好，方便聊一下吗\n发送"))
        assertEquals(PageKind.UNKNOWN, TextTools.pageKind("首页\n推荐\n我的"))
    }

    @Test fun removesContactDetailsBeforeApiRequest() {
        val result = TextTools.redact("电话 13812345678，邮箱 Test.Name@example.com，熟悉 Kotlin")
        assertFalse(result.contains("13812345678"))
        assertFalse(result.contains("Test.Name@example.com"))
        assertTrue(result.contains("Kotlin"))
    }

    @Test fun evidenceOnlyListsSharedSkills() {
        val result = TextTools.evidence("Android Kotlin Compose", "5 年 Android Java 经验，Kotlin 项目")
        assertTrue(result.contains("Android"))
        assertTrue(result.contains("Kotlin"))
        assertTrue(result.contains("JD 原文"))
        assertTrue(result.contains("简历原文"))
    }

    @Test fun distinguishesJobHeadersButNotScrolledBody() {
        assertEquals("Android 开发工程师|20-30K·13薪",
            TextTools.jobAnchor("Android 开发工程师\n20-30K·13薪\n职位详情\nKotlin"))
        assertEquals(null, TextTools.jobAnchor("岗位职责\n负责 Kotlin 开发"))
    }

    @Test fun proposesLastVisibleChatMessageForReview() {
        assertEquals("好的，明天见", TextTools.latestChatLine("17:35\n面试时间可以吗\n17:48\n好的，明天见\n回复消息"))
    }

    @Test fun distinguishesDifferentHrChatHeaders() {
        assertEquals("王女士|示例公司 招聘主管", TextTools.chatAnchor("王女士\n示例公司 招聘主管\n换电话\n你好"))
        assertTrue(TextTools.chatAnchor("王女士\n示例公司 招聘主管") !=
            TextTools.chatAnchor("李先生\n另一家公司 招聘主管"))
    }
}
