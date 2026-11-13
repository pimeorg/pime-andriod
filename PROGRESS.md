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

最新要求：设置页做成类似 `T:/temp/WeaveText` 的首页（Material 3、品牌卡、
分组列表），图标等参考 `E:/work/pime`。本轮据此重做了启动页，并补上应用图标。

## 已完成

| 内容 | 位置 |
|---|---|
| Gradle 工程与 wrapper | `settings.gradle`、`build.gradle`、`app/build.gradle`、`gradlew*` |
| IME 注册（service、intent-filter、method.xml） | `AndroidManifest.xml`、`res/xml/method.xml` |
| 启动器设置页（Material 3 首页、品牌卡、分组列表） | `SettingsActivity.java`、`res/layout/activity_settings.xml` |
| 应用图标（自适应 + 旧系统回退） | `res/mipmap-anydpi*`、`res/drawable-nodpi/app_icon_blue*` |
| 明暗主题与配色 | `res/values{-night}/{colors,themes,bools}.xml` |
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

### 设置页

参考 WeaveText 的首页结构，但不引入 Compose：使用 AppCompat + Material
Components 的原生 View，保持 `SettingsActivity` 单文件、无状态层。页面由顶部
工具栏、primaryContainer 品牌卡、两个分组卡片和页脚组成：

- 品牌卡：PIME 标志、slogan、状态胶囊（未启用 / 已启用未切换 / 已就绪，
  纯色点为状态色），以及一个可直接唤起键盘的试验输入框。
- 未就绪时品牌卡内是 WeaveText 式的两步引导：编号圆圈（完成变绿勾、当前步
  为主题色）、标题/说明、当前步的按钮，以及“本机处理、不联网”的隐私说明。
  已就绪时引导整块隐藏，只留试验输入框。
- “键盘”组：输入法设置（系统页）、键盘与候选、插件与链、存储位置。
- “关于”组：关于 PIME、输入内核。
- 二级页：插件与链、存储位置、关于都是独立页面（工具栏返回箭头 + 系统返回键
  接管），不是对话框。插件页读各包 `manifest.yaml` 的 id/role/version 展示，
  点任意行把路径复制到剪贴板并弹 Snackbar；存储页列出两个目录并说明重装/
  `pm clear` 语义；关于页有品牌卡、版本、插件数、数据来源（rime-wanxiang
  CC BY 4.0）与许可证说明。
- 状态判断不再读 `Settings.Secure.ENABLED_INPUT_METHODS`，启用状态用
  `InputMethodManager.getEnabledInputMethodList()`，默认状态在 API 34+ 用
  `getCurrentInputMethodInfo()`、旧系统回退到 `DEFAULT_INPUT_METHOD`。
- 宽屏下内容列最宽 640dp 并居中；系统栏 inset 用 `ViewCompat` 处理，避免
  targetSdk 36 的 edge-to-edge 把标题压到状态栏下。
- 明暗两套 Material 3 配色（取自 WeaveText 的蓝色方案）；启动图标使用
  `E:/work/pime` 的蓝色 PIME 标志，裁切出透明前景并生成自适应图标。

### 启动器图标

用户在 MuMu 12 上看过设置页后指出启动器图标不对：界面里的品牌标记是「蓝色圆 +
白气泡」，而启动器画出来的是「白底 + 蓝色气泡」，正好反了。

- 源资源只用一份 `drawable-nodpi/pime_logo.webp`：它的 alpha 就是气泡轮廓，
  界面里 `tint` 成白色叠在蓝色圆上，启动器也同样用它的 alpha 描白色。
- `tools/gen_launcher_icons.py` 从该 alpha 生成：各密度 `ic_launcher.webp`
  （品牌蓝圆角方块 + 白气泡，圆角 22%）、`ic_launcher_round.webp`（蓝色圆 + 白气泡）、
  以及自适应图标前景 `ic_launcher_foreground.webp`。
- 自适应前景按规范收在 108dp 画布中央半径 31dp 的安全圆内（逐像素求最远点再缩放），
  所以任何启动器遮罩都不会切到气泡。
- `mipmap-anydpi-v26/ic_launcher{,_round}.xml` 的背景色改为品牌蓝 `#2267FA`，
  前景指向 `@mipmap/ic_launcher_foreground`；同时删掉旧的 `mipmap-anydpi/`
  位图兜底与两份不再引用的 `app_icon_blue*.webp`，各密度位图直接给 API 24/25 用。

### 试输入框

用户在 MuMu 12 上点首页的试验输入框，说「根本就不弹键盘」。

- 复查后确认两件事：一，`EditText` 自己吃掉触摸，挂在它上面的 `OnClickListener`
  永远不会触发，所以那行代码等于没写；二，输入框只能用**当前**输入法，若用户
  还没把 PIME 设为默认，框里根本不会出现 PIME 的候选 —— 但界面此前对此一言不发。
- 改成监听 `setOnFocusChangeListener`（点击的实际产物就是获得焦点），并在拿到焦点时
  显式调用 `InputMethodManager.showSoftInput`，不再依赖系统「自动弹」的行为；
  连接了物理键盘或系统设置成不自动弹时也能起来。
