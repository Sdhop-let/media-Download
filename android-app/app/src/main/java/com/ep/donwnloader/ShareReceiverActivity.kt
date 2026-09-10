package com.ep.donwnloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 无感分享中转：X / Instagram / Bluesky 点「分享」选本应用时落地到这里。
 *  全透明主题 + 立即 finish（无转场动画），用户视觉上「没有跳转」；
 *  链接捕获与下载全部在后台完成，结果经 NotifyHub（底部胶囊 / 系统通知）反馈。 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        handleIntent(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)   // 去掉进出场动画，视觉完全无感
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val texts = if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getStringArrayListExtra(Intent.EXTRA_TEXT).orEmpty()
        } else listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() })
        if (texts.none { it.isNotBlank() }) return

        // 可识别链接 → 后台静默捕获下载；不可识别 → 打开主界面把文本填进输入框由用户处理
        val hasLink = texts.any { ShareIn.extractLinks(it).isNotEmpty() }
        if (hasLink) {
            Store.scope.launch(Dispatchers.IO) {
                Store.tasks.handleSharePayload(texts)
            }
        } else {
            Store.pendingShare = texts.firstOrNull { it.isNotBlank() }
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
