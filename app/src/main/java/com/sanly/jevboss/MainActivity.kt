package com.sanly.jevboss

import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var permissionRefresh by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ResumeStore(this)
        val secrets = SecretStore(this)
        setContent {
            MaterialTheme {
                var resumes by remember { mutableStateOf(store.list()) }
                var typesafeKey by remember { mutableStateOf("") }
                var deepseekKey by remember { mutableStateOf("") }
                var notice by remember { mutableStateOf(store.errorMessage().orEmpty()) }
                var probingJev by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    scope.launch {
                        val messages = uris.map { uri ->
                            store.import(uri).fold({ "已导入 ${it.name}" }, { it.message ?: "导入失败" })
                        }
                        resumes = store.list()
                        notice = messages.joinToString("；")
                    }
                }
                val refresh = permissionRefresh
                val overlayGranted = remember(refresh) { Settings.canDrawOverlays(this) }
                val accessibilityGranted = remember(refresh) { isAccessibilityEnabled() }

                Scaffold(topBar = { Surface(shadowElevation = 2.dp) {
                    Text("Jev 求职助手", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth().padding(20.dp))
                } }) { padding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(vertical = 18.dp)
                    ) {
                        item {
                            Text("使用步骤", style = MaterialTheme.typography.titleMedium)
                            Text("配置密钥和权限后，在 BOSS 点“荐”悬浮按钮。HR 回复可独立使用：核对上下文与消息，拆解 HR 的每个问题，补充真实答案，再生成候选。编辑后的回复会在复制前逐项复核。")
                        }
                        item {
                            HorizontalDivider()
                            Text("权限与数据", style = MaterialTheme.typography.titleMedium)
                            Text("读屏服务仅处理 BOSS 位于前台时当前可见的职位或聊天文字；本机 OCR 会补读界面。点击分析或生成后，所需的脱敏文字才会发送至 Jev/TypeSafe 和 DeepSeek。请核对待回复消息与内容；应用不会自动发送或投递。")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                Text(if (accessibilityGranted) "读屏权限已开启" else "开启读屏权限")
                            }
                            Spacer(Modifier.height(4.dp))
                            Button(onClick = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            }) { Text(if (overlayGranted) "悬浮窗权限已开启" else "开启悬浮窗权限") }
                        }
                        item {
                            HorizontalDivider()
                            Text("API 密钥", style = MaterialTheme.typography.titleMedium)
                            Text("密钥使用 Android Keystore 加密保存在本机，不会写入 APK。留空并保存不会覆盖已配置的密钥。")
                            OutlinedTextField(value = typesafeKey, onValueChange = { typesafeKey = it },
                                label = { Text("Jev / TypeSafe API Key") },
                                placeholder = { Text(if (secrets.get("typesafe").isNotBlank()) "已配置" else "请输入") },
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = deepseekKey, onValueChange = { deepseekKey = it },
                                label = { Text("DeepSeek API Key") },
                                placeholder = { Text(if (secrets.get("deepseek").isNotBlank()) "已配置" else "请输入") },
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                if (typesafeKey.isNotBlank()) secrets.put("typesafe", typesafeKey.trim())
                                if (deepseekKey.isNotBlank()) secrets.put("deepseek", deepseekKey.trim())
                                typesafeKey = ""
                                deepseekKey = ""
                                notice = "密钥已保存在本机"
                            }) { Text("保存密钥") }
                            Button(onClick = {
                                probingJev = true
                                notice = "正在测试 Jev 最小请求…"
                                scope.launch {
                                    notice = runCatching { DirectApi(secrets).probeJev() }
                                        .getOrElse { "Jev 连接诊断失败：${it.message ?: it.javaClass.simpleName}" }
                                    probingJev = false
                                }
                            }, enabled = !probingJev) { Text(if (probingJev) "正在诊断 Jev…" else "诊断 Jev 连接") }
                        }
                        item {
                            HorizontalDivider()
                            Text("简历 PDF（${resumes.size}）", style = MaterialTheme.typography.titleMedium)
                            Text("仅支持可复制文字的 PDF；扫描件会提示重新导入。提取文字使用 Android Keystore 加密后保存在应用私有目录，原文件不上传。")
                            Button(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("导入 PDF 简历") }
                        }
                        items(resumes, key = { it.id }) { resume ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(resume.name, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    store.remove(resume.id)
                                    resumes = store.list()
                                }) { Text("删除") }
                            }
                        }
                        item {
                            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = {
                                val launch = packageManager.getLaunchIntentForPackage("com.hpbr.bosszhipin")
                                if (launch == null) notice = "未找到 BOSS 直聘 App"
                                else startActivity(launch)
                            }, enabled = accessibilityGranted && overlayGranted) { Text("打开 BOSS 直聘") }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionRefresh++
    }

    private fun isAccessibilityEnabled(): Boolean {
        val target = ComponentName(this, BossAccessibilityService::class.java)
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == target }
    }
}