- edge-to-edge 关掉了窗口自己的 resize，所以 insets 监听里把底部 padding 取
  `max(systemBars.bottom, ime.bottom)`，键盘弹出时输入框不会被压住；
  Activity 同时声明 `windowSoftInputMode="adjustResize"`。
- 诚实反馈：PIME 不是当前输入法时，输入框的 helper 文案改成
  「当前不是 PIME 输入法，输入走系统输入法」，并在首次点击时弹一次 Snackbar
  （「这里只能用当前输入法，切到 PIME 才会出现候选」+「选择输入法」动作直接拉出选择器）。
- 补 `onWindowFocusChanged` 里刷新状态：从输入法选择器对话框回来时 Activity 不会
  `onResume`，否则状态胶囊与 helper 文案会停在旧值。

### 行图标

同一批问题里提到「图标啥的」：键盘与候选页当时复用了占位图标 ——「候选数量」用
拼图、「主题」用信息图标、「按键振动」与「按键音」共用同一个滑杆。现在每行一个
语义正确的图标：`ic_unfold_more`（高度）、`ic_vibration`、`ic_volume_up`、
`ic_view_list`、`ic_contrast`、`ic_memory`（输入内核）。


### 宿主显示设置

在 MuMu 12（Android 15 / x86_64）上验收 Material 3 设置页时，用户提出“设置里
要能改东西”：设置页不能只是只读信息，需要有真实可配置项。约束是这些配置只能
属于宿主视图层，不能触碰插件业务。

- 新增 `Prefs`（`SharedPreferences` 文件 `pime`）集中存放宿主偏好，`PimeApplication`
  在 `onCreate` 里按偏好设置 `AppCompatDelegate` 夜间模式，保证首个界面就是正确
  主题。
- “键盘与候选”二级页分两组：手感（键盘高度 88/100/115%、按键振动、按键音）、
  显示（候选数量 全部/3/5/7/9、主题 跟随系统/浅色/深色）。前三项就地生效——
  键盘高度在 `onCreateInputView` 按百分比缩放并同步 `minimumHeight`，振动/按键音
  在 `KeyboardView.fire()` 里判断（按键音走 `AudioManager.playSoundEffect`）。
- 候选数量在 `PimeImeService.onCandidates` 里截断显示条数，`selectCandidate` 仍用
  原始下标，因此截断只影响可见候选，不影响插件链给出的候选序号。
- 单选对话框用 `MaterialAlertDialogBuilder.setSingleChoiceItems`；改完偏好后
  `rebuildDetail()` 重建当前二级页（`Page` 在构建时读偏好），所以行副标题立即
  反映新值。主题这类需要整页重建的项交给 `AppCompatDelegate.setDefaultNightMode`，
  由系统重建 Activity。
- 开关行是自绘的 `addSwitchRow`（图标 + 标题/副标题 + `MaterialSwitch`），点击
  整行与点击开关等价。


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
5. **打开设置页立即闪退**：`SettingsActivity.refreshStatus()` 读取
   `Settings.Secure.ENABLED_INPUT_METHODS` 判断是否启用。Android 15 起该键
   只允许 `targetSdkVersion <= 33` 的应用读取，本应用 targetSdk 36，于是
   `onResume` 抛 `SecurityException`，表现为点图标即闪退。改用
   `InputMethodManager.getEnabledInputMethodList()`（公开 API，与 targetSdk
   无关）按包名判断；Java 层不再读任何 `Settings.Secure` 键。

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
- **设置页启动修复实测（2026-11-13）**：重新构建并安装后，
  `am start -W -n dev.pime.ime/.SettingsActivity` 冷启动返回
  `Status: ok`、`LaunchState: COLD`，`topResumedActivity` 为设置页；
  logcat 无 `AndroidRuntime` 异常，界面显示“状态：已启用”，插件目录与链
  路径正常。
- **Material 3 设置页实测（2026-11-13）**：MuMu 12 横屏下冷启动正常，品牌卡、
  分组卡片和图标显示完整；切换 `cmd uimode night yes/no` 后明暗两套配色均
  正常。点击“插件与链”列出 5 个完整插件包与 `default.cbor` 链路径；点击
  “存储位置”显示绝对路径；“关于”对话框显示版本与宿主/插件边界。将 PIME
  设为默认输入法后 `onResume` 立即变为“已就绪 · 当前默认输入法”并隐藏动作
  按钮；点击试验输入框能拉起 PIME 软键盘，输入 `nhso` 时组合串与候选栏
  正常出现。桌面启动器显示新的蓝色 PIME 图标。
