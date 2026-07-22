# AI 构图助手 📸

**让原生相机更智能** — 通过无障碍服务 + 屏幕捕获，让 AI 自动操控小米14 Ultra 专业模式。

## 核心思路

```
用户打开小米相机 → 切换专业模式
        ↓
无障碍服务检测到相机 App 前台
        ↓
MediaProjection 捕获屏幕 → 每帧送入 AI 分析
        ↓
AI 输出: 放大2x / 调整ISO / 点击对焦 / ...
        ↓
无障碍服务模拟手势执行:
  - PinchGesture → 放大缩小
  - SwipeGesture → 切换参数值
  - ClickGesture → 点击对焦/切换模式
        ↓
用户看到相机自动调整，满意后按快门
```

## 技术方案

### 三层架构

```
┌──────────────────────────────────────────────┐
│            UI Layer (Compose)                │
│  主界面: 服务控制 / 状态监控 / 分析结果展示      │
├──────────────────────────────────────────────┤
│          Overlay Layer (悬浮窗)               │
│  构图叠加: 三分线 / 评分面板 / 动作提示          │
├─────────────┬────────────────────────────────┤
│  Service Layer                               │
│  ┌─────────────────┐ ┌─────────────────────┐ │
│  │ Accessibility    │ │ ScreenCapture       │ │
│  │ Service          │ │ Service             │ │
│  │ • 检测相机前台    │ │ • MediaProjection   │ │
│  │ • 读取UI控件     │ │ • 帧捕获            │ │
│  │ • 模拟手势       │ │ • Bitmap输出        │ │
│  └────────┬────────┘ └──────────┬──────────┘ │
│           │                     │            │
│  ┌────────▼─────────────────────▼──────────┐ │
│  │        AI Analysis Service              │ │
│  │  CompositionEngine → GestureExecutor    │ │
│  └────────────────────────────────────────-┘ │
└──────────────────────────────────────────────┘
```

### 核心技术点

| 技术 | 用途 | 说明 |
|------|------|------|
| **AccessibilityService** | UI 操控 | 模拟 pinch/swipe/click，操作小米相机控件 |
| **MediaProjection** | 画面捕获 | 捕获屏幕内容送入 AI 分析 |
| **CompositionEngine** | AI 分析 | 纯算法实现：三分法/黄金比/对称/引导线/平衡/纵深/简洁/水平 |
| **GestureExecutor** | 指令执行 | 节流 + 重试 + 稳定性过滤 |
| **ComposeOverlayView** | 叠加层 | 悬浮窗显示评分、网格、动作提示 |

### AI 构图评分体系

| 指标 | 权重 | 检测方法 |
|------|------|----------|
| 三分法 | 25% | 主体中心到三分线交叉点的距离 |
| 黄金比例 | 15% | 主体中心到黄金分割点的距离 |
| 引导线 | 12% | Sobel 边缘检测 + 线条方向分析 |
| 视觉平衡 | 10% | 四象限亮度分布均匀度 |
| 简洁性 | 10% | 颜色量化后的种类数量 |
| 对称性 | 10% | 左右镜像像素差异 |
| 地平线 | 10% | 水平边缘占比 |
| 纵深感 | 8% | 上下区域亮度对比 |

## 使用流程

### 1. 安装 App
用 Android Studio 打开项目 → Build → 安装到小米14 Ultra

### 2. 授权权限
- **无障碍服务**: 设置 → 无障碍 → AI构图助手 → 开启
- **屏幕捕获**: App 内点击"开启" → 系统弹窗确认
- **悬浮窗**: 设置 → 应用 → AI构图助手 → 悬浮窗权限

### 3. 使用
1. 打开小米相机，切换到**专业模式**
2. 回到 AI 构图助手，依次启动服务
3. 切回小米相机，看到叠加层显示构图评分
4. AI 会自动调整参数，或显示建议
5. 满意后直接按快门拍照

## 项目结构

```
app/src/main/java/com/aicompose/
├── App.kt                           # Application
├── service/
│   ├── ComposeAccessibilityService.kt # 无障碍服务（核心）
│   ├── ScreenCaptureService.kt       # 屏幕捕获服务
│   └── AIAnalysisService.kt          # AI 分析服务
├── ai/
│   └── CompositionEngine.kt          # 构图分析引擎
├── gesture/
│   └── GestureExecutor.kt            # 手势执行器
├── overlay/
│   ├── ComposeOverlayView.kt         # 叠加层 View
│   └── ComposeOverlayManager.kt      # 叠加层管理
├── viewmodel/
│   └── MainViewModel.kt              # 主界面 ViewModel
└── ui/
    └── MainActivity.kt               # 主界面 (Compose)
```

## 小米相机 UI 控件适配

无障碍服务通过 `viewIdResourceName` 定位小米相机控件。以下 ID 基于逆向分析，**需要在真机上验证**：

```kotlin
// 使用方法: 打开 App → 启用无障碍 → 打开小米相机 → 查看 Logcat
// Tag: "ComposeA11y" 会输出完整的 UI 树结构
// 找到对应控件的 ID 后更新 ComposeAccessibilityService.kt 中的常量
```

**如何获取真实控件 ID:**
1. 启用 App 的无障碍服务
2. 打开小米相机专业模式
3. 过滤 Logcat: `adb logcat -s ComposeA11y`
4. 在 UI 树中找到参数控件的 `id` 值
5. 更新 `ID_ISO_VALUE`、`ID_SHUTTER_VALUE` 等常量

## 扩展方向

- **TFLite 集成**: 添加深度学习美学评分模型
- **场景识别**: 自动检测人像/风景/夜景，推荐最佳参数
- **学习模式**: 记录用户的调整偏好，个性化建议
- **语音播报**: TTS 语音提示构图建议
- **拍摄指导**: 在用户按下快门前给出最终构图建议

## 限制

- 需要 Android 9+ (API 28+)
- 屏幕捕获有约 1-2% 性能开销
- AI 分析基于屏幕画面（非原始传感器数据）
- 小米相机控件 ID 可能因 MIUI 版本不同而变化
- 无障碍服务每次重启需要重新授权

## License

MIT License
