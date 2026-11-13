# PocketSSH

> 手机上的 SSH 终端 —— 纯 Kotlin 编写的开源 Android SSH 客户端，自带 VT100 / xterm-256color 终端模拟器。

[![Build APK](https://github.com/zkkm2580/PocketSSH/actions/workflows/android.yml/badge.svg)](https://github.com/zkkm2580/PocketSSH/actions/workflows/android.yml)
![minSdk](https://img.shields.io/badge/minSdk-24-brightgreen)
![platform](https://img.shields.io/badge/platform-Android-blue)
![license](https://img.shields.io/badge/license-MIT-lightgrey)

```
┌────────────────────────────────────────┐
│ ← 家里的服务器                已连接    │
├────────────────────────────────────────┤
│ root@debian:~# htop                    │
│ █  CPU0  12.4%   CPU1   8.1%           │
│ Mem[||||||||         1.2G/4.0G]        │
│ Swp[                 0K/2.0G]          │
│ 1  [||||      12.4%]  2  [||      8.1%]│
│ root@debian:~# █                       │
├────────────────────────────────────────┤
│ ESC TAB CTRL ALT ↑ ↓ ← → HOME END PGDN │
└────────────────────────────────────────┘
```

## 功能

- **完整终端模拟器**（自研，非 WebView / 非现成控件）
  - VT100/xterm 控制序列：光标移动、擦除、插入/删除行与字符、滚动区域、制表位、保存/恢复光标
  - SGR 富文本：加粗、暗淡、斜体、下划线、反显、隐藏、删除线
  - **256 色 + 24 位真彩色**（`38;5;n` / `38;2;r;g;b`）
  - **Alternate Screen**（`?1049h/l`），`vim`、`htop`、`less`、`mc` 全屏程序正常显示
  - UTF-8 增量解码，**中日韩宽度字符双列对齐**
  - 3000 行滚动回看，上下滑动即翻页，输入自动回到实时输出
  - 响应 DA / DSR 查询，支持 bracketed paste（粘贴大段文本不会被 vim 自动缩进破坏）
- **SSH 连接**
  - 密码认证、键盘交互认证（keyboard-interactive）
  - 私钥认证：RSA / ECDSA / Ed25519（OpenSSH 新格式，可带口令）
  - 主机密钥 **TOFU** 校验：首次连接展示 SHA-256 指纹，之后自动比对，密钥变更直接阻断
  - Keep-Alive 心跳，断线后一键重连
  - PTY 尺寸随屏幕 / 键盘 / 字号实时同步（`SIGWINCH`）
- **移动端体验**
  - 屏幕上一排快捷键：ESC、TAB、粘性 CTRL / ALT、方向键、HOME、END、PGUP、PGDN、`/`、`-`、`|`、`~`、`^C`
  - 中文输入法可正常组合上屏
  - 可调字号（8–26sp），深色终端配色（One Dark 风格）
  - 连接期间保持屏幕常亮，一键复制整屏内容

## 安装

1. 到 [Releases](https://github.com/zkkm2580/PocketSSH/releases) 下载 `app-release.apk`；
2. 在手机上允许「安装未知来源应用」后安装；
3. 打开 App → 右下角 **+** → 填主机地址 / 端口 / 用户名 / 密码或私钥 → 保存 → 点击连接。

首次连接会显示服务器指纹，确认与服务器一致后点「信任并连接」。

### 常见问题

- **能登录但打不出字 / 键盘弹不出来**：点一下终端区域即可唤起键盘。1.0.1 起输入法由隐藏的 `EditText` 承载（`TerminalInputView`），字符、退格、回车、方向键分别走 IME 的 commit / deleteSurroundingText / keyEvent 通道，不再依赖自定义 `InputConnection`。
- **连不上**：确认手机与服务器网络互通；公司网络可能屏蔽 22 端口，可改端口再试。
- **提示主机密钥已更改**：说明服务器重装或换了密钥。确认无误后，在连接列表长按该主机 →「忘记此主机密钥」再连接。

## 自己编译

环境要求：**JDK 17**、Android SDK（platform 34、build-tools 34.0.0）。

```bash
# 1. 指向你的 Android SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 单元测试（终端解析器）
./gradlew :app:testDebugUnitTest

# 3. 构建
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

也可以直接用本机的 `gradle`（8.7+）执行同样的任务，或用 Android Studio 打开项目。

`keystore/release.jks.b64` 是一个**演示用签名证书**（base64 文本形式提交，构建时自动解码），
目的是让 CI 与本地都能产出可直接安装、可覆盖升级的 release APK。
**正式发布请换成自己的证书**，并修改 `keystore/keystore.properties`。

CI 会在每次 push 到 `main` 时跑单元测试并构建 debug/release APK（见 `.github/workflows/android.yml`），
打 `v*` 标签时会自动创建 Release 并附上 APK。

## 项目结构

```
app/src/main/java/com/zkkm/pocketssh/
├── PocketSshApp.kt          Application 入口
├── MainActivity.kt          连接列表
├── HostAdapter.kt           列表适配器
├── HostEditActivity.kt      新建 / 编辑连接
├── TerminalActivity.kt      终端界面，串联会话与模拟器
├── term/
│   ├── TerminalModel.kt     单元格、颜色编码、滚动缓冲
│   ├── AnsiPalette.kt       xterm-256 调色板
│   ├── VtEmulator.kt        VT100/xterm 解析器与屏幕状态机
│   └── TerminalView.kt      Canvas 渲染 + 输入法/按键接入
└── ssh/
    ├── SshHost.kt           连接配置模型
    ├── HostStore.kt         配置持久化
    ├── KnownHostsStore.kt   主机密钥（TOFU）
    └── SshSession.kt        JSch 会话、PTY、读写线程
```

## 技术要点

| 关注点 | 做法 |
| --- | --- |
| SSH 协议 | [mwiede/jsch](https://github.com/mwiede/jsch)（JSch 活跃维护分支，支持 Ed25519 与 ed25519-sk） |
| 终端解析 | 自写状态机（GROUND / ESC / CSI / OSC / DCS），逐码点处理，UTF-8 增量解码 |
| 渲染 | 自定义 `View` + `Canvas`，按样式合并同色同属性字符连续绘制，减少 drawText 调用 |
| 线程模型 | 网络线程读 → 主线程喂给模拟器；模拟器内部对状态加锁，渲染时同锁读取 |
| 主机密钥 | 自定义 `HostKeyRepository`，未知密钥通过对话框阻塞式确认，变更直接拒绝 |
| 剪贴板 | bracketed paste 感知：多行粘贴自动包裹 `ESC[200~ … ESC[201~` |

## 安全说明

- 密码与私钥保存在应用私有存储（`SharedPreferences`），**未加密**；设备已 root 或应用被备份时可能泄露。
  后续版本计划接入 `EncryptedSharedPreferences` / Android Keystore。
- 传输层由 SSH 本身加密；应用不联网除你配置的服务器以外的任何地址，无统计、无广告、无遥测。
- 仓库中的 `keystore/` 仅用于演示签名，请勿用于正式分发。

## Roadmap

- [ ] 私钥导入（文件选择器 / 剪贴板）与密钥生成
- [ ] 凭据加密存储（Android Keystore）
- [ ] 多会话标签页与后台保活
- [ ] 单触选择复制（选区）、鼠标上报（vim 鼠标）
- [ ] 端口转发 / SFTP 文件浏览

## 许可证

[MIT](LICENSE)
