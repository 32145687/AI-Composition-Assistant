package com.aicompose.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicompose.VM

class MainActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            vm.onCaptureResult(result.resultCode, result.data!!)
        }
    }

    private var vmRef: VM? = null
    private val vm: VM
        get() = vmRef ?: throw IllegalStateException("VM not initialized")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()){}.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val vmInstance = viewModel<VM>()
            vmRef = vmInstance
            LaunchedEffect(Unit) { vmInstance.setLauncher(captureLauncher) }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Screen(vmInstance)
            }
        }
    }
}

@Composable
fun Screen(vm: VM) {
    val a11y by vm.a11y.collectAsState()
    val cap by vm.capturing.collectAsState()
    val ai by vm.analyzing.collectAsState()
    val ovPerm by vm.overlayPermission.collectAsState()
    val ov by vm.overlayVisible.collectAsState()
    val auto by vm.autoExec.collectAsState()
    val res by vm.result.collectAsState()
    val status by vm.status.collectAsState()
    val action by vm.lastAction.collectAsState()

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                vm.checkA11y()
                vm.checkOverlayPerm()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D0D0D), Color(0xFF1A1A2E), Color(0xFF16213E))))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(36.dp))

            // 标题
            Text("📸 AI 构图助手", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("智能构图 · AR引导 · 自动调整", color = Color(0xFFB0BEC5), fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))

            // 状态指示器
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                Chip("无障碍", a11y); Chip("捕获", cap); Chip("AI", ai)
                Chip("悬浮窗", ovPerm); Chip("叠加层", ov)
            }

            Spacer(Modifier.height(4.dp))
            Text("使用步骤", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)

            Step(1, "启用无障碍服务", "模拟触控操作小米相机", Icons.Default.Accessibility, a11y, { vm.openA11ySettings() })
            Step(2, "开启屏幕捕获", "捕取实时画面供AI分析", Icons.Default.ScreenShare, cap, { vm.requestCapture() }, a11y)
            Step(3, "AI 实时分析", "构图评分+法则检测+建议", Icons.Default.AutoAwesome, ai, {
                if (ai) vm.stopAnalysis() else vm.requestCapture()
            }, cap)
            Step(4, "悬浮窗权限", "显示AR叠加层需要此权限", Icons.Default.Layers, ovPerm, {
                vm.toggleOverlay()  // 会自动跳转到权限设置
            }, true)
            Step(5, "显示 AR 叠加层", "构图网格+评分+引导线", Icons.Default.Visibility, ov, { vm.toggleOverlay() }, ovPerm && ai)

            Spacer(Modifier.height(4.dp))

            // 控制面板
            if (ai) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF1E1E1E)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("控制面板", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column {
                                Text("自动调整", color = Color.White, fontSize = 14.sp)
                                Text("AI自动缩放优化构图", color = Color(0xFF757575), fontSize = 11.sp)
                            }
                            Switch(auto, { vm.toggleAutoExec() }, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E676)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Column {
                                Text("引导线模式", color = Color.White, fontSize = 14.sp)
                                Text("点击切换: 三分/黄金/对角/中心", color = Color(0xFF757575), fontSize = 11.sp)
                            }
                            IconButton({ vm.cycleGuide() }) { Icon(Icons.Default.GridOn, null, tint = Color(0xFF00E5FF)) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(vm.getStats(), color = Color(0xFF757575), fontSize = 11.sp)
                    }
                }
            }

            // 分析结果卡片
            res?.let { r ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF1E1E1E)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("构图分析", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("${r.analysisTimeMs}ms", color = Color(0xFF757575), fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(10.dp))

                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${r.score.toInt()}", color = when {
                                r.score >= 80 -> Color(0xFF00E676); r.score >= 60 -> Color(0xFF00E5FF)
                                r.score >= 40 -> Color(0xFFFFAB00); else -> Color(0xFFFF1744)
                            }, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text("${r.gradeEmoji} ${r.grade}  /100", color = Color(0xFF757575), fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(10.dp))

                        r.rules.sortedByDescending { it.score }.forEach { rule ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${rule.icon} ${rule.name}", color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.width(72.dp))
                                LinearProgressIndicator(rule.score, modifier = Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)),
                                    color = if (rule.passed) Color(0xFF00E676) else Color(0xFFFFAB00), trackColor = Color(0xFF333333))
                                Spacer(Modifier.width(6.dp))
                                Text("${(rule.score*100).toInt()}%", color = Color(0xFF757575), fontSize = 11.sp, modifier = Modifier.width(32.dp))
                            }
                        }

                        if (r.suggestions.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("💡 建议", color = Color(0xFFFFAB00), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            r.suggestions.forEach { Text(it, color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                        }
                    }
                }
            }

            // 使用指南
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF1E1E1E)), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("使用指南", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "📱 打开小米相机 → 切换专业模式",
                        "🔍 AI 实时分析构图质量（8项法则）",
                        "📐 AR 叠加层显示三分线/黄金比例/引导线",
                        "🤖 自动缩放优化取景范围",
                        "⚡ 切换引导线模式：三分法→黄金比→对角线→中心",
                        "🎯 目标：帮你用手机拍出构图专业的照片"
                    ).forEach { Text(it, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            }

            // 当前执行动作
            action?.let {
                Spacer(Modifier.height(4.dp))
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Color(0xFF1B3A2F)), shape = RoundedCornerShape(10.dp)) {
                    Text("🤖 $it", color = Color(0xFF00E676), fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(Modifier.height(60.dp))
        }

        // 底部状态
        Surface(Modifier.align(Alignment.BottomCenter), color = Color(0xFF1E1E1E), shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(status, color = Color(0xFFB0BEC5), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun Chip(label: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Color(0xFF00E676) else Color(0xFF424242)))
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (active) Color.White else Color(0xFF616161), fontSize = 11.sp)
    }
}

@Composable
fun Step(n: Int, title: String, desc: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
         active: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(if (active) Color(0xFF1B3A2F) else Color(0xFF1E1E1E)), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (active) Color(0xFF00E676) else Color(0xFF424242)), contentAlignment = Alignment.Center) {
                if (active) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                else Text("$n", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = Color(0xFF757575), fontSize = 12.sp)
            }
            Button(onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(
                if (active) Color(0xFF00E676) else Color(0xFF00E5FF), Color.Black),
                shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text(if (active) "✓" else "开启", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
