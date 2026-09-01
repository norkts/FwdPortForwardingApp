#!/bin/bash

# ============================================================================
# 自动编译安装脚本
# 功能：自动检查 adb 连接、构建 debug/release APK 并安装到设备
# 使用方法: ./build.sh [选项] [debug|release]
# ============================================================================

set -e  # 遇到错误立即退出

# ============================================================================
# 全局配置
# ============================================================================

# 脚本所在目录（支持从任意位置调用）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 默认构建类型
BUILD_TYPE="debug"

# 是否清理旧构建
DO_CLEAN=false

# 颜色常量
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # 无颜色

# ============================================================================
# 工具函数
# ============================================================================

# 打印信息（绿色）
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

# 打印警告（黄色）
print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# 打印错误（红色）
print_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

# 打印步骤标题（青色加粗）
print_step() {
    echo ""
    echo -e "${CYAN}${BOLD}>>> $1${NC}"
}

# 显示帮助信息
show_help() {
    echo -e "${BOLD}自动编译安装脚本${NC}"
    echo ""
    echo "用法: $0 [选项] [构建类型]"
    echo ""
    echo "构建类型："
    echo "  debug      构建调试版本（默认）"
    echo "  release    构建发布版本"
    echo ""
    echo "选项："
    echo "  -c, --clean    构建前执行清理（等同于 gradlew clean）"
    echo "  -h, --help     显示此帮助信息"
    echo ""
    echo "示例："
    echo "  $0                 # 构建 debug 版本并安装"
    echo "  $0 release         # 构建 release 版本并安装"
    echo "  $0 -c debug        # 先清理，再构建 debug 版本并安装"
    echo "  $0 --clean release # 先清理，再构建 release 版本并安装"
    echo ""
    echo "环境要求："
    echo "  - Android SDK 已安装且 adb 在 PATH 中"
    echo "  - 设备已通过 USB 连接并开启 USB 调试"
}

# ============================================================================
# 参数解析
# ============================================================================

parse_args() {
    local positional_count=0

    while [ $# -gt 0 ]; do
        case "$1" in
            -h|--help)
                show_help
                exit 0
                ;;
            -c|--clean)
                DO_CLEAN=true
                shift
                ;;
            debug|release)
                BUILD_TYPE="$1"
                shift
                ;;
            *)
                print_error "未知参数: $1"
                echo "运行 '$0 --help' 查看用法"
                exit 1
                ;;
        esac
    done
}

# ============================================================================
# 核心功能模块
# ============================================================================

# 检查 adb 连接状态，返回可用设备序列号（或为空表示唯一设备）
ADB_DEVICE_FLAG=""

check_adb() {
    print_step "检查 adb 连接"

    # 检查 adb 命令是否存在
    if ! command -v adb &> /dev/null; then
        print_error "adb 命令不存在"
        print_warn "请确保 Android SDK 已安装，并将 platform-tools 添加到 PATH"
        exit 1
    fi

    # 获取已连接设备列表（排除标题行和离线/未授权设备）
    local devices_output
    devices_output=$(adb devices 2>/dev/null | grep -w "device" || true)
    local device_count
    device_count=$(echo "$devices_output" | grep -c . || true)

    if [ "$device_count" -eq 0 ]; then
        print_error "未检测到已连接的 adb 设备"
        print_warn "请检查："
        print_warn "  1. 设备已通过 USB 连接到电脑"
        print_warn "  2. 设备已开启 USB 调试模式"
        print_warn "  3. 已在设备上授权此电脑进行调试"
        print_warn "  4. 已安装对应设备的 USB 驱动"
        exit 1
    elif [ "$device_count" -eq 1 ]; then
        local serial
        serial=$(echo "$devices_output" | awk '{print $1}')
        print_info "已连接设备: ${serial}"
        ADB_DEVICE_FLAG="-s ${serial}"
    else
        # 多设备场景：列出所有设备供用户选择
        print_warn "检测到多个设备："
        echo ""
        echo "$devices_output" | awk '{print "  " NR ". " $1 " (" $2 ")"}'
        echo ""

        local selected=0
        while [ "$selected" -eq 0 ]; do
            read -r -p "请输入设备序号 (1-${device_count}): " choice
            if [[ "$choice" =~ ^[0-9]+$ ]] && [ "$choice" -ge 1 ] && [ "$choice" -le "$device_count" ]; then
                local serial
                serial=$(echo "$devices_output" | sed -n "${choice}p" | awk '{print $1}')
                ADB_DEVICE_FLAG="-s ${serial}"
                print_info "已选择设备: ${serial}"
                selected=1
            else
                print_warn "无效输入，请输入 1 到 ${device_count} 之间的数字"
            fi
        done
    fi
}

