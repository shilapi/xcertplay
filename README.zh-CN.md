# xcertplay

[English](README.md) | 中文

`xcertplay` 是面向 Android 车机的 CarPlay 接收端项目。支持通过 CH341 I2C 桥接到 MFI 芯片，亦或是通过板载 I2C 控制器直连 MFI 芯片，支持 CarPlay 有线或无线连接。

> 仍在绝赞开发中。

## Features

- 面向 Android 和 Android Automotive OS 的 CarPlay 主机应用。
- 支持 CH341 桥接 MFI 芯片、原生 `/dev/i2c-N` 设备连接的 MFI芯片、Remote MFI 认证（API见下）。
- 支持 CarPlay 有线或无线连接。
- 支持触发 CarPlay Ultra （未测试/未完成的协议栈，但是确实可以在 iPhone 上触发 CarPlay Ultra 的提示）。
- 支持语音、导航、音乐多通道音频输出并 mapping 至 Android 的对应通道。
- 支持动态 Activity resize ，并自动重新握手至新的分辨率。
- 支持车机位置回传。
- 支持 Android 9 (API 28) 。

## 使用方法

1. 通过蓝牙将 iPhone 与车机配对。
2. 在应用中用三指向下滑动，打开设置页面。
3. 确认所有设置均已按需配置。
4. 滑动到底部，选择 `Save & Reconnect`。
5. 按照你选择的方式连接 MFi 芯片。
6. 等待连接完成，然后开始使用。

## 当前进度

他运转👍，已在车机/手机平台测试，如果出现部分车机不适配的情况欢迎 issue （并附上你的 log ，位于 `/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`）。保存的 log 会包含 AirPlay、RCS、CAF、iAP2、USBMUX 和 Lockdown 等控制协议的每次交互摘要及详细 payload；屏幕和音频媒体流本身不会写入。

转接板：[CH341-to-MFI](https://github.com/shilapi/ch341-to-mfi-chip)

正在 MFI 芯片转接板到货...

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `common/` | 两个目标共用的 CarPlay 宿主界面、设置、持久化和应用资源。 |
| `mobile/` | 使用共享 CarPlay 主机界面的 Android 应用。 |
| `automotive/` | 使用共享主机界面并支持高级音频通道映射的 Android Automotive OS 应用。 |
| `shared/` | Car App Library 代码，以及 CH341、I2C、MFi、iPhone、iAP2、NCM、VPN、AirPlay 和媒体实现。 |

## Remote MFI 功能

Remote MFi 客户端把远程服务当作一块 MFi 芯片远程调用，抑或是采用 BAA 认证，通过远程进行认证免去了本地连接 MFI 芯片进行认证的流程。

### 端点

| Method | Path | 用途 | Request body | Success response | 失败 response |
| --- | --- | --- | --- | --- | --- |
| `GET` | `/mfi/certificate` | 获取 MFI 芯片版本、证书类型和证书内容，客户端首次调用后缓存 | 无 | 证书 JSON | `{"detail":"..."}` |
| `POST` | `/mfi/sign` | 对 challenge 签名 | `{"challenge":"...","requestId":"..."}` | `{"signature":"..."}` | `{"detail":"..."}` |
| `POST` | `/mfi/reset` | 请求重置远程 MFI 芯片 | `{}` | `{"detail":""}` | `{"detail":"..."}` |

（可选）采用标准 Bearer Authentication 进行验证。

**当前仅测试了 BAA Authentication**

## 环境要求

- 启动 Gradle 需要 JDK 17 或更高版本；daemon 通过 Gradle toolchain 解析 Java 25。
- Android SDK Platform 37。
- Android 9（API 28）或更高版本。
  在 Android 9 上不可用 Wi-Fi P2P 5 GHz 模式，应用会改用 LocalOnlyHotspot。
- Android NDK `28.2.13676358`。
- 硬件验证需要支持 USB Host/OTG 的 Android 设备以及 MFi 硬件。

## 构建

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

在 macOS 或 Linux 中：

```bash
./gradlew :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
```

构建未签名 release APK：

```powershell
.\gradlew.bat :mobile:assembleRelease :automotive:assembleRelease
```

## 致谢

感谢 [LIVI](https://github.com/f-io/LIVI) 项目为本项目提供了重要参考。
感谢 [showcase](https://github.com/amineross/showcase) 项目为本项目的 BAA 认证提供重要参考。

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。
