# Gradle 国内镜像源配置 & 自动编译安装脚本实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Gradle 构建配置替换为国内镜像源（阿里云），并创建 build.sh 脚本实现自动编译并安装到已连接的 adb 设备。

**Architecture:** 修改 build.gradle、settings.gradle 中的仓库配置，替换为阿里云镜像；创建 shell 脚本封装 gradle 编译、打包和 adb 安装流程。

**Tech Stack:** Gradle 3.1.2, Android SDK 27, Bash Shell, ADB

**Spec:** 无（直接实施）

---

## Global Constraints

- Android 项目，使用 Gradle 构建系统
- 当前配置使用 jcenter()、google()、maven.fabric.io 仓库
- Gradle 版本：3.1.2（Android 插件版本）
- 保持原有构建功能不变
- 所有注释使用中文

---

## 文件结构

### 修改的文件：
1. `build.gradle` - 主构建配置，替换 buildscript 和 allprojects 的仓库
2. `settings.gradle` - 添加插件管理配置（可选）

### 创建的文件：
1. `build.sh` - 自动编译和安装脚本

---

## 任务分解

### Task 0: 修改 gradle-wrapper.properties - Gradle 下载地址镜像

**Files:**
- Modify: `gradle/wrapper/gradle-wrapper.properties:6`

**Interfaces:**
- Consumes: 无
- Produces: Gradle 下载地址改为国内镜像

**步骤：**

- [ ] **Step 1: 备份原始 gradle-wrapper.properties**

```bash
cp /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/gradle/wrapper/gradle-wrapper.properties \
   /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/gradle/wrapper/gradle-wrapper.properties.backup
```

- [ ] **Step 2: 替换 distributionUrl**

将 `distributionUrl` 行替换为：

```properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-4.4-all.zip
```

- [ ] **Step 3: 验证修改**

```bash
cat /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/gradle/wrapper/gradle-wrapper.properties
```

Expected: 看到腾讯云镜像地址

---

### Task 1: 修改主 build.gradle - buildscript 仓库镜像

**Files:**
- Modify: `build.gradle:5-10`

**Interfaces:**
- Consumes: 无
- Produces: buildscript 仓库配置改为国内镜像

**步骤：**

- [ ] **Step 1: 备份原始 build.gradle**

```bash
cp /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle \
   /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle.backup
```

- [ ] **Step 2: 替换 buildscript repositories 块**

将 `buildscript.repositories` 部分替换为：

```gradle
buildscript {
    repositories {
        maven {
            url 'https://maven.aliyun.com/repository/google'
        }
        maven {
            url 'https://maven.aliyun.com/repository/central'
        }
        maven {
            url 'https://maven.aliyun.com/repository/gradle-plugin'
        }
        google()
        maven {
            url 'https://maven.fabric.io/public'
        }
    }
}
```

- [ ] **Step 3: 验证修改**

```bash
cat /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle | grep -A 15 "buildscript {" | head -20
```

Expected: 看到阿里云镜像配置

---

### Task 2: 修改主 build.gradle - allprojects 仓库镜像

**Files:**
- Modify: `build.gradle:20-25`

**Interfaces:**
- Consumes: Task 1 的 buildscript 修改
- Produces: allprojects 仓库配置改为国内镜像

**步骤：**

- [ ] **Step 1: 替换 allprojects repositories 块**

将 `allprojects.repositories` 部分替换为：

```gradle
allprojects {
    repositories {
        maven {
            url 'https://maven.aliyun.com/repository/google'
        }
        maven {
            url 'https://maven.aliyun.com/repository/central'
        }
        maven {
            url 'https://maven.aliyun.com/repository/gradle-plugin'
        }
        maven {
            url 'https://maven.google.com'
        }
    }
}
```

- [ ] **Step 2: 验证修改**

```bash
cat /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle | grep -A 20 "allprojects {" | head -25
```

Expected: 看到阿里云镜像配置

---

### Task 3: 创建 build.sh 自动编译安装脚本

**Files:**
- Create: `build.sh`

**Interfaces:**
- Consumes: Gradle 构建系统、adb 工具
- Produces: 可执行的编译安装脚本

**步骤：**

- [ ] **Step 1: 创建 build.sh 脚本文件**

