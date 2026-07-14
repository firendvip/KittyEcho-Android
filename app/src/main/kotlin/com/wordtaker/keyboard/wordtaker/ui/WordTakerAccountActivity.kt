package com.wordtaker.keyboard.wordtaker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wordtaker.keyboard.wordtaker.account.AccountRepository
import com.wordtaker.keyboard.wordtaker.account.AccountResult
import com.wordtaker.keyboard.wordtaker.backend.BackendConfig
import com.wordtaker.keyboard.wordtaker.backend.PlanInfo
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 账户/额度页（阶段3）：登录状态、云端剩余额度、邮箱验证码登录、微信登录（系统浏览器
 * 授权 → kittyecho://auth deep link 回跳）、兑换码、套餐入口（仅到「跳转支付页」）。
 *
 * 未登录时后端匿名走设备额度（x-device-id），额度卡片对匿名同样可见。
 */
class WordTakerAccountActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        setContent {
            WordTakerTheme {
                AccountScreen(
                    repository = AppGraph.accountRepository,
                    onBack = { finish() },
                )
            }
        }
        handleWechatDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleWechatDeepLink(intent)
    }

    /** kittyecho://auth?code=... （微信授权回跳）→ 后端换 JWT。 */
    private fun handleWechatDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != BackendConfig.WECHAT_DEEPLINK_SCHEME) return
        if (data.host != BackendConfig.WECHAT_DEEPLINK_HOST) return
        val code = data.getQueryParameter("code")?.trim().orEmpty()
        if (code.isEmpty()) {
            Toast.makeText(this, "微信授权失败：未获得 code", Toast.LENGTH_SHORT).show()
            return
        }
        val repo = AppGraph.accountRepository
        // lifecycleScope 触发登录，结果 Toast 提示（页面状态经 repository state 自动刷新）。
        lifecycleScope.launch {
            when (val result = repo.loginWithWechatCode(code)) {
                is AccountResult.Ok -> {
                    Toast.makeText(this@WordTakerAccountActivity, "微信登录成功", Toast.LENGTH_SHORT).show()
                    repo.refreshQuota()
                }
                is AccountResult.Err ->
                    Toast.makeText(this@WordTakerAccountActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * 账户/额度页内容。item6: 抽为可共用的 Composable —— App 主设置页 (MinimalSettingsScreen)
 * 以同窗口子页方式内嵌它；本 Activity 仅保留给微信 deep link 回跳兼容。
 */
@Composable
internal fun AccountScreen(
    repository: AccountRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsStateWithLifecycle()
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun show(message: String) {
        statusMessage = message
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    // 进入页面即拉一次额度（匿名可用）；登录态变化时再刷。
    LaunchedEffect(state.loggedIn) {
        when (val r = repository.refreshQuota()) {
            is AccountResult.Ok -> statusMessage = null
            is AccountResult.Err -> statusMessage = r.message
        }
        if (state.loggedIn) repository.refreshAccount()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            TopBar(title = "账户", onBack = onBack)

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(8.dp))

                QuotaSection(state.quota, statusMessage) {
                    scope.launch {
                        when (val r = repository.refreshQuota()) {
                            is AccountResult.Ok -> statusMessage = null
                            is AccountResult.Err -> statusMessage = r.message
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                if (!state.loggedIn) {
                    LoginSection(repository, onMessage = ::show)
                } else {
                    LoggedInSection(repository, onMessage = ::show)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// —— 额度 ——

@Composable
private fun QuotaSection(
    quota: com.wordtaker.keyboard.wordtaker.backend.QuotaInfo?,
    statusMessage: String?,
    onRefresh: () -> Unit,
) {
    SectionLabel("云端额度")
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = quota?.cloudRemaining?.let { "$it 字" } ?: "— —",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val detail = quota?.let { q ->
                    listOfNotNull(
                        q.deviceRemaining?.let { "本机赠送 $it" },
                        q.accountRemaining?.let { "账号余额 $it" },
                    ).joinToString(" · ").ifBlank { null }
                }
                Text(
                    text = detail ?: (statusMessage ?: "剩余可用云端润色字数"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
    }
}

// —— 未登录：邮箱验证码 + 微信 ——

@Composable
private fun LoginSection(
    repository: AccountRepository,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
        }
    }

    val emailValid = EMAIL_REGEX.matches(email.trim())
    val codeValid = CODE_REGEX.matches(code.trim())

    SectionLabel("登录")
    Card {
        Column {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("邮箱") },
                singleLine = true,
                isError = email.isNotBlank() && !emailValid,
                supportingText = {
                    if (email.isNotBlank() && !emailValid) Text("邮箱格式不正确")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(8) },
                    label = { Text("验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = emailValid && !sending && countdown == 0,
                    onClick = {
                        sending = true
                        scope.launch {
                            when (val r = repository.sendEmailCode(email.trim())) {
                                is AccountResult.Ok -> {
                                    onMessage("验证码已发送")
                                    countdown = RESEND_SECONDS
                                }
                                is AccountResult.Err -> onMessage(r.message)
                            }
                            sending = false
                        }
                    },
                ) {
                    Text(if (countdown > 0) "${countdown}s" else "发送验证码")
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = emailValid && codeValid && !loggingIn,
                onClick = {
                    loggingIn = true
                    scope.launch {
                        when (val r = repository.loginWithEmail(email.trim(), code.trim())) {
                            is AccountResult.Ok -> onMessage("登录成功")
                            is AccountResult.Err -> onMessage(r.message)
                        }
                        loggingIn = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loggingIn) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("登录")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        when (val r = repository.wechatAuthUrl()) {
                            is AccountResult.Ok -> {
                                val url = r.value.url
                                if (url.isBlank()) {
                                    onMessage("微信登录暂不可用")
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                                        )
                                    }.onFailure { onMessage("无法打开浏览器") }
                                }
                            }
                            is AccountResult.Err -> onMessage(r.message)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("微信登录")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "未登录也可使用：本机自带免费云端额度",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// —— 已登录：账号 / 兑换码 / 套餐 / 退出 ——

@Composable
private fun LoggedInSection(
    repository: AccountRepository,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val state by repository.state.collectAsStateWithLifecycle()
    var redeemCode by rememberSaveable { mutableStateOf("") }
    var redeeming by remember { mutableStateOf(false) }

    SectionLabel("账号")
    Card {
        Column {
            val account = state.account
            Text(
                text = account?.nickname
                    ?: account?.email
                    ?: account?.phone
                    ?: "已登录",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            account?.inviteCode?.let { invite ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "邀请码：$invite",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(invite))
                        onMessage("邀请码已复制")
                    }) { Text("复制") }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                repository.logout()
                onMessage("已退出登录")
            }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
        }
    }

    Spacer(Modifier.height(20.dp))

    SectionLabel("兑换码")
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = redeemCode,
                onValueChange = { redeemCode = it.trim() },
                label = { Text("输入兑换码") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = redeemCode.isNotBlank() && !redeeming,
                onClick = {
                    redeeming = true
                    scope.launch {
                        when (val r = repository.redeem(redeemCode)) {
                            is AccountResult.Ok ->
                                onMessage("兑换成功" + (r.value?.let { "，+${it} 字" } ?: ""))
                            is AccountResult.Err -> onMessage(r.message)
                        }
                        redeeming = false
                        redeemCode = ""
                    }
                },
            ) { Text("兑换") }
        }
    }

    Spacer(Modifier.height(20.dp))

    PlansSection(repository, onMessage)
}

@Composable
private fun PlansSection(
    repository: AccountRepository,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf<List<PlanInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val r = repository.plans()) {
            is AccountResult.Ok -> plans = r.value
            is AccountResult.Err -> loadError = r.message
        }
    }

    SectionLabel("字数包")
    Card {
        when {
            plans == null && loadError == null -> Text(
                "套餐加载中…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            loadError != null -> Text(
                loadError.orEmpty(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plans.isNullOrEmpty() -> Text(
                "暂无可购套餐",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column {
                plans.orEmpty().forEachIndexed { index, plan ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                plan.name.ifBlank { plan.code },
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                buildString {
                                    append("¥${plan.priceCents / 100}")
                                    plan.charAmount?.let { append(" · ${it} 字") }
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                when (val r = repository.createOrder(plan.code, PAY_CHANNEL)) {
                                    is AccountResult.Ok -> {
                                        val payUrl = r.value.payload?.optString("url")
                                            ?.takeIf { it.startsWith("http") }
                                        if (payUrl != null) {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(payUrl)),
                                                )
                                            }.onFailure { onMessage("无法打开支付页") }
                                        } else {
                                            onMessage("订单已创建，支付渠道即将开放")
                                        }
                                    }
                                    is AccountResult.Err -> onMessage(r.message)
                                }
                            }
                        }) { Text("去支付") }
                    }
                }
            }
        }
    }
}

// —— 局部小组件 ——

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        content()
    }
}

private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private val CODE_REGEX = Regex("^\\d{4,8}$")
private const val RESEND_SECONDS = 60
private const val PAY_CHANNEL = "alipay"
