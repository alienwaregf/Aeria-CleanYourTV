# 📺 CleanYourTV 懒人版

### Aerial + CleanYourTV，二合一，一次装好。

**一块干净的电视桌面，加上一个能瘦身的系统。**

[下载 APK](#安装) · [对比](#对比) · [致谢](#致谢)

![版本](https://img.shields.io/badge/版本-软件内更新-2997ff?style=flat-square)
![包名](https://img.shields.io/badge/包名-com.gathin.slimtv-555555?style=flat-square)
![Android](https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![鸣谢](https://img.shields.io/badge/鸣谢-Aerial·CleanYourTV-555555?style=flat-square)

---

## 对比

|  | Aeria-CleanYourTV（我们） | Aerial | CleanYourTV |
|---|---|---|---|
| **定位** | 桌面 + 瘦身，二合一 | 电视桌面 | 电视桌面 + 瘦身 |
| **电视桌面** | ✅ | ✅ | ✅ |
| **系统瘦身** | ✅ | ❌ | ✅ |
| **瘦身怎么做** | ✅ 电视本身即可，电脑更方便 | ❌ | 工具跑 `pm` 命令；launcher 须自己编译 |
| **天气** | ✅ 定位精准，接入和风，数据详细，恶劣天气提醒 | ❓ 定位经常错误 | 插件提供 |
| **屏保 / 壁纸** | ✅ 与Aerial一致 | ✅ 航拍 4K /<br>![Dolby Vision](https://img.shields.io/badge/-Dolby%20Vision-black?logo=dolby) | ✅ 主题 + 屏保插件 |
| **信号源免切换预览** | ✅ 不切信号源全屏预览 | ✅ 需要设为壁纸 | ❌ |
| **图标** | 只用App官方图标 | 联网拉远程图标 | — |
| **语言** | 只留中 / 英 | 中 / 英 / 哈 / 俄 / 乌 | 31 种 |
| **最低安卓** | 5.0+ | 5.0+ | 8.0+ |
| **安装** | 安装 APK | 安装 APK | 自己编译后装；社区有预编译 APK |
| **包名** | com.gathin.slimtv | app.orchard.tv | com.gathin.tvhome |
| **更新** | 软件内更新 | 覆盖安装 | — |
| **CEC 自动切信号源** | ✅ | ✅ | — |
| **输出设备** | ✅ | ✅ | — |
| **覆盖TV型号** | ❓ 太老的可能会有BUG | ✅ 更新频率高 | ✅ 更新频率高 |

---

## 安装

电脑和电视连同一个网络。不用 root。

### 1. 打开电视的 ADB 调试

设置 → 设备偏好设置 → 关于 → 连按「内部版本号」7 次，再在开发者选项里打开「USB 调试」（有无线调试的也打开）。电视的 IP 在「设置 → 网络 → 已连接的网络」里。第一次连接时电视会问是否允许调试，选「始终允许」。

### 2. 选一个 APK

两个包是同一个软件，只差桌面声明，按电视选一个：

| 电视 | 下载 |
|---|---|
| 📺 支持换桌面（大多数电视，如索尼） | [![APK](https://img.shields.io/badge/APK-ARMv7%20%7C%20ARMv8%20%7C%20x64-green?logo=android)](https://github.com/alienwaregf/Aeria-CleanYourTV/releases/download/v99/Aeria-CleanYourTV.apk) |
| 📺 不支持换桌面（装不上桌面类 App，如部分 TCL、海信） | [![APK](https://img.shields.io/badge/APK-ARMv7%20%7C%20ARMv8%20%7C%20x64-green?logo=android)](https://github.com/alienwaregf/Aeria-CleanYourTV/releases/download/v99/Aeria-CleanYourTV-NoHome.apk) |

然后装到电视上（文件名换成你下的那个）：

```bash
adb kill-server && adb start-server && adb connect <电视IP>:5555
adb -s <电视IP>:5555 install -r Aeria-CleanYourTV.apk
```

装完按主页键，进的就是 CleanYourTV 懒人版。无 HOME 版靠无障碍服务接管主页键，开机自动进。

---

## 致谢

- **[pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)**：Aerial 桌面本体（MIT 协议）。没有它，就没有这个桌面。
- **[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)**：CleanYourTV 瘦身清单数据与思路。电视型号的瘦身清单（如 `sony-bravia-4k-vh2`）来自这个仓库的 `examples/`。
- 壁纸与航拍影片来自 Apple TV 与必应，版权归各自所有者。
