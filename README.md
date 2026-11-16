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
├── app/src/main/java/com/pimeorg/pime/
│   ├── PimeImeService.java               InputMethodService：core 生命周期与 HAL sink
│   ├── PimeCore.java                     JNI 封装
│   ├── KeyboardView.java                 软键盘视图（只上报按键）
│   ├── ImeToolbar.java                   顶栏工具栏（页切换、粘贴、设置、收起）
│   ├── VoiceModels.java                  语音模型下载/校验/解包
│   ├── VoicePermissionActivity.java      申请 RECORD_AUDIO 的透明 Activity
│   └── SettingsActivity.java             启用/切换输入法与宿主偏好
├── app/src/main/cpp/
│   ├── CMakeLists.txt                    add_subdirectory(pime-core) + vmlib + pime_jni
│   └── pime_jni.cpp                      C ABI ↔ JNI，无输入逻辑
├── app/src/main/jniLibs/                 sherpa-onnx / onnxruntime 运行时（arm64-v8a, x86_64）
├── app/src/main/assets/
│   ├── plugins/                          随包发布的插件包（含原生 pime-speech-sherpa）
│   ├── chains/default.cbor               默认（中文）链
│   └── chains/english.cbor               内置英文链
├── third_party/wamr/                     WAMR 2.4.1 源码（core 的 WASM 后端）
├── LICENSE                               Apache License 2.0
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

### Release（签名 + R8）

`assembleRelease` 走 R8（`minifyEnabled` + `shrinkResources`），保留规则在
`app/proguard-rules.pro`：JNI 侧按符号名调用
`Java_com_pimeorg_pime_PimeCore_native*`，并按方法名回调 `onNative*`，所以
`PimeCore` 整类连同包名都不能重命名。

签名材料不进仓库（`.gitignore` 已挡 `keystore.properties` / `*.jks`）：

```properties
# keystore.properties（仓库根目录）
storeFile=app/pime-release.jks
storePassword=...
keyAlias=pime
keyPassword=...
```

```bash
keytool -genkeypair -v -keystore app/pime-release.jks -alias pime \
        -keyalg RSA -keysize 4096 -validity 10000 \
        -storetype PKCS12 -storepass <storePassword> -keypass <keyPassword> \
        -dname "CN=PIME Android, O=PIME, C=CN"
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk（约 25 MB，v1+v2 签名）
# R8 报告: app/build/outputs/mapping/release/{mapping,usage,seeds}.txt
```

没有 `keystore.properties` 时 release 仍可构建（只是不签名），方便干净检出上
跑 `assembleRelease` 做校验。release 与 debug 签名不同，切到 release 前需要先
`adb uninstall com.pimeorg.pime`（应用数据随之清空，内置插件与链会在首启重装）。

