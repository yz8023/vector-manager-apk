# Vector 管理器 APK 发布仓库

Vector Framework（LSPosed 延续分支，由 JingMatrix 维护）的管理器预编译安装包发布仓库。本仓库仅提供可直接安装的 APK，源码位于上游主仓库。

## 简介

Vector 是一个面向现代 Android 的 ART 挂钩框架，以 Zygisk 模块形式运行，基于 LSPlant 构建，与原版 Xposed API 保持一致。管理器 App（本仓库发布的 APK）负责：

- 查看与管理已启用的 Xposed 模块（启用 / 禁用、批量操作、备份与恢复）
- 编辑模块作用域：勾选模块作用于哪些应用
- **按应用反向查看**：以应用为中心浏览"哪些模块作用于它 / 哪些模块声明推荐作用于它"，支持一键把应用加入推荐模块的作用域
- 查看框架状态、日志与崩溃报告

## 安装要求

1. 已 root 的设备（Magisk / KernelSU），并启用 Zygisk（推荐配合 [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)）
2. 已刷入 Vector 框架模块（[Releases](https://github.com/JingMatrix/Vector/releases)）
3. 支持Android 8.1 ～ Android 17 Beta

## 使用说明

1. 刷入框架模块并重启后，从系统通知进入管理器设置
2. 本仓库 APK 为管理器本体，安装后即可使用
3. 模块页顶部可在「模块 / 应用」两个视图间切换：应用视图把被模块作用的应用排在前面，点击应用可查看作用于它的模块

## 注意事项

- 本仓库发布的 APK 使用 debug 签名，仅供体验与测试
- 仓库内不含任何源码；如需构建，请参考上游主仓库
