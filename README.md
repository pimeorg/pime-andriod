# pime-android

pime-core 的 Android 宿主外壳。它是一个标准输入法（IME），只负责宿主职责：

- 创建并驱动一个 pime-core 实例；
- 把按键、文本、上下文、焦点交给 core；
- 把 core 的 sink 命令（组合串、提交、候选、提示音）落到 Android 编辑器与 UI；
- core 未处理的按键直接回传编辑器。

分词、词典、候选、排序、状态机等业务全部在插件里，本仓库不实现。

## 结构

```
pime-android/
├── app/src/main/AndroidManifest.xml      IME service + 启动器 Activity
├── app/src/main/res/xml/method.xml       input-method 元数据
├── app/src/main/java/dev/pime/ime/
│   ├── PimeImeService.java               InputMethodService：core 生命周期与 HAL sink
│   ├── PimeCore.java                     JNI 封装
│   ├── KeyboardView.java                 软键盘视图（只上报按键）
│   └── SettingsActivity.java             启用/切换输入法
├── app/src/main/cpp/
│   ├── CMakeLists.txt                    add_subdirectory(pime-core) + vmlib + pime_jni
│   └── pime_jni.cpp                      C ABI ↔ JNI，无输入逻辑
├── app/src/main/assets/
│   ├── plugins/                          随包发布的 WASM 插件包（五个）
│   └── chains/default.cbor               默认链
├── third_party/wamr/                     WAMR 2.4.1 源码（core 的 WASM 后端）
├── TASK.md / PROGRESS.md
└── gradlew / gradlew.bat
```

## 构建

依赖：

| 项 | 版本 |
|---|---|
| JDK | 17+（本机 21） |
| Android SDK | compileSdk 36，build-tools 36 |
| NDK | 28.2.13676358 |
| CMake | 3.31.6（SDK 组件） |
| Gradle | 8.14.5（wrapper） |
| AGP | 8.13.2 |
| WAMR | 2.4.1（`third_party/wamr`，fast interpreter） |
| pime-core | `../pime-core`（默认） |

```bash
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

`pime-core` 不在默认位置时：

```bash
./gradlew assembleDebug -PpimeCoreDir=/path/to/pime-core
```

（`app/build.gradle` 通过 `pimeCoreDir` 传给 CMake 的 `PIME_CORE_DIR`。）

安装与启用：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 打开 "PIME 输入法" → 启用输入法 → 选择输入法
```

## 数据目录

首次启动创建，后续可直接推包：

```
/data/data/dev.pime.ime/files/
├── plugins/<id>/manifest.yaml + plugin.wasm|plugin.lua + 资产
├── chains/default.cbor          # tools/chainc 生成的链配置；缺失时使用空链
└── trust/                       # 信任根
```

`assets/plugins/` 与 `assets/chains/` 若存在，会在首次启动时复制到上述目录。
已存在的目录不会覆盖：更新 APK 里的插件包后需要清除应用数据（或手动替换
`files/plugins/`）才会生效。

## 运行时约定

- core 以 `PIME_FLAG_ALLOW_INSECURE` 启动：未签名插件包可加载，签名校验仍按 core 规则执行。
- 启动后在加载插件前授予内置链声明的能力（`input.read`、`workspace`、`output.emit`、
  `asset.read`）。core 失败关闭：声明但未授权的能力在使用时返回 `PIME_DENIED`。
- WASM 后端为 WAMR 快速解释器（`WAMR_BUILD_FAST_INTERP=1`）；插件模块由 NDK clang
  + wasm-ld 以 freestanding 方式构建，未定义符号作为 `env` 导入由 core 提供。
- 空链（`{"plugins": []}`）合法：所有按键返回 unhandled，由 Java 层交给编辑器。
- `onStartInput` 设置上下文（app_id、field_type、locale=zh_CN、page_size=20、
  `CANDIDATES|TOUCH|SECURE`），密码框同时置 secure。
- `onFinishInput` 复位并取消焦点；会话在 `onDestroy` 释放。

## 已知限制

- Lua 运行时未启用（`PIME_BUILD_LUA=OFF`）：Lua 插件包不能在本 APK 内执行；
  WASM 插件包已可执行。
- 随包只内置五个第一方 WASM 包（keymap-default、pinyin-parse、pinyin-translate、
  pinyin-rank、candidates-format）与一条默认链。
- 只有 debug 签名；release 签名与混淆未配置。
- 未在真机/模拟器上验证（本机无 adb 设备、无 AVD）；已在 Windows 宿主上以同一份
  core + WAMR 跑通五包加载、链激活与 `nihao → 你好`。
- 软键盘布局为首批最小集（字母、数字、Shift、退格、空格、回车、逗号、句点）。

## 对 pime-core 的改动

`src/platform/posix/platform_posix.cpp`：Android 上 bionic 仅在 API 28+ 声明
`getrandom()`；改为直接 `syscall(__NR_getrandom)`，使 API 24 起可编译并在所有
受支持内核上工作。其余平台补上 `<sys/random.h>`。