安装与启用：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 打开 "PIME 输入法" → 启用输入法 → 选择输入法
```

## 数据目录

首次启动创建，后续可直接推包：

```
/data/data/com.pimeorg.pime/files/
├── plugins/<id>/manifest.yaml + plugin.wasm|plugin.lua|plugin.so + 资产
├── chains/<name>.cbor           # tools/chainc 生成的链配置；缺失时使用空链
├── models/asr-stream-small/     # 语音模型 model.int8.onnx + tokens.txt（按需下载）
└── trust/                       # 信任根
```

`assets/plugins/` 与 `assets/chains/` 若存在，会在首次启动时复制到上述目录；
APK 更新后按包时间戳整包刷新（用户改过的链保持不动）。

装随包分发之外的插件不必再 `adb push`：设置页「插件与链 → 插件包」里有
「导入插件包」（选一个插件包的 `.zip`，允许带一层顶层目录）与「从文件夹导入」
（选一个已是插件包的目录）。导入会校验 `manifest.yaml` 与 `plugin.wasm`、按
`pime-plugin-<id>-<version>` 原子落盘，并让运行中的输入服务重扫插件目录。

## 运行时约定

- core 以 `PIME_FLAG_ALLOW_INSECURE` 启动：未签名插件包可加载，签名校验仍按 core 规则执行。
- 启动后在加载插件前授予内置链声明的能力（`input.read`、`workspace`、`output.emit`、
  `asset.read`）。core 失败关闭：声明但未授权的能力在使用时返回 `PIME_DENIED`。
- WASM 后端为 WAMR 快速解释器（`WAMR_BUILD_FAST_INTERP=1`）；插件模块由 NDK clang
  + wasm-ld 以 freestanding 方式构建，未定义符号作为 `env` 导入由 core 提供。
  Android 构建关闭 WAMR 的 native 栈守卫探测
  （`WAMR_DISABLE_STACK_HW_BOUND_CHECK=1`）：Bionic 主线程的栈边界落在
  ART 的 `PROT_NONE` 守卫页内，否则 `wasm_runtime_init` 会崩溃。
- 插件清单必须包含当前平台标识；x86_64 模拟器对应 `android-x64`。
- 空链（`{"plugins": []}`）合法：所有按键返回 unhandled，由 Java 层交给编辑器。
- 顶栏工具栏只做宿主动作：页切换、粘贴剪贴板纯文本、打开设置、收起键盘；
  粘贴在组合中会先结束组合并清掉候选，避免 `commitText` 顶掉正在输入的内容。
- 语音输入只有一个人口：长按空格键说话，松开结束并提交。长按判定的阈值就用
  系统 `ViewConfiguration.getLongPressTimeout()`（跟随无障碍设置）；短按空格
  仍然是空格，长按期间滑出键区也不会变成别的按键。识别走独立的「语音代」：
  平时停在中文/英文代，按住时空格键整键换成强调色、键面上只留一个麦克风
  图标，停下后切回原代，按键链不受影响。宿主用
  `AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, s16le)`
  采集，PCM 经 `pime_core_audio_in` 进入 core 音频环；插件用
  `audio.capture` 读取并把识别结果作为 `set_composition`/`commit` 回写。
  插件是 `runtime: native` 的 `pime-speech-sherpa`，`plugin.so` 从
  `nativeLibraryDir` 复制到插件包后设为只读。空格键上不写任何文字：一条
  空白的宽栏加一个静音色的小麦克风图标（宽栏本身就是空格），不靠文字说明
  长按手势。
- 键面上不再有说明性文字，功能键一律是自绘字形：Shift 用上箭头、退格用
  带叉的箭头、回车用拐弯箭头、符号页用 `#`、回字母页用 `A`、空格长按说话
  用麦克风；与 Shift / 回车 / 退格同一套圆头描边，大小跟随键高。唯一保留
  文字的是中/英——这两个字本身就是要表达的内容。它被做成左右两格的开关：
  「中」在左、「英」在右，中间一道 1dp 半透明细分隔（借 `theme.hint`
  加 alpha）；当前语言那侧用 `keyFuncText` 且加粗，另一侧压成 `theme.hint`
  的常规字重，所以不用额外高亮也能看出当前在哪一边。两字同字号共基线，窄屏
  时整块（含分隔线）等比缩小，不会截字。
- 语音开不了时（密码框、缺插件、缺模型、缺 `RECORD_AUDIO`）长按不会吞键：
  宿主提示原因后返回「未开始」，松手照旧是一个空格。缺模型时
  `VoiceModels` 从 GitHub release 镜像下载
  `sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01`
  （约 25 MB，sha256 校验，bzip2 + tar 只解出模型与词表），下完提示再长按；
  缺 `RECORD_AUDIO` 时由透明 Activity 申请，授权后广播回输入服务并提示长按
  空格，不会在手指已经离开的情况下偷偷开麦。
- 上滑字符只认带副标签的字母键（三行）：竖向位移至少 `max(28dp, 0.55 × 键高)`，
  且大于横向位移的 1.5 倍；不满足时按普通字母处理，快速连打不会把字母变成数字。
