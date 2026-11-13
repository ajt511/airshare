# GestureShare 手势互传

在小米 / OPPO（以及其他安卓）手机上提供**隔空手势文件传输**体验：

> 屏幕前**张开手掌** → **握拳抓取**内容 → **侧倾手机**选择目标设备 → **张开手掌**释放发送

基于开源项目 [EasyShare](https://github.com/HotKids/EasyShare)（互传联盟协议，
MIT）构建，可直接向小米/OPPO/vivo 等手机的**原生互传**功能发送文件；另提供
独立的**局域网传输通道**（同 Wi-Fi/热点直连，双方装 GestureShare 即可）。

---

## 功能一览

| 功能 | 说明 |
|---|---|
| ✋ 隔空手势传输 | 前摄 + MediaPipe Hand Landmarker 识别伸掌/握拳，零接触发送 |
| 📳 摇一摇发送 | 应用内摇一摇直接打开手势界面（可开关） |
| 📡 互传联盟通道 | BLE 发现 + Wi-Fi Direct 直传，与小米/OPPO/vivo 原生互传互通 |
| 🌐 局域网通道 | UDP 广播发现 + HTTP 直传，无需 Wi-Fi Direct，只要求同一 Wi-Fi/热点 |
| 🖐 张掌即收 | 手势界面收到传输请求时，张开手掌即可自动接收 |
| 📳 振动反馈 | 抓取/释放震动确认（可开关） |
| 🎯 厂商适配 | MIUI/HyperOS、ColorOS 自启动与电池优化一键跳转 |

设备雷达同时列出两类目标并带通道徽标：`互传联盟`（原生互传设备）与
`局域网`（装了 GestureShare 的设备）。

## 隔空手势怎么用

1. 打开 App，点首页的「隔空手势传输」卡片（或在系统分享面板里选「手势发送」）
2. 授权相机后，**在屏幕前 20–40cm 处张开手掌**，等待提示「已识别手掌」
3. **握拳**——手机振动表示内容已「抓住」（从分享面板进入则抓住的就是那些文件；直接进入则先点「选择要抓取的文件」）
4. 握拳状态下**左右侧倾手机**，雷达上切换目标设备（也可点按切换）
5. 对准目标后**张开手掌**——内容开始发送，对方设备的原生互传/GestureShare 会弹出接收请求
6. 反过来：对方发给你时，保持手势界面打开，**张开手掌**即可自动接收（张掌即收）

提示：手势识别只在手势界面内启用，退出即关相机，不常驻后台；姿势变化需
连续约 0.5s 稳定才触发，避免误识别。

## 发送通道对比

| | 互传联盟 | 局域网 |
|---|---|---|
| 对方需装 App | ❌（原生互传即可） | ✅ 双方 GestureShare |
| 网络要求 | Wi-Fi + 蓝牙，Wi-Fi Direct 点对点 | 同一 Wi-Fi / 热点 |
| 发送端额外设置 | 需 [Shizuku](https://shizuku.rikka.app/) 授权一次（取 Wi-Fi Direct MAC） | 无 |
| 接收端 | 原生互传或 GestureShare 后台接收 | GestureShare 前台/后台接收 |

## 安装 APK（GitHub Actions 自动出包）

本仓库已配置 CI，**push 到 main 即自动构建并出 APK**：

1. Fork / 推送本仓库到 GitHub
2. 打开 Actions → Android CI → 最新一次运行
3. Artifacts 下载 `airshare-main`（含 universal / arm64 两个 APK + SHA256SUMS）
4. 也可以在 Actions 里手动触发并勾选 `publish_release` 发布 Release

> 签名：未配置 `SIGNING_*` secrets 时，CI 会为每次构建生成临时密钥。APK
> 可正常安装使用；覆盖安装不同次构建的包需先卸载。要固定签名，配置仓库
> secrets：`SIGNING_KEYSTORE_BASE64`（jks 的 base64）、`SIGNING_STORE_PASSWORD`、
> `SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。

## 本地构建

```sh
# 1. 下载手部关键点模型（首次必需；目录不存在请先创建）
mkdir -p app/src/main/assets
curl -fL -o app/src/main/assets/hand_landmarker.task \
  https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task

# 2. 构建（JDK 17+，Android SDK 37）
./gradlew assembleRelease testReleaseUnitTest
```

无模型文件时构建会直接失败并给出上面的下载命令。

## 使用前置条件

- Android 12（API 31）及以上
- 双方开启 Wi-Fi（互传联盟通道还需要蓝牙）
- 权限：附近设备、通知、（手势功能）相机
- 互传联盟**发送**需要 [Shizuku](https://shizuku.rikka.app/)（授权一次）；接收不需要
- 小米/OPPO 建议在「小米 / OPPO 适配」设置里允许自启动 + 忽略电池优化，否则后台接收会被系统杀掉

## 已知限制（如实说明）

- 向 OPPO 原生互传发送时，对方端可能提示「接收失败」但文件实际收到（CatShare/EasyShare 已知行为）
- 摇一摇目前只在 GestureShare 自己的界面内生效（应用内），系统级全局摇一摇需要常驻后台，暂未实现
- 隔空手势依赖前摄光线条件，强逆光下识别率会下降
- LAN 通道的后台接收跟随 App 内「后台接收」开关，需厂商适配设置到位

## 目录导览（fork 新增部分）

```
app/src/main/java/me/pipi/easyshare/
├── gesture/          # 手势分类器、状态机、摇一摇、侧倾选择（纯逻辑，可单测）
├── vision/           # MediaPipe Hand Landmarker 封装
├── lan/              # 局域网：UDP 发现 + HTTP 收发（Ktor）
├── compat/           # MIUI/ColorOS 自启动、电池优化跳转
├── GestureTransferActivity.kt   # 全屏手势界面（CameraX + 雷达 UI）
└── ui/gesture/       # 手势界面 Compose UI + ViewModel
```

上游协议引擎（`services/`、`utils/` 中的 BLE/P2P/Ktor 部分）保持 EasyShare 原样。

## 许可与致谢

MIT License（继承上游），详见 [LICENSE](LICENSE) 与 [ATTRIBUTION.md](ATTRIBUTION.md)。
