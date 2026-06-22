# 大黄鸭世界杯预测 · iOS 壳

WKWebView 壳 App,加载 https://duck.gobet365.win 。GitHub Actions 在 macOS runner 上自动构建**未签名 IPA**(用于 AltStore / Sideloadly 等侧载,或第三方重签名分发)。

## 自动构建
推送到 `main` 或在 Actions 手动触发 **Build unsigned IPA**：
1. 用 XcodeGen 从 `project.yml` 生成 Xcode 工程
2. `xcodebuild` 无签名编译
3. 打包成 `WorldCupDuck.ipa`，作为 Actions Artifact 上传（`WorldCupDuck-unsigned-ipa`）

## 安装
在 Actions 运行页底部下载 `WorldCupDuck-unsigned-ipa.zip`，解压得到 `WorldCupDuck.ipa`，用 AltStore / Sideloadly / 签名工具安装到设备。

- Bundle ID: `com.duck.worldcup.ios`
- 显示名: 大黄鸭世界杯预测