- 「中/英」切换代：启动时构建两代——当前中文链（`files/chains/<活动链>`）与
  内置英文链（`assets/chains/english.cbor`）——切换只做
  `pime_generation_activate`，输入中不重建链。切换时进行中的组合按原样提交，
  再 `pime_core_reset` 结束组合（parse 插件负责清空缓冲），避免旧码串在切回
  中文后复活。该选择写入 `SharedPreferences`，输入服务重启后保持。
- 英文链是 keymap → english-parse → english-translate → candidates-format。
  宿主只在按键层补两点 WeaveText 语义：空格让链提交原码后再补一个空格，
  标点/数字以外的字符先提交原码再插入该字符（不补空格）。候选点按由
  candidates-format 提交词本身，不加空格。
- 主题或键盘高度变化时设置页广播 `ACTION_DISPLAY_CHANGED`，运行中的输入服务
  重建输入视图并保留组合串与候选。
- `onStartInput` 设置上下文（app_id、field_type、locale=zh_CN、page_size=20、
  `CANDIDATES|TOUCH|SECURE`），密码框同时置 secure。
- `onFinishInput` 复位并取消焦点；会话在 `onDestroy` 释放。

## 已知限制

- Lua 运行时未启用（`PIME_BUILD_LUA=OFF`）：Lua 插件包不能在本 APK 内执行；
  WASM 插件包已可执行。
- 随包内置 20 个第一方插件包（19 个 WASM + 1 个原生 `pime-speech-sherpa`）
  与两条链：`default.cbor`（中文，可在设置页增删改）与
  `english.cbor`（英文，保留名称，不出现在链列表里）。
- 语音模型不随 APK 分发：首次使用需联网下载（约 25 MB，可走镜像）；APK 因
  附带 sherpa-onnx/onnxruntime 运行时有约 33 MB。
- MuMu 12 没有宿主麦克风输入，模拟器上只能验证采集线程与识别器加载；
  语音识别结果用 debug 回放钩子（`files/voice-test.wav`）验证，回放期间同样
  要按住空格，松手即提交。
- 只有 debug 签名；release 签名与混淆未配置。
- 未在 arm64 物理设备上验证；已在 MuMu 12（Android 15 / API 35 / x86_64）上
  跑通插件加载、`nihao → 你好` 候选与提交、语音回放预编辑与提交。
- 软键盘布局为字母/符号两页（字母、数字、Shift、退格、空格、回车、标点）。
  字母页三行都带副标签：首行 `q–p` 为 `1–0`，第二行 `a–l` 为
  `~!@#%“”*?`，第三行 `z–m` 为 `（）-_：；、`；上滑该键键入副标签字符。
  两页底行都有中/英键（左右两格 + 细分隔，当前语言实色加粗、另一侧压暗），
  用于在中英文模式间切换；空格键长按说话、短按空格，键面只有麦克风图标。
  页切换键是 `#`（去符号页）与 `A`（回字母页），均与 Shift / 回车 / 退格
  同一套字形。
  顶栏是工具栏：字母/符号页切换、粘贴、键盘设置、收起；组合串与候选
  共用工具栏下方的一行，没有组合与候选时该行隐藏。

## 许可证

本仓库以 [Apache License 2.0](LICENSE) 授权，版权归 pime 贡献者所有。

随仓库分发的第三方组件保留其原始许可：

| 组件 | 位置 | 许可 |
|---|---|---|
| WAMR 2.4.1 | `third_party/wamr/` | Apache 2.0（见 `third_party/wamr/LICENSE`） |
| sherpa-onnx 运行时 | `app/src/main/jniLibs/` | Apache 2.0 |
| onnxruntime | `app/src/main/jniLibs/` | MIT |

拼音词表数据另有 CC BY 4.0 署名要求，见 pime-public-plugin 的
`data/pinyin/SOURCES.md`。

## 对 pime-core 的改动

`src/platform/posix/platform_posix.cpp`：Android 上 bionic 仅在 API 28+ 声明
`getrandom()`；改为直接 `syscall(__NR_getrandom)`，使 API 24 起可编译并在所有
受支持内核上工作。其余平台补上 `<sys/random.h>`。
