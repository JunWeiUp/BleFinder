# BleFinder 蓝牙信号探测器 🔎

[![Android CI](https://github.com/JunWeiUp/BleFinder/actions/workflows/android.yml/badge.svg)](https://github.com/JunWeiUp/BleFinder/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![minSdk](https://img.shields.io/badge/minSdk-21%2B-green.svg)](app/build.gradle.kts)

[English](README.md) | **简体中文**

通过实时追踪蓝牙信号强度（RSSI）来寻找丢失的蓝牙设备——就像给耳机、手表、追踪器配了一个「金属探测器」。丢失的设备**不需要**能发声，只要处于开机状态即可。

## 功能

- **双通道扫描**：BLE 广播（`BluetoothLeScanner`，低延迟模式）+ 经典蓝牙发现（自动循环），覆盖耳机、手环、追踪器和老式蓝牙设备。
- **设备列表按信号强弱排序**，颜色分档（绿=近，红=远），20 秒未出现的设备自动移除。
- **设备自定义命名**：长按设备（或点 ✏️ 图标）即可重命名，按 MAC 地址持久保存。
- **追踪模式**：
  - 大号实时 RSSI 数值（指数平滑去抖动）
  - 信号强度条 + 粗略距离估算（对数距离模型）
  - **1 米校准**：在离目标设备 1 米处采样一次，记住它的真实信号基准，该设备的距离估算远比通用默认值准确
  - 趋势箭头：↑ 越来越近 / ↓ 越来越远 / → 稳定（近 2 秒 vs 4~8 秒前均值对比）
  - 最近信号走势折线图
  - **蜂鸣随接近加快**——钻到床底、沙发缝找东西时不用盯屏幕
  - 靠近时震动；追踪期间屏幕常亮
- 中英双语界面、深色主题，Android 5.0（API 21）起可用，适配 Android 12+ 蓝牙权限模型。

## 获取应用

从最新一次 [Actions 构建](https://github.com/JunWeiUp/BleFinder/actions/workflows/android.yml)下载 `app-debug-apk` 产物，或自行构建：

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 说明：`settings.gradle.kts` 将阿里云/腾讯 Maven 镜像放在官方仓库（Google/Maven Central）之前作为兜底，国内外网络均可构建。

## 怎么找到你的设备

1. 先确认丢失的设备处于**开机**状态——关机的设备不会发出任何蓝牙信号，任何 App 都找不到。
2. 打开 App，授权蓝牙权限（Android 11 及以下系统还要求开启位置服务——这是系统要求，App 不会读取你的位置）。
3. 在列表里通过名字或 MAC 找到目标设备（通常在信号最强的那批里），可以先重命名方便识别。
4. 点击进入追踪模式，**缓慢移动**：每次半米、停两秒，看趋势箭头再决定方向：
   - 信号每增强约 **6 dBm**，距离大约**减半**
   - 慢慢转动手机朝向，某个方向信号会明显更强（天线方向性）
   - 隔墙、金属和人体遮挡都会大幅衰减信号
5. 越接近蜂鸣节奏越快；RSSI 到 **-55 dBm 以上**基本就在 1 米以内，直接翻找周围即可。

## 权限说明

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12+） | 扫描设备、读取设备名 |
| `BLUETOOTH` / `BLUETOOTH_ADMIN`（Android 11-） | 旧系统扫描支持 |
| `ACCESS_FINE_LOCATION`（Android 11-） | 系统对蓝牙扫描的要求；应用从不读取位置，Android 12+ 上扫描已标记 `neverForLocation` |
| `VIBRATE` | 靠近时震动反馈 |

## 已知限制

- RSSI 受反射和遮挡影响波动很大，「估计距离」只是粗略推算，**以趋势箭头和颜色分档为准**。
- 高频扫描较耗电，找到设备后请退出追踪。
- 部分手机 BLE 与经典发现并行时，经典设备结果可能偏慢。

## 项目结构

```
app/src/main/java/com/blefinder/app/
  MainActivity.kt       # 权限流程、扫描列表、追踪 UI、蜂鸣/震动反馈
  ScanManager.kt        # BLE + 经典蓝牙双通道扫描封装
  DeviceListAdapter.kt  # 设备列表适配器
  DeviceNameStore.kt    # 设备自定义命名，按 MAC 持久化
  SparkView.kt          # 信号走势折线图（自定义 View）
```

## 参与贡献

欢迎 Issue 和 PR——尤其欢迎在不同手机型号上对 RSSI 平滑系数、趋势阈值和距离分档的调优反馈。

## 许可证

[MIT](LICENSE)
