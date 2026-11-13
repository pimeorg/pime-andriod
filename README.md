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
│   ├── CMakeLists.txt                    add_subdirectory(pime-core) + pime_jni
│   └── pime_jni.cpp                      C ABI ↔ JNI，无输入逻辑
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

## 运行时约定

- core 以 `PIME_FLAG_ALLOW_INSECURE` 启动：未签名插件包可加载，签名校验仍按 core 规则执行。
- 空链（`{"plugins": []}`）合法：所有按键返回 unhandled，由 Java 层交给编辑器。
- `onStartInput` 设置上下文（app_id、field_type、locale=zh_CN、page_size=20、
  `CANDIDATES|TOUCH|SECURE`），密码框同时置 secure。
- `onFinishInput` 复位并取消焦点；会话在 `onDestroy` 释放。

## 已知限制

- 本构建未启用 WASM/Lua 运行时（core 的 `PIME_BUILD_WASM` / `PIME_BUILD_LUA` 需要
  WAMR 与 Lua 5.4），因此现有插件包（WASM/Lua）不能在本 APK 内执行。外壳与加载
  路径已就绪，补齐运行时后即可直接加载。
- 只有 debug 签名；release 签名与混淆未配置。
- 未在真机/模拟器上验证（本机无 adb 设备、无 AVD）；已用 Windows 宿主 smoke test
  验证 core 的空链代、会话与按键返回路径。
- 软键盘布局为首批最小集（字母、数字、Shift、退格、空格、回车、逗号、句点）。

## 对 pime-core 的改动

`src/platform/posix/platform_posix.cpp`：Android 上 bionic 仅在 API 28+ 声明
`getrandom()`；改为直接 `syscall(__NR_getrandom)`，使 API 24 起可编译并在所有
受支持内核上工作。其余平台补上 `<sys/random.h>`。