```bash
#!/bin/bash

# 自动编译并安装到已连接的 adb 设备
# 使用方法: ./build.sh [debug|release]

set -e  # 遇到错误立即退出

# 配置
PROJECT_DIR="/Users/norkts/Documents/trae_projects/FwdPortForwardingApp"
BUILD_TYPE="${1:-debug}"  # 默认为 debug 版本
APK_PATH=""

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 工具函数
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 adb 连接
check_adb_connection() {
    print_info "检查 adb 设备连接..."
    if ! command -v adb &> /dev/null; then
        print_error "adb 命令不存在，请确保 Android SDK 已正确安装"
        exit 1
    fi
    
    DEVICES=$(adb devices | grep -w "device" | wc -l)
    if [ "$DEVICES" -eq 0 ]; then
        print_error "未检测到已连接的 adb 设备"
        print_warn "请确保："
        print_warn "  1. 设备已通过 USB 连接"
        print_warn "  2. 设备已开启 USB 调试"
        print_warn "  3. 已授权此电脑进行调试"
        exit 1
    elif [ "$DEVICES" -gt 1 ]; then
        print_warn "检测到多个设备，请指定设备："
        adb devices -l
        read -p "请输入设备序列号: " DEVICE_SERIAL
        if [ -z "$DEVICE_SERIAL" ]; then
            print_error "未指定设备序列号"
            exit 1
        fi
        DEVICE_FLAG="-s $DEVICE_SERIAL"
    else
        DEVICE_FLAG=""
        print_info "已连接设备："
        adb devices -l
    fi
}

# 执行构建
build_app() {
    print_info "开始构建应用..."
    cd "$PROJECT_DIR"
    
    if [ "$BUILD_TYPE" = "release" ]; then
        print_info "构建 Release 版本..."
        ./gradlew assembleRelease
        APK_PATH=$(find app/build/outputs/apk -name "*.apk" -type f | grep release | head -1)
    else
        print_info "构建 Debug 版本..."
        ./gradlew assembleDebug
        APK_PATH=$(find app/build/outputs/apk -name "*.apk" -type f | grep debug | head -1)
    fi
    
    if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
        print_error "构建失败，未找到 APK 文件"
        exit 1
    fi
    
    print_info "构建成功：$APK_PATH"
}

# 安装到设备
install_to_device() {
    print_info "安装 APK 到设备..."
    
    if adb $DEVICE_FLAG install -r "$APK_PATH"; then
        print_info "✓ 安装成功！"
        print_info "APK 路径：$APK_PATH"
    else
        print_error "安装失败"
        exit 1
    fi
}

# 清理旧构建
clean_build() {
    print_info "清理旧构建..."
    cd "$PROJECT_DIR"
    ./gradlew clean
}

# 主流程
main() {
    print_info "=== 自动编译安装脚本 ==="
    print_info "构建类型：$BUILD_TYPE"
    echo ""
    
    # 检查连接
    check_adb_connection
    echo ""
    
    # 询问是否清理
    read -p "是否清理旧构建？(y/n): " CLEAN_CHOICE
    if [ "$CLEAN_CHOICE" = "y" ] || [ "$CLEAN_CHOICE" = "Y" ]; then
        clean_build
        echo ""
    fi
    
    # 构建
    build_app
    echo ""
    
    # 安装
    install_to_device
    echo ""
    
    print_info "=== 完成 ==="
}

# 显示使用方法
show_usage() {
    echo "使用方法: $0 [build_type]"
    echo ""
    echo "构建类型："
    echo "  debug    - 构建调试版本（默认）"
    echo "  release  - 构建发布版本"
    echo ""
    echo "示例："
    echo "  $0          # 构建 debug 版本并安装"
    echo "  $0 release  # 构建 release 版本并安装"
}

# 解析命令行参数
if [ "$1" = "--help" ] || [ "$1" = "-h" ]; then
    show_usage
    exit 0
fi

# 执行主流程
main
```

- [ ] **Step 2: 赋予执行权限**

```bash
chmod +x /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.sh
```

- [ ] **Step 3: 验证脚本语法**

```bash
bash -n /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.sh
```

Expected: 无错误输出

- [ ] **Step 4: 测试脚本帮助选项**

```bash
cd /Users/norkts/Documents/trae_projects/FwdPortForwardingApp && ./build.sh --help
```

Expected: 显示使用帮助

---

### Task 4: 验证 Gradle 配置和整体测试

**Files:**
- Verify: `build.gradle` 的镜像配置
- Verify: `build.sh` 的功能

**Interfaces:**
- Consumes: Task 1-3 的所有修改
- Produces: 确认配置正确

**步骤：**

- [ ] **Step 1: 验证 build.gradle 内容**

```bash
cat /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle
```

检查：
- buildscript.repositories 包含阿里云镜像
- allprojects.repositories 包含阿里云镜像
- 原有配置保留（如 maven.fabric.io）

- [ ] **Step 2: 测试 Gradle 构建**

```bash
cd /Users/norkts/Documents/trae_projects/FwdPortForwardingApp && ./gradlew tasks
```

Expected: 无仓库访问错误

- [ ] **Step 3: 验证 build.sh 脚本**

```bash
cd /Users/norkts/Documents/trae_projects/FwdPortForwardingApp && ./build.sh --help
```

Expected: 显示帮助信息

- [ ] **Step 4: 提交更改**

```bash
cd /Users/norkts/Documents/trae_projects/FwdPortForwardingApp
git add build.gradle build.sh
git commit -m "feat: 替换 Gradle 仓库为阿里云国内镜像并添加自动编译安装脚本"
```

---

## 自检清单

1. **镜像配置完整性** ✓
   - buildscript.repositories 已配置阿里云镜像
   - allprojects.repositories 已配置阿里云镜像
   - 保留了原有必要的仓库（如 maven.fabric.io）

2. **构建脚本功能** ✓
   - 支持 debug/release 构建选择
   - 自动检查 adb 设备连接
   - 支持多设备场景
   - 可选清理旧构建
   - 错误处理和彩色输出

3. **代码质量** ✓
   - 脚本有详细的中文注释
   - 使用 set -e 确保错误处理
   - 提供 --help 选项
   - 模块化设计，职责清晰

4. **无占位符** ✓
   - 所有代码块都是完整的实际代码
   - 没有 "TBD" 或 "TODO"
   - 测试命令和预期结果都已明确

---

## 备份信息

原始 build.gradle 备份位置：
`/Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle.backup`

如需恢复原始配置，可运行：
```bash
cp /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle.backup \
   /Users/norkts/Documents/trae_projects/FwdPortForwardingApp/build.gradle
```

---

**Plan created:** 2026-09-01