- **设置页 UX 打磨实测（2026-11-13 第二轮）**：MuMu 12 上验证四种状态：
  未启用（灰色胶囊 + 第一步引导 + 隐私说明 + “去启用”）、已启用未切换
  （琥珀胶囊 + 第一步打勾 + 第二步“选择输入法”）、已就绪（绿色胶囊、引导隐藏）；
  `cmd uimode night yes/no` 下明暗配色均正常。三个二级页（关于、插件与链、
  存储位置）进入/返回正常，工具栏返回箭头与系统返回键都可回首页；插件页按
  `manifest.yaml` 显示 `format/preprocess/parse/rank/translate · 1.0.0`，点行
  复制路径并弹出“已复制：…”Snackbar；存储页显示两个目录与重装说明。
- **设置页 UX 细节（第三轮）**：可复制行右侧改用复制图标（导航行才用箭头），
  插件页组标题带数量（“插件包 · 5 个”），Snackbar 只报短名
  （“已复制：pime-candidates-format”）而不是整条路径；试验输入框加
  clear_text 图标与 “试试 nihao、hbg、zsh” 辅助文字；首页与二级页切换用
  `Fade` 过渡；关于页说明块的分割线不再按图标缩进。竖屏（900×1600）实测
  布局正常，无需横屏特判。
- **状态变化提示（第四轮）**：从系统页返回时若启用/默认状态发生变化，弹
  Snackbar（“已启用 PIME 输入法”“已停用 PIME 输入法”“已切换为默认输入法”）；
  实测停用后返回显示“已停用 PIME 输入法”，状态胶囊与引导同步切换。复制图标
  补 `contentDescription`，方向性图标 `autoMirrored` 以支持 RTL。
- **宿主显示设置实测（2026-11-13 第五轮）**：首页新增“键盘与候选”入口（`ic_tune`
  图标）。依次改键盘高度为“宽松（115%）”、候选数量为 5、再改为 3、主题为“深色”，
  每次 `run-as … cat shared_prefs/pime.xml` 都看到对应键落盘
  （`keyboard_percent=115` / `candidates=3` / `theme=dark`），二级页行副标题同步
  刷新（“标准（100%）”→“宽松（115%）”），改主题后 Activity 立即以深色重建。
  切换 PIME 为当前输入法后：键盘明显变高（115%），同一段简拼输入在“候选数量=3”
  下候选栏只有 3 个（就能/技能/今年），改回“全部”后同一输入出现 5 个以上
  （就能就/就那就/技能就/今年就/就你就…），确认截断只作用于宿主显示条数。
  验证完把偏好文件删除（`run-as … rm shared_prefs/pime.xml`）恢复默认值，
  并把默认输入法还原为 Sogou、PIME 保持启用。
- **图标与试输入框修复实测（2026-11-13 第六轮）**：`adb uninstall` 后全新安装，
  启动器、最近任务、应用信息页、输入法列表里的图标统一为「品牌蓝底 + 白气泡」。
  试输入框：把 PIME 设为当前输入法后点击即弹出 PIME 键盘，点 `g` 出候选
  （个/给/跟/过/更），键盘弹出时输入框仍可见；把输入法切回 Sogou 后，点击会弹
  Snackbar「这里只能用当前输入法，切到 PIME 才会出现候选」并带「选择输入法」动作。
- **MuMu 12 上 Sogou 输入法不渲染（环境问题，非宿主缺陷）**：默认输入法为
  Sogou 时，点击设置页输入框后 `dumpsys input_method` 报 `mInputShown=true`
  但屏幕上没有任何键盘；用启动器自带的「搜索游戏和应用」输入框复现同样的现象，
  说明是模拟器里 Sogou 自身的问题。把 PIME 设为当前输入法后同一位置点击即正常
  弹出。为排除「物理键盘抑制软键盘」，也把 `show_ime_with_hard_keyboard` 试过 0/1
  两档，结果一致；验证完已还原为 0。

## 对 core 的修改

`src/platform/posix/platform_posix.cpp`：

- Android 分支用 `syscall(__NR_getrandom, ...)`，因为 bionic 直到 API 28 才声明
  `getrandom()`；内核从 Android 5 起即支持该调用。
- 非 Android 的 Linux/OHOS 补 `<sys/random.h>`。

## 待完成 / 已知限制

- Lua（`PIME_BUILD_LUA`）未启用：Lua 插件包仍不能在 APK 内执行；WASM 已经可用。
- 未在物理设备上运行：实测环境为 MuMu 12 的 Android 15 x86_64 模拟器，覆盖
  JNI、WAMR 执行与 InputConnection 路径，但未覆盖 arm64 真机与厂商 ROM 差异。
- 只有 debug 签名；未配置 release 签名与混淆。
- 链选择固定 `default.cbor`；设置页只展示插件包和路径，未做多链/插件启停 UI。
- 设置页的插件列表按“目录内直接存在 `manifest.yaml` + `plugin.wasm`”筛选；
  旧版本留下的 `files/plugins/<id>/<version>/` 目录不会显示，也不会被清理。
- 插件包只在首次启动时从 `assets/` 复制到 `files/plugins/`；`adb install -r`
  保留应用数据，因此重装 APK 不会更新插件。实测新插件需先
  `adb shell pm clear dev.pime.ime`（会清空设置），或提升插件版本号让目录名
  变化。
- 未使用 `pime_core_touch`（手写输入 UI 未实现）。
