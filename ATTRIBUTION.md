# Attribution / 开源致谢

AirShare（隔空互传）是社区开源成果的衍生作品，感谢以下项目：

## 传输协议基座

- **[HotKids/EasyShare](https://github.com/HotKids/EasyShare)**（MIT License）
  本仓库的工程骨架、互传联盟协议实现（BLE GATT 发现、Wi-Fi Direct 连接、
  Ktor/Netty 传输栈）、UI 框架与大部分工具类均来自 EasyShare。手势与局域网
  通道为本次 fork 新增，其余上游代码版权归其作者所有。
- **[kmod-midori/CatShare](https://github.com/kmod-midori/CatShare)**（MIT License）
  EasyShare 的上游，最早的互传联盟协议独立实现之一。EasyShare 的 LICENSE
  中保留了其版权声明。

## 手势识别

- **[Google MediaPipe](https://developers.google.com/mediapipe)**（Apache License 2.0）
  `tasks-vision` Hand Landmarker 提供手部 21 关键点检测；模型
  `hand_landmarker.task` 由 CI 从 Google 官方存储下载。伸掌/握拳的几何
  分类逻辑为本仓库实现（`gesture/HandGestureAnalyzer.kt`）。

## 许可

本项目整体以 MIT License 发布（继承上游条款），详见 [LICENSE](LICENSE)。
