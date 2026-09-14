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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wordtaker.keyboard.wordtaker.account.AccountProfileState
import com.wordtaker.keyboard.wordtaker.account.AccountRepository
import com.wordtaker.keyboard.wordtaker.account.AccountResult
import com.wordtaker.keyboard.wordtaker.account.PassportLoginController
import com.wordtaker.keyboard.wordtaker.account.PassportLoginStatus
import com.wordtaker.keyboard.wordtaker.account.PassportStartResult
import com.wordtaker.keyboard.wordtaker.backend.PlanInfo
import com.wordtaker.keyboard.wordtaker.di.AppGraph
import kotlinx.coroutines.launch

/**
 * 账户/额度页：登录状态、云端剩余额度、望三通行证（系统浏览器 OIDC + S256 PKCE）、
 * 兑换码、套餐入口（仅到「跳转支付页」）。
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
                    passportLogin = AppGraph.passportLogin,
                    onBack = { finish() },
                )
            }
        }
        handlePassportCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePassportCallback(intent)
    }

    /** Exact kittyecho://auth callback is validated and consumed by the PKCE state machine. */
    private fun handlePassportCallback(intent: Intent?) {
        val callback = intent?.data?.toString() ?: return
        intent.data = null
        lifecycleScope.launch {
            when (val result = AppGraph.passportLogin.handleCallback(callback)) {
                is AccountResult.Ok -> {
                    Toast.makeText(this@WordTakerAccountActivity, "登录成功", Toast.LENGTH_SHORT).show()
                    AppGraph.accountRepository.refreshQuota()
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
    passportLogin: PassportLoginController,
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

    // 进入页面即拉一次额度（匿名可用）；账号资料由 repository 自主 hydrate。
    LaunchedEffect(state.loggedIn) {
        when (val r = repository.refreshQuota()) {
            is AccountResult.Ok -> statusMessage = null
            is AccountResult.Err -> statusMessage = r.message
        }
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
                    LoginSection(passportLogin, onMessage = ::show)
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

// —— 未登录：中央托管的手机号验证码 + 微信 ——

@Composable
private fun LoginSection(
    passportLogin: PassportLoginController,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val status by passportLogin.status.collectAsStateWithLifecycle()
    val busy = status is PassportLoginStatus.Exchanging

    SectionLabel("登录")
    Card {
        Column {
            Text(
                text = "望三通行证",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "将在系统浏览器中打开，仅提供手机号验证码和微信登录；微信可使用快捷确认或扫码切换。",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = passportLogin.isAvailable && !busy,
                onClick = {
                    when (val start = passportLogin.begin()) {
                        is PassportStartResult.Ready -> {
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(start.authorizationUrl))
                                .addCategory(Intent.CATEGORY_BROWSABLE)
                            val opened = runCatching {
                                context.startActivity(browserIntent)
                            }.isSuccess
                            if (!opened) {
                                passportLogin.cancel()
                                onMessage("未找到可用的系统浏览器")
                            }
                        }
                        is PassportStartResult.Unavailable -> onMessage(start.message)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("望三通行证登录")
                }
            }
            val statusText = when (val current = status) {
                PassportLoginStatus.Idle -> if (passportLogin.isAvailable) {
                    "未登录也可使用：本机自带免费云端额度"
                } else {
                    "统一登录尚未启用或配置不完整"
                }
                PassportLoginStatus.AwaitingBrowser -> "正在等待浏览器完成登录"
                PassportLoginStatus.Exchanging -> "正在安全完成登录…"
                is PassportLoginStatus.Error -> current.message
            }
            Text(statusText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status is PassportLoginStatus.AwaitingBrowser) {
                TextButton(onClick = passportLogin::cancel) { Text("取消登录") }
            }
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
            when (val profile = state.profile) {
                AccountProfileState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        Modifier.height(18.dp).width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("账号资料加载中…")
                }
                is AccountProfileState.Unavailable -> Column {
                    Text(
                        text = profile.message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        scope.launch {
                            when (val result = repository.refreshAccount()) {
                                is AccountResult.Ok -> onMessage("账号资料已更新")
                                is AccountResult.Err -> onMessage(result.message)
                            }
                        }
                    }) { Text("重试") }
                }
                is AccountProfileState.Available -> {
                    val account = profile.account
                    Text(
                        text = account.nickname
                            ?: account.phone
                            ?: "已登录",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    account.inviteCode?.let { invite ->
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
                }
                AccountProfileState.SignedOut -> Text("登录状态已失效")
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

private const val PAY_CHANNEL = "alipay"
