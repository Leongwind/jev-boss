package com.sanly.jevboss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run explicitly with -e api_probe true; never uses a real resume or chat. */
@RunWith(AndroidJUnit4::class)
class ContextReplyApiTest {
    @Test fun ranksResumeAndGeneratesSupportedGreeting() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("api_probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = DirectApi(SecretStore(context))
        val job = "Android 开发工程师。负责 Kotlin Android App 开发，要求 Jetpack Compose、协程和性能优化经验。"
        val androidResume = Resume("android", "synthetic-android.pdf",
            "3年 Android 应用开发经历，使用 Kotlin、Jetpack Compose 和协程开发移动 App，并负责启动性能优化。")
        val unrelatedResume = Resume("other", "synthetic-other.pdf",
            "3年餐饮门店运营经历，负责排班、采购、客户服务和库存管理。")
        val ranked = api.match(job, listOf(androidResume, unrelatedResume))
        assertEquals("android", ranked.first().resumeId)
        val greetings = api.greeting(job, androidResume)
        assertTrue(greetings.isNotEmpty())
    }

    @Test fun repliesToLatestHrMessageRatherThanGreetingAgain() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("api_probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = DirectApi(SecretStore(context))
        val resume = Resume("probe", "synthetic.pdf", "3年 Android 开发经历，使用 Kotlin 和 Jetpack Compose 开发移动应用。")
        val job = "Android 开发工程师。负责 Kotlin Android App 开发，要求熟悉 Jetpack Compose 和协程。"
        val chat = "HR：周四下午三点面试可以吗？\n求职者：周四下午三点可以。\nHR：那我稍后发会议链接。"
        val latest = "那我稍后发会议链接。"
        val intent = api.hrIntent(latest, chat)
        val replies = api.reply(job, resume, chat, latest, intent)
        assertTrue(replies.isNotEmpty())
        assertFalse(replies.any { it.startsWith("您好，我是") || it.startsWith("你好，我是") })
        assertTrue(replies.any { it.contains("链接") || it.contains("谢谢") || it.contains("收到") })
    }

    @Test fun repliesFromChatWithoutJobOrResume() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("api_probe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = DirectApi(SecretStore(context))
        val chat = "HR：你好，请问方便聊一下岗位吗？\n求职者：可以，您请说。\nHR：你明天下午方便电话沟通吗？"
        val latest = "你明天下午方便电话沟通吗？"
        val intent = api.hrIntent(latest, chat)
        val replies = api.reply("", null, chat, latest, intent)
        assertTrue(replies.isNotEmpty())
        assertFalse(replies.any { it.contains("3年") || it.contains("Kotlin") })
    }
}
