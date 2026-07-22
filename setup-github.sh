#!/bin/bash
# ============================================
# AI 构图助手 - GitHub 推送 & 自动构建脚本
# ============================================
# 用法: ./setup-github.sh <github仓库地址>
# 例如: ./setup-github.sh https://github.com/yourname/AI-Composition-Assistant.git
# ============================================

set -e

REPO_URL="$1"

if [ -z "$REPO_URL" ]; then
    echo "❌ 请提供 GitHub 仓库地址"
    echo ""
    echo "用法: ./setup-github.sh <仓库地址>"
    echo "示例: ./setup-github.sh https://github.com/yourname/AI-Composition-Assistant.git"
    echo ""
    echo "如果没有仓库，先去 GitHub 创建一个："
    echo "  1. 打开 https://github.com/new"
    echo "  2. 仓库名: AI-Composition-Assistant"
    echo "  3. 选择 Public 或 Private"
    echo "  4. 不要勾选 README/LICENSE/.gitignore"
    echo "  5. 点击 Create repository"
    echo "  6. 复制仓库地址，运行此脚本"
    exit 1
fi

echo "🚀 开始设置..."
echo "📦 仓库: $REPO_URL"
echo ""

# 检查 git
if ! command -v git &> /dev/null; then
    echo "❌ 需要安装 git"
    exit 1
fi

# 进入项目目录
cd "$(dirname "$0")"

# 初始化 git
if [ ! -d ".git" ]; then
    echo "📁 初始化 Git 仓库..."
    git init
    git branch -M main
fi

# 配置用户信息（如果未配置）
if [ -z "$(git config user.name)" ]; then
    read -p "👤 输入你的 Git 用户名: " GIT_NAME
    git config user.name "$GIT_NAME"
fi

if [ -z "$(git config user.email)" ]; then
    read -p "📧 输入你的 Git 邮箱: " GIT_EMAIL
    git config user.email "$GIT_EMAIL"
fi

# 添加远程仓库
echo "🔗 添加远程仓库..."
git remote remove origin 2>/dev/null || true
git remote add origin "$REPO_URL"

# 添加所有文件
echo "📝 添加文件..."
git add -A

# 提交
echo "💾 提交代码..."
git commit -m "feat: AI构图助手初始版本

- 无障碍服务: 检测小米相机UI + 模拟手势
- 屏幕捕获: MediaProjection实时帧捕获
- AI引擎: 8项构图法则检测(三分法/黄金比/对称/引导线等)
- 悬浮窗叠加: 评分面板 + 构图网格 + 动作提示
- 自动调整: 放大缩小/参数切换/点击对焦"

# 推送
echo "⬆️ 推送到 GitHub..."
git push -u origin main

echo ""
echo "✅ 推送成功！"
echo ""
echo "📋 接下来 GitHub Actions 会自动构建 APK："
echo "   1. 打开你的仓库页面"
echo "   2. 点击 'Actions' 标签"
echo "   3. 等待构建完成（约 5-10 分钟）"
echo "   4. 点击最新的构建任务"
echo "   5. 在 'Artifacts' 区域下载 APK"
echo ""
echo "🔗 仓库地址: $REPO_URL"
echo "🔗 Actions 页面: ${REPO_URL%.git}/actions"
echo ""
echo "📱 下载 APK 后传到小米14u 安装即可！"
