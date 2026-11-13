# pime-android 进度

更新：2026-11-13

## 环境与需求

用户在 Windows 11 + Android SDK/NDK 环境下提出：在已创建的 `E:/pime/pime-android`
写 Android 外壳，最终生成 APK。外壳应遵循 pime-core 的宿主/插件边界，不实现输入
法业务逻辑。

本机工具链：JDK 21.0.8；SDK `E:/andiod/andriod-sdk`（platform 36、build-tools 36、
NDK 28.2.13676358、CMake 3.31.6）；Gradle 8.14.5（缓存分发 + wrapper）；AGP
8.13.2。无 adb 设备、无 AVD。

## 已完成

| 内容 | 位置 |
|---|---|
| Gradle 工程与 wrapper | `settings.gradle`、`build.gradle`、`app/build.gradle`、`gradlew*` |
| IME 注册（service、intent-filter、method.xml） | `AndroidManifest.xml`、`res/xml/method.xml` |
| 启动器设置页（启用/切换、插件目录） | `SettingsActivity.java` |
| core 生命周期、会话、上下文、焦点、安全、复位 | `PimeImeService.java`、`PimeCore.java` |
| 插件加载、代 build/activate、链 CBOR 读取 | `PimeCore.java`、`PimeImeService.java` |
| HAL sink → Java（组合、提交、候选、隐藏、提示音、通知） | `pime_jni.cpp` |
| UTF-8/UTF-16 转换、组合串字节游标换算 | `pime_jni.cpp` |
| 软键盘（字母/数字/Shift/退格/空格/回车/标点） | `KeyboardView.java` |
| 未处理按键回传编辑器（零插件可用） | `PimeImeService.java` |
| NDK 静态链接 core，`libpime_jni.so` 双 ABI | `app/src/main/cpp/CMakeLists.txt` |
| debug APK 产出 | `app/build/outputs/apk/debug/app-debug.apk` |
| 宿主 smoke test | 临时工程（非仓库内），见下 |

## 设计思路

### 边界

Java 层只做 Android 宿主：按键、编辑器交互、视图、权限与目录。所有输入法语义
都在 core 之后的插件链里。`pime_jni.cpp` 是纯翻译层：结构体填充、字符串转换、
回调映射，不含任何状态机或规则。

### 生命周期

`onCreate` 创建 core（`PIME_FLAG_ALLOW_INSECURE`）→ 安装 `assets/` 内的插件和链到
`files/` → 加载 `files/plugins/*` → 从 `files/chains/default.cbor`（缺失时用空链）
build/activate 代。`onDestroy` 释放会话与 core。

每个输入框复用一个会话：`onStartInput` 设置 context/secure/focus，`onFinishInput`
focus=false + reset。sink 回调在 owner 线程同步到达，直接更新 UI。

### 按键路径

1. 软键盘上报 `Key`（Unicode 码点或专用键值）。
2. `pime_core_key` 返回 handled → 结束。
3. unhandled 或错误 → 退格/回车发送 `KeyEvent`，其余字符 `commitText`。

因此即使没有任何插件，键盘也可正常输入 ASCII；安装带插件的链后，按键先经过
core，组合串和候选由插件产生。

### 缓冲与文本

JNI 每次按键构造 `pime_key_event`，文本以 UTF-8 传入；sink 文本按 UTF-8 解码为
UTF-16 交给 Java。组合串游标按“字节前缀 → UTF-16 单元数”换算后再 +1 传给
`setComposingText`。

## 验证

- `./gradlew assembleDebug` 成功；APK 含 `lib/arm64-v8a/libpime_jni.so` 与
  `lib/x86_64/libpime_jni.so`；`aapt2 dump badging` 显示 `provides-component:'ime'`。
- Windows 宿主 smoke test（链接同一份 core）：空链代 `build: OK`、`activate: OK`、
  会话创建成功；字母/空格/退格/回车均 `handled=0`，sink 无输出——与 Java 层的
  passthrough 设计一致。

## 对 core 的修改

`src/platform/posix/platform_posix.cpp`：

- Android 分支用 `syscall(__NR_getrandom, ...)`，因为 bionic 直到 API 28 才声明
  `getrandom()`；内核从 Android 5 起即支持该调用。
- 非 Android 的 Linux/OHOS 补 `<sys/random.h>`。

## 待完成 / 已知限制

- 未启用 WAMR（`PIME_BUILD_WASM`）与 Lua（`PIME_BUILD_LUA`）：现有 WASM/Lua 插件包
  不能在 APK 内执行。加载路径已就绪，补运行时后无需改 Java。
- 未在真机/模拟器运行（无设备）；JNI 回调与 UI 只做了编译期与 core 侧验证。
- 只有 debug 签名；未配置 release 签名、混淆、图标。
- 链选择固定 `default.cbor`；未做多链/插件管理 UI。
- 未使用 `pime_core_touch`（手写输入 UI 未实现）。
