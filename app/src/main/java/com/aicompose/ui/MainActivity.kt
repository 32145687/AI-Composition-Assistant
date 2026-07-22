package com.aicompose.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicompose.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            // 权限获取成功，由 ViewModel 处理
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                MainScreen(
                    onRequestCapture = {
                        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
                    },
                    onCaptureResult = { resultCode, data ->
                        // 由 Composable 内部处理
                    }
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onRequestCapture: () -> Unit,
    onCaptureResult: (Int, Intent?) -> Unit
) {
    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsState()
    val captureRunning by viewModel.captureRunning.collectAsState()
    val aiRunning by viewModel.aiRunning.collectAsState()
    val overlayVisible by viewModel.overlayVisible.collectAsState()
    val autoExecute by viewModel.autoExecute.collectAsState()
    val analysisResult by viewModel.analysisResult.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    // 生命周期监听
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkAccessibilityStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D0D0D),
                        Color(0xFF1A1A2E),
                        Color(0xFF16213E)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // 标题
            Text(
                "📸 AI 构图助手",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "让原生相机更智能",
                color = Color(0xFFB0BEC5),
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 状态概览
            StatusOverview(
                accessibilityEnabled = accessibilityEnabled,
                captureRunning = captureRunning,
                aiRunning = aiRunning,
                overlayVisible = overlayVisible
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 步骤卡片
            Text(
                "使用步骤",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            // Step 1: 无障碍服务
            StepCard(
                step = 1,
                title = "启用无障碍服务",
                description = "用于模拟触控操作小米相机 UI",
                icon = Icons.Default.Accessibility,
                isEnabled = accessibilityEnabled,
                buttonText = if (accessibilityEnabled) "已启用 ✓" else "去启用",
                onClick = { viewModel.openAccessibilitySettings() }
            )

            // Step 2: 屏幕捕获
            StepCard(
                step = 2,
                title = "开启屏幕捕获",
                description = "捕获取景画面供 AI 分析",
                icon = Icons.Default.ScreenShare,
                isEnabled = captureRunning,
                buttonText = if (captureRunning) "运行中 ✓" else "开启",
                onClick = { viewModel.requestScreenCapture(ActivityResultContracts.StartActivityForResult().let { screenCaptureLauncher }) },
                enabled = accessibilityEnabled
            )

            // Step 3: AI 分析
            StepCard(
                step = 3,
                title = "启动 AI 分析",
                description = "实时分析构图并生成调整建议",
                icon = Icons.Default.AutoAwesome,
                isEnabled = aiRunning,
                buttonText = if (aiRunning) "运行中 ✓" else "启动",
                onClick = {
                    if (aiRunning) viewModel.stopAIAnalysis()
                    else viewModel.startAIAnalysis()
                },
                enabled = captureRunning
            )

            // Step 4: 叠加层
            StepCard(
                step = 4,
                title = "显示构图叠加层",
                description = "在小米相机上方显示评分和引导",
                icon = Icons.Default.Layers,
                isEnabled = overlayVisible,
                buttonText = if (overlayVisible) "已显示 ✓" else "显示",
                onClick = { viewModel.toggleOverlay() },
                enabled = aiRunning
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 控制面板
            if (aiRunning) {
                ControlPanel(
                    autoExecute = autoExecute,
                    onToggleAutoExecute = { viewModel.toggleAutoExecute() },
                    stats = viewModel.getStats()
                )
            }

            // 分析结果
            analysisResult?.let { result ->
                AnalysisResultCard(result)
            }

            // 使用说明
            UsageGuide()

            Spacer(modifier = Modifier.height(40.dp))
        }

        // 底部状态栏
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter),
            color = Color(0xFF1E1E1E),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    statusMessage,
                    color = Color(0xFFB0BEC5),
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
fun StatusOverview(
    accessibilityEnabled: Boolean,
    captureRunning: Boolean,
    aiRunning: Boolean,
    overlayVisible: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatusChip("无障碍", accessibilityEnabled)
        StatusChip("捕获", captureRunning)
        StatusChip("AI分析", aiRunning)
        StatusChip("叠加层", overlayVisible)
    }
}

@Composable
fun StatusChip(label: String, isActive: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF00E676) else Color(0xFF424242))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            color = if (isActive) Color.White else Color(0xFF616161),
            fontSize = 12.sp
        )
    }
}

