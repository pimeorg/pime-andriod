# pime-android 进度

更新：2026-11-13

## 环境与需求

用户在 Windows 11 + Android SDK/NDK 环境下提出：在已创建的 `E:/pime/pime-android`
写 Android 外壳，最终生成 APK。外壳应遵循 pime-core 的宿主/插件边界，不实现输入
法业务逻辑。

本机工具链：JDK 21.0.8；SDK `E:/andiod/andriod-sdk`（platform 36、build-tools 36、
NDK 28.2.13676358、CMake 3.31.6）；Gradle 8.14.5（缓存分发 + wrapper）；AGP
8.13.2。

后续继续要求：把 WASM 插件链真正跑起来并打进 APK（不是只留加载路径），因此本阶段
集成了 WAMR、同步了插件包，并在 Windows 宿主上用同一份 core 验证了整条链。

再后续：用户授权使用 MuMu 模拟器做实测。本机 MuMu 12（Nx）位于
`t:/Program Files/Netease/MuMu`，VM 0 为 Android 15（API 35、x86_64，adb
`127.0.0.1:16384`）。实测发现并修复了两个只在 Android 上出现的问题（见下）。

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
| WAMR 2.4.1 静态链入（fast interpreter） | `app/src/main/cpp/CMakeLists.txt`、`third_party/wamr` |
| 五个第一方 WASM 插件包与默认链随包发布 | `app/src/main/assets/plugins/`、`assets/chains/default.cbor` |
| 宿主能力授权（`pime_core_permission`） | `pime_jni.cpp`、`PimeCore.java`、`PimeImeService.java` |
| MuMu 12（Android 15 / x86_64）实机验证 | 见“验证” |
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

### WASM 运行时与能力

core 的 WASM 适配层用 WAMR 2.4.1，Android 构建开启快速解释器（`WAMR_BUILD_FAST_INTERP`），
与 Windows 宿主保持一致；AOT/HIT 不启用。插件模块由 `pime-public-plugin` 的
wasm32-ndk 工具链以 freestanding 方式产出，未定义符号全部是 `env` 导入，由 core
用 `wasm_runtime_register_natives` 提供（包括资产、时钟、发射等）。

插件声明的能力不能默认可用：宿主在 `loadPlugin` 之前逐个调用
`pime_core_permission` 授权内置链用到的 `input.read`、`workspace`、`output.emit`、
`asset.read`。缺任一项时插件在使用处得到 `PIME_DENIED`（例如 translate 的词典
打不开，表现为 `NOT_FOUND`），因此授权是链能出词的前提。

### 包布局

`assets/plugins/<id>/` 与 `assets/chains/default.cbor` 首次启动复制到 `files/`。
链中的五个 id 与目录名一一对应：keymap-default（preprocess）、pinyin-parse
（parse）、pinyin-translate（translate，带 `assets/pinyin.dict`）、pinyin-rank
（rank）、candidates-format（format）。

### Android 实测发现的问题

1. **清单平台列表缺少 x64**：插件清单只列了 `android-arm64`，而 MuMu 为
   `android-x64`，`Core::plugin_load` 的平台过滤直接返回 `PIME_NOT_FOUND`，
   五个包全部加载失败。已在全部清单与模板中补上 `android-x64`、`ios-x64`、
   `harmony-x64`。
2. **WAMR 栈守卫探测在 Bionic 上崩溃**：`wasm_runtime_init` 调用 `touch_pages()`
   触碰栈页并 `mprotect` 守卫页。Bionic/ART
   主线程返回的边界落在 `PROT_NONE` 守卫页内，首次 `wasm_runtime_init` 即
   `SIGSEGV (SEGV_ACCERR)`。Android 构建改为
   `WAMR_DISABLE_STACK_HW_BOUND_CHECK=1`：跳过栈守卫探测与 sigaltstack，
   线性内存的硬件边界检查与解释器自身栈检查保留。
3. **候选提交后组合串不重置（插件侧）**：parse 插件在 `candidate` 事件里直接清空
   `buf`，导致同一次链调用中 translate/format 看不到提交前的码串，候选提交静默
   失败；不清空又会残留到下一次组合（`nihao` 提交后再输入得到 `nihaoq`）。
   最终设计：`candidate` / `space` / `enter` 视为“提交阶段”，parse 本次仍写出
   提交前码串（供 format 定位候选），但 `state.buf` 写空且不再发
   `set_composition`；键位插件只映射 `action`，不再把所有控制键标记为
   `handled`，否则空组合下的退格无法回传编辑器。
4. **宿主 InputConnection 适配**：空组合时曾用 `finishComposingText()`，
   它会把组合串当作正文留在编辑器里（表现为提交后残留 `n`），改为
   `setComposingText("", 1)` 再结束组合；退格回传从 `sendKeyEvent(DEL)`
   改为 `deleteSurroundingText(1, 0)`，软键盘下前者在 SearchView 里不生效。

## 验证

- `./gradlew assembleDebug` 成功；APK 含 `lib/arm64-v8a/libpime_jni.so` 与
  `lib/x86_64/libpime_jni.so`，以及 `assets/plugins/` 五个包和 `assets/chains/default.cbor`；
  `llvm-nm` 可见 WAMR 符号与 `nativePermission` 导出。
