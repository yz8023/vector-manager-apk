# Vector 管理器 APK 发布仓库

Vector Framework（LSPosed 延续分支，由 JingMatrix 维护）的管理器与配套框架包发布仓库。

## 简介

Vector 是一个面向现代 Android 的 ART 挂钩框架，以 Zygisk 模块形式运行，基于 LSPlant 构建，与原版 Xposed API 保持一致。管理器 App 负责：

- 查看与管理已启用的 Xposed 模块（启用 / 禁用、批量操作、备份与恢复）
- 编辑模块作用域：勾选模块作用于哪些应用
- **按应用反向查看**：以应用为中心浏览「哪些模块作用于它 / 哪些模块声明推荐作用于它」，支持一键把应用加入推荐模块的作用域
- 查看框架状态、日志与崩溃报告

## 为什么必须用配套的框架包

框架在构建时会把**管理器 APK 的签名证书**编译进 daemon，运行时逐字节校验管理器签名（`InstallerVerifier`）。因此：

- 刷别人构建的 LSPosed 框架（如 Forinxy IT、官方 LSPosed 等），再装本仓库的管理器：签名校验不通过，daemon 不认可该管理器，最终会使用框架内置的旧管理器 —— 表现就是「能读到框架和模块，但没有反向视图」。
- 想换包名绕过是无效的：校验的是签名，签名不同一律拒绝。

**结论：框架与管理器必须出自同一次构建。** 下载本仓库同一 Release 里的框架 zip 与管理器 APK 配套使用即可。

## 最新版本

前往 [Releases](https://github.com/yz8023/vector-manager-apk/releases) 下载：

- `Vector-vcanary-3112-1-Release.zip`：框架模块，刷入 Magisk / KernelSU
- `vector-manager-v1.1.apk`：管理器（含反向视图）

## 刷入步骤

1. 在 Magisk / KernelSU 中卸载现有 LSPosed 模块（Forinxy IT 或其他分支，二者都 hook zygote，不能共存）
2. 刷入 `Vector-vcanary-3112-1-Release.zip`，重启
3. 安装 `vector-manager-v1.1.apk`（或重启后从框架通知进入并安装内置管理器）
4. 正常的 LSPosed / Xposed 模块全部可识别；模块页顶部切换「模块 / 应用」即可使用反向视图

原有的模块启用状态与作用域配置保存在 `/data/adb/lspd/`，沿用概率高。

## 安装要求

- 已 root 的设备（Magisk / KernelSU），并启用 Zygisk（推荐配合 [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)）
- 支持 Android 8.1 ～ Android 17 Beta

## 说明

- 本仓库发布的构建使用调试证书签名，仅供体验与测试
- 仓库内不含源码；如需构建，请参考上游主仓库

## ScopeLens：给 Forinxy LSPosed IT 用的反向视图 App

如果你用的是 **Forinxy 的 LSPosed IT 框架**且不想换框架，可以安装 `scopelens-v1.0.apk`。它不改动框架，而是以 root 调用框架**自带的 `lspctl` CLI** 读取和写入作用域，因此改动经由 daemon 自身生效，天然与框架兼容。

功能：

- **应用为中心的反向视图**：按应用浏览「哪些模块作用于它 / 哪些模块在声明中推荐作用于它」
- **一键添加 / 移除作用域**：调用 `lspctl scope add/remove`，即时生效（部分系统组件变更会提示重启）
- **模块视图**：查看每个模块的当前作用域与声明作用域
- **诊断**：显示 lspctl 路径、daemon 版本、shell uid、daemon status

依赖与前提：

- 已 root（Magisk / KernelSU / APatch）
- 设备上已安装 LSPosed IT（Forinxy）框架，`lspctl` 可执行文件存在（通常在 `/data/adb/ksu/bin/lspctl`、`/data/adb/ap/bin/lspctl` 或框架模块目录下）
- **需要开启框架的「开发者模式」**：`lspctl` 的 `module`/`scope` 命令受该开关门控，未开启时 CLI 会把这些子命令直接移除（报 "Unmatched arguments"）。v1.0.2 起启动检测到未开启时提供一键自动开启（root 直写 daemon 设置，立即生效），也可以在 LSPosed 管理器 → 设置 → Debugging → Developer mode 手动开启
- App 通过持久 `su` 会话执行 `lspctl ... --json` 并解析其单行 JSON 输出

安装：直接安装 `scopelens-v1.0.2.apk`，首次运行授予 root 权限。

> 说明：`lspctl` 是框架提供的官方调试 CLI，其输出为 `{"ok":true,"command":...,"data":{...}}` 结构。ScopeLens 依赖该结构；若框架版本变化导致输出格式调整，可在 App 的「Diag」页查看原始 status 输出协助排查。