@Composable
fun StepCard(
    step: Int,
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEnabled: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFF1B3A2F) else Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 步骤编号
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isEnabled) Color(0xFF00E676) else Color(0xFF424242)
                    )
            ) {
                if (isEnabled) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.Black)
                } else {
                    Text("$step", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(description, color = Color(0xFF757575), fontSize = 13.sp)
            }

            Button(
                onClick = onClick,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEnabled) Color(0xFF00E676) else Color(0xFF00E5FF),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ControlPanel(
    autoExecute: Boolean,
    onToggleAutoExecute: () -> Unit,
    stats: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("控制面板", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("自动执行调整", color = Color.White, fontSize = 15.sp)
                    Text(
                        "AI 会自动操作相机参数",
                        color = Color(0xFF757575),
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = autoExecute,
                    onCheckedChange = { onToggleAutoExecute() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF00E676),
                        checkedTrackColor = Color(0xFF00E676).copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(stats, color = Color(0xFF757575), fontSize = 12.sp)
        }
    }
}

@Composable
fun AnalysisResultCard(result: com.aicompose.ai.CompositionEngine.AnalysisResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("构图分析", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${result.analysisTimeMs}ms",
                    color = Color(0xFF757575),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 总分
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${result.score.toInt()}",
                    color = when {
                        result.score >= 80 -> Color(0xFF00E676)
                        result.score >= 60 -> Color(0xFF00E5FF)
                        result.score >= 40 -> Color(0xFFFFAB00)
                        else -> Color(0xFFFF1744)
                    },
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "/100",
                    color = Color(0xFF757575),
                    fontSize = 20.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 子项分数
            result.scores.entries.sortedByDescending { it.value }.forEach { (key, value) ->
                val name = when (key) {
                    "thirds" -> "三分法"
                    "golden" -> "黄金比例"
                    "symmetry" -> "对称性"
                    "leading" -> "引导线"
                    "balance" -> "视觉平衡"
                    "depth" -> "纵深感"
                    "simplicity" -> "简洁性"
                    "horizon" -> "水平线"
                    else -> key
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.width(70.dp))
                    LinearProgressIndicator(
                        progress = { value },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = when {
                            value >= 0.7f -> Color(0xFF00E676)
                            value >= 0.5f -> Color(0xFF00E5FF)
                            value >= 0.3f -> Color(0xFFFFAB00)
                            else -> Color(0xFFFF1744)
                        },
                        trackColor = Color(0xFF333333)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${(value * 100).toInt()}%",
                        color = Color(0xFF757575),
                        fontSize = 12.sp,
                        modifier = Modifier.width(36.dp)
                    )
                }
            }

            // 待执行指令
            if (result.commands.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("待执行调整", color = Color(0xFFFFAB00), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                result.commands.forEach { cmd ->
                    Text(
                        "• ${cmd::class.simpleName}",
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun UsageGuide() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("使用指南", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            val tips = listOf(
                "📱 打开小米相机，切换到专业模式",
                "🔍 AI 会自动分析取景画面的构图",
                "📐 检测三分法、黄金比例、对称性等构图法则",
                "🤖 自动调整：放大缩小、切换参数、点击对焦",
                "⚡ 支持手动/自动两种模式",
                "🎯 目标：帮助你拍出构图更好的照片",
                "💡 建议：先熟悉手动模式，再开启自动调整"
            )

            tips.forEach { tip ->
                Text(
                    tip,
                    color = Color(0xFFB0BEC5),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}