# 执行 Gradle 构建
# 全局变量：BUILD_TYPE, SCRIPT_DIR
# 输出：APK_FILE 路径
APK_FILE=""

build_app() {
    print_step "开始构建 ${BUILD_TYPE} 版本"

    cd "$SCRIPT_DIR"

    local gradle_task
    if [ "$BUILD_TYPE" = "release" ]; then
        gradle_task="assembleRelease"
    else
        gradle_task="assembleDebug"
    fi

    print_info "执行命令: ./gradlew ${gradle_task}"

    if ! ./gradlew "$gradle_task"; then
        print_error "Gradle 构建失败"
        exit 1
    fi

    # 查找生成的 APK 文件
    local search_pattern
    if [ "$BUILD_TYPE" = "release" ]; then
        search_pattern="*release*.apk"
    else
        search_pattern="*debug*.apk"
    fi

    APK_FILE=$(find app/build/outputs/apk -name "$search_pattern" -type f 2>/dev/null | head -1 || true)

    if [ -z "$APK_FILE" ] || [ ! -f "$APK_FILE" ]; then
        print_error "构建完成但未找到 ${BUILD_TYPE} APK 文件"
        print_warn "请检查 app/build/outputs/apk/ 目录"
        exit 1
    fi

    # 显示 APK 信息
    local apk_size
    apk_size=$(ls -lh "$APK_FILE" | awk '{print $5}')
    print_info "构建成功: ${APK_FILE}"
    print_info "文件大小: ${apk_size}"
}

# 安装 APK 到设备
install_apk() {
    print_step "安装 APK 到设备"

    # shellcheck disable=SC2086
    if adb ${ADB_DEVICE_FLAG} install -r -t "$APK_FILE"; then
        print_info "安装成功！"
    else
        print_error "APK 安装失败"
        print_warn "可能原因："
        print_warn "  - 设备存储空间不足"
        print_warn "  - APK 签名与已安装版本不匹配"
        print_warn "  - 设备 Android 版本低于应用 minSdkVersion"
        exit 1
    fi
}

# 清理旧构建产物
clean_build() {
    print_step "清理旧构建"
    cd "$SCRIPT_DIR"

    if ./gradlew clean; then
        print_info "清理完成"
    else
        print_warn "清理过程中出现警告，继续执行"
    fi
}

# ============================================================================
# 主流程
# ============================================================================

main() {
    echo ""
    echo -e "${BOLD}========================================${NC}"
    echo -e "${BOLD}       FwdPortForwardingApp 编译脚本${NC}"
    echo -e "${BOLD}========================================${NC}"
    echo ""
    print_info "构建类型: ${BUILD_TYPE}"

    # 检查 adb 连接
    check_adb

    # 可选清理
    if [ "$DO_CLEAN" = true ]; then
        clean_build
    fi

    # 构建
    build_app

    # 安装
    install_apk

    # 完成
    print_step "全部完成"
    print_info "APK 已成功安装到设备"
}

# ============================================================================
# 入口
# ============================================================================

parse_args "$@"
main
