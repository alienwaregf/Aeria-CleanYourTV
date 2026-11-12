<div align="center">

# 📺 CleanYourTV 懒人版

### Aerial + CleanYourTV，二合一，一次装好。

**一块干净的电视桌面，加上一个能瘦身的系统。**<br>
开机就是 Aerial 的航拍桌面，没有广告，没有推荐流；<br>
再进一页，把电视里用不上的预装软件停掉，系统自己轻下来。

[下载 APK](#安装) · [功能](#功能) · [自己编译](#自己编译) · [致谢](#致谢)

![版本](https://img.shields.io/badge/版本-软件内更新-2997ff?style=flat-square)
![包名](https://img.shields.io/badge/包名-com.gathin.slimtv-555555?style=flat-square)
![Android](https://img.shields.io/badge/Android%20TV-5.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white)
![鸣谢](https://img.shields.io/badge/鸣谢-Aerial·CleanYourTV-555555?style=flat-square)

</div>

---

## 这是什么

一台 Android TV，两个问题：桌面全是广告和推荐，系统里塞满了用不上的预装软件。

这个 App 把两个开源项目合进一个安装包，一次解决：

| 一半 | 来自 | 解决的问题 |
|---|---|---|
| **Aerial 桌面** | [pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)（v1.94） | 换掉广告桌面：App、壁纸、航拍屏保，开机自动进 |
| **Clean 系统瘦身** | [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（瘦身清单数据） | 按品牌型号停掉预装软件：不卸载、可还原 |

不用 root，不动系统文件，随时能还原。已在索尼 KD-43X85K（Android 10）上长期实测。

---

## 功能

### 🖥️ Aerial 桌面

- **首页**：常用 App 放在下方的玻璃底板上，往下是全部 App；藏起来的系统 App 也在。
- **整理**：长按 OK 键移动、放进文件夹、隐藏、卸载，或设成开机时打开；HDMI 信号源能换成 Apple TV、PlayStation、Switch、Xbox、电脑的图标。
- **屏保**：Apple TV 的航拍影片，支持 4K / 4K 杜比视界；也能换成照片、时钟或手机传的本地视频。
- **壁纸**：必应每日精选、内置壁纸、手机传的照片和视频，或者航拍：主屏幕停 10 秒后在磁贴后播放。
- **控制中心**：第一排按上键到右上角打开：信号源、网络、设置、音量、亮度、个性化。
- **个性化**：每排 4、5 或 6 个 App，单排或双排，时钟大小和位置、App 名称、焦点样式、按键声音，都能改。
- **天气**：彩云 + Open-Meteo 双源，736 个城市手动选；未来三天按国标 GB/T 28592 的 12 小时降雨量分档，恶劣天气有提醒。
- **桌面守护**：在不允许更换桌面的电视上，让 CleanYourTV 懒人版保持为主屏幕，不会读取任何屏幕内容。

### 🧹 Clean 系统瘦身

- **按型号给清单**：打开瘦身页，自动按电视的品牌、型号、设备名去上游拉对应的瘦身清单，拉不到用本机缓存、再没有用内置快照。
- **两列，一眼分清**：左列是建议禁用的，右列是按使用情况自选的；点任意一行进系统应用详情页手动禁，不会误伤正在用的。
- **一键命令**：页面给出一键命令，先清数据再禁用（`pm clear` 后接 `pm disable-user`），只停用、不卸载，恢复只要重新启用，系统更新后重跑一遍就行。
- **本机网页设置**：电视里开网页就能改设置，不用再装别的工具。

> 以索尼 KD-43X85K 为例：清单默认 31 个建议禁用，另有 49 个按使用情况自选。国行本机没装的包会报 `Unknown package`，属于正常跳过；被索尼锁住的包改不了状态，会留着。

---

## 安装

电脑和电视连同一个网络。不用 root。

### 1. 打开电视的 ADB 调试

设置 → 设备偏好设置 → 关于 → 连按「内部版本号」7 次，再在开发者选项里打开「USB 调试」（有无线调试的也打开）。电视的 IP 在「设置 → 网络 → 已连接的网络」里。第一次连接时电视会问是否允许调试，选「始终允许」。

### 2. 装 APK

```bash
adb kill-server && adb start-server && adb connect <电视IP>:5555
adb -s <电视IP>:5555 install -r CleanYourTV懒人版.apk
```

装完按主页键，进的就是 CleanYourTV 懒人版。

---

## 自己编译

Mac 上编（JDK 17、Android SDK platform 34），在工程目录里跑：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew clean app:assembleStandardRelease
```

产物在 `app/build/outputs/apk/standard/release/app-standard-release.apk`。只交付 release 版。

---

## 和上游比，删了什么、加了什么

这不是简单打包，是一个二合一 fork。下面是和上游原版（Aerial v1.94、CleanYourTV）逐项对过的增减。

### 删了什么（上游有，这里没有）

- **远程图标索引**：Aerial 的图标库会联网拉一套远程图标并打日志；这里改成本地图标库，不再联网拉图，`TAG` 与日志调用随拉取一起去掉。
- **矢量天气图标**：Aerial 用 `glyphFor` 画矢量天气图标；这里天气改成彩色 emoji，4 个天气 glyph（雨、雪、雾、雷）与对应的 4 个路径常量随 `glyphFor` 一起去掉。
- **Open-Meteo 地名兜底**：Aerial 用 Open-Meteo 的英文地名，加 `mainNear` 兜底（区名拼音没对上时，找 20 km 内最重要的地名）；这里改走 ip138/ip.cn + 736 城表，`mainNear` 与 `City.rank` 随之去掉。
- **二维码设置块**：Aerial 控制中心里扫码去官网设置的入口（`aerial.qust.me/update/#setup=1`）已砍，相关两条字符串也去掉。
- **更新系统**：Aerial 的更新检查与版本说明没接进来。
- **CEC / 输出设备 / Vidaa**：没接。
- **多语言**：只留中文和英文，哈萨克、俄、乌语没留。
- **上游就有的废代码**：`dreamChecking`、`keepsMoving`、`Palette.Background`、8 条没引用的字符串——上游里也是废的，一并清了。
- **本项目清废**：11 处废代码、23 条没引用的字符串、4 个 WeatherClient 旧方法、`PROVINCE_ORDER`——都是逐项 grep 确认没引用才删的，删完又对着上游原版复核过，没有误删。

### 加了什么（这里有，上游没有）

- **电视里直接瘦身**：把 CleanYourTV 的瘦身做成电视里的页面（`SystemSlimmingActivity`）。按品牌型号拉上游清单，两列分建议禁用/自选，一键命令先清数据再禁用，电视里开网页就能改设置。上游 CleanYourTV 本身是靠电脑/助手跑命令的工具，没有这个电视端页面；瘦身清单数据照样来自上游。
- **一套自己的天气**：彩云客户端、更细的天气种类（约 20 种，上游只有 7 种）、彩色 emoji（小雨是 🌧️）、未来三天按国标 12 小时降雨量分档、恶劣天气提醒、736 城表 + ip138/ip.cn 统一取定位。
- **桌面守护**：在不允许更换桌面的电视上，让 CleanYourTV 懒人版保持为主屏幕，不会读取任何屏幕内容。
- **自编译优化**：启动基线，按 Aerial #891 的做法。
- **二合一**：Aerial 桌面 + Clean 瘦身合进一个安装包（`com.gathin.slimtv`），装一次全都有。
- **品牌**：App 名 CleanYourTV懒人版，网页标题 + 📺 图标。

上游更新了新功能时，这里的对应改动要手动合进来；反过来，这里删掉的上游功能（如远程图标索引、Open-Meteo 的地名兜底）也能从上游 v1.94 原样抄回来。

---

## 致谢

- **[pengchujin/aerial-tv](https://github.com/pengchujin/aerial-tv)**：Aerial 桌面本体（MIT 协议）。没有它，就没有这个桌面。
- **[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)**：CleanYourTV 瘦身清单数据与思路。电视型号的瘦身清单（如 `sony-bravia-4k-vh2`）来自这个仓库的 `examples/`。
- 壁纸与航拍影片来自 Apple TV 与必应，版权归各自所有者。

---

## 许可证

MIT。详见 [LICENSE](LICENSE)（如仓库里没有，请以 Aerial 上游的 MIT 协议为准）。