- Windows 宿主 smoke test（同一份 core + WAMR，快速解释器）：空链代
  `build: OK`、`activate: OK`、按键 unhandled；完整链下五个包 `load: OK`，
  `build/activate: OK`，连打 `nihao` 组合串逐键推进，候选 `[0]你 [1]你好 …`，
  选择 0 后提交 `你好`。
- 同一次 smoke 也验证了能力模型：未授权 `asset.read` 时 translate 打不开词典；
  授权后正常出词。
- **MuMu 12 实测（Android 15 / API 35 / x86_64）**：安装 APK 后 `ime enable` +
  `ime set`，在系统设置搜索框唤起键盘。logcat 显示五个插件
  `plugin loaded`（无 warning），代 build/activate 成功。依次点击软键盘
  `n i h a o`：组合串显示 `nihao`，候选栏为 `你好`；点击候选后提交，
  设置搜索框内容变为 `你好`。与 Windows 宿主 smoke 结果一致。
- 测试期间设备屏幕为 1600×900 横屏，density 240；组合/候选栏出现后键盘视图
  高度由 224dp 收缩，坐标随之变化（输入过程中需按当前布局点击）。
- **候选提交与状态复位实测**：`n i` → 候选 `你 / 你好 / 您`，点 `你` 后搜索框
  变为已提交的 `你`（组合串消失）；随后按 `n` 组合串为单独的 `n`（不再拼接旧码
  `nin`）；退格一次清空组合串，再退格一次由编辑器删除已提交的 `你`，搜索框回到
  空状态。三条路径同时验证了插件 `state` 复位、`set_composition("")` 结束组合、
  以及空组合退格回传编辑器。
- **简拼与整句实测（2026-11-13）**：同步重建后的 parse/translate 包（translate
  词表 20k 条，支持简拼、模糊音、束搜索整句）。MuMu 12 上输入 `ws`，组合串为
  `ws`，候选为 `我是 / 我说 / 晚上 / 为啥 / 无数`；点击首位后搜索框提交
  `我是`。随后验证 `woshi` → 首位 `我是`、`nh` → 首位 `你好`。首次按键加载
  20k 词表时 logcat 无 `failed to link import function` 警告。
- **三字母简拼实测（2026-11-13 第二轮）**：translate 改为原始字母网格后，
  MuMu 12 上输入 `hbg`，组合串为 `hbg`，候选栏为
  `还不够 / 和不过 / 好不过 / 和不够 / 好不够`；点击首位后搜索框提交
  `还不够`。这同时验证了 Android 侧加载的是新 wasm（`pm clear` 后插件包
  从 assets 重新复制）。
- **多场景输入实测（2026-11-13 第三轮）**：同步 20,069 条词表与内聚权重
  调整后的 wasm，重建 APK 并 `pm clear`。MuMu 12 上依次输入 13 组字符串，
  候选首位均符合预期：`hbg` → 还不够、`wanan` → 晚安、`shengrikuaile` →
  生日快乐、`tiananmen` → 天安门、`nvhai` → 女孩、`lvse` → 绿色、
  `srkl` → 生日快乐、`nhm` → 你好吗、`zgm` → 在干嘛、`dhrs` → 多喝热水、
  `nh` → 你好、`dd` → 等等、`wsm` → 为什么；`xian` 的候选中同时可见
  “先”与“西安”。logcat 中五个插件 `plugin loaded`，无 WAMR 链接警告。
- **无上下文歧义复测（2026-11-13 第三轮补测）**：词表增加排序覆盖后重新
  打包，MuMu 12 上验证 `zsh` → 早上好、`gz` → 广州、`sz` → 深圳、
  `yiqichifanba` → 一起吃饭吧、`wozaijia` → 我在家，五组候选首位均正确；
  对应的次选（这是/工作/甚至/我再见）仍可见。
- Android 实测操作注意：用 `adb shell input tap` 连点软键盘退格时，系统会
  合并/丢弃过快的事件；清空多字母组合串需约 1–2 秒一次地点击，或重新
  `ime set` 后从空输入框开始。该现象只影响自动化测试脚本，不影响手动输入。

## 对 core 的修改

`src/platform/posix/platform_posix.cpp`：

- Android 分支用 `syscall(__NR_getrandom, ...)`，因为 bionic 直到 API 28 才声明
  `getrandom()`；内核从 Android 5 起即支持该调用。
- 非 Android 的 Linux/OHOS 补 `<sys/random.h>`。

## 待完成 / 已知限制

- Lua（`PIME_BUILD_LUA`）未启用：Lua 插件包仍不能在 APK 内执行；WASM 已经可用。
- 未在物理设备上运行：实测环境为 MuMu 12 的 Android 15 x86_64 模拟器，覆盖
  JNI、WAMR 执行与 InputConnection 路径，但未覆盖 arm64 真机与厂商 ROM 差异。
- 只有 debug 签名；未配置 release 签名、混淆、图标。
- 链选择固定 `default.cbor`；未做多链/插件管理 UI。
- 插件包只在首次启动时从 `assets/` 复制到 `files/plugins/`；`adb install -r`
  保留应用数据，因此重装 APK 不会更新插件。实测新插件需先
  `adb shell pm clear dev.pime.ime`（会清空设置），或提升插件版本号让目录名
  变化。
- 未使用 `pime_core_touch`（手写输入 UI 未实现）。
