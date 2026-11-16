# pime-android 进度

更新：2026-11-16

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
| release 签名 + R8 产出 | `app/build.gradle`、`app/proguard-rules.pro`、`app/pime-release.jks`（不入库） |
| 宿主 smoke test | 临时工程（非仓库内），见下 |
| 候选栏展开（微信输入法式 `⌄`） | `PimeImeService.java`、`tools/chains/android-pinyin.yaml` |

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


### 插件与链页

用户看完上一轮的插件页后指出“插件与链的 UI 与 UX 都太差”。原来的问题很具体：
同一批插件被列了两遍（“当前链插件”带开关 +“处理顺序”带箭头）；插件 id 当标题，
在 360dp 宽的手机上折行成 “pime-keymap-defaul / t”；任何一次开关都整页重建，
滚动位置丢失；“插件设置”分组里的卡片没有写属于哪个插件；链只能新建和删除，
不能改名，删除还只能点一个小垃圾桶；“当前链”仅靠加粗表示。

重做的原则是**一份插件只出现一次，顺序即唯一事实**：

- **链方案卡片**：每行 = 链名（去掉 `.cbor`）+「N 个插件 · 使用中 / 点按切换」。
  在用的链用勾选图标 + 加粗，不再靠字重猜；行尾 `⋮` 菜单给出「重命名 / 删除」，
  当前链的删除项置灰（服务正用着它）。卡片底部固定「新建链（以当前链为模板
  复制一份）/ 导入链」两行，并写明各自会做什么——原来这两行用导航箭头，看起来
  像会进二级页。
- **链路顺序卡片**：一行一个插件，从左到右是展开箭头、`序号 · 中文名`、
  `role · version`、上移/下移、启用开关。关掉开关 = 把插件从链里摘掉，它会落到
  下面的「未启用插件」卡片，而不是凭空消失（旧版本的顺序列表只列链内插件，
  关掉一个就再也点不回来了）。序号用强调色，插件 id 让位给中文名，副标题改放
  `role · version`，因此再长的 id 也不会折行。
- **未启用插件卡片**：已安装但不在当前链里的插件，打开开关就追加到链尾。
- **设置面板**：点插件行就地展开（浅色底 + 完整插件 id）：布尔项是开关行，
  数值项是「现在 N」+ 点击弹数值对话框（原来的 ±1 三选一太粗，改成直接输入非负
  整数，并在输入框上报错）。没有可配置项就直说。链里引用了、但 `files/plugins`
  下找不到包的插件，副标题用琥珀色标「未安装」，面板里给出解释——这类条目以前
  会直接从列表里消失。
- **插件与设置的中文名**：`pime-keymap-default` → 按键映射、`pime-pinyin-parse` →
  拼音切分、`pime-pinyin-translate` → 拼音候选、`pime-pinyin-rank` → 候选排序、
  `pime-candidates-format` → 候选排版；`max_candidates` → 最大候选数、
  `page_size` → 每页候选数。未知 id / 未知键原样显示，第三方插件不会因为查不到
  映射而丢失信息（完整 id 在展开面板里）。
- **可撤销**：启用/停用弹带「撤销」的 Snackbar，撤销是把操作前读到的整条链写回，
  顺序与各插件设置都不丢。
- **不跳位**：`rebuildDetail()` 记住 `scrollY`，重建后 `post` 回去，所以改一行
  设置不会把列表弹回顶部。
- `ChainStore` 增加 `renameChain`（重命名时若重命名的是活动链，同步更新
  `Prefs.activeChain`）与 `displayName`（去掉扩展名）。

### 插件包导入

上一轮把链的增删改补齐后，用户随即指出：“只有导入链，没有导入插件的方法”。
在此之前装插件只有两条路：随 APK 的 `assets/plugins/` 首次启动复制，或
`adb push` 到 `files/plugins/`。页面上确实缺一个“把插件包拿进来”的入口。

插件包在 core 里就是一个**目录**（`manifest.yaml` + `plugin.wasm|lua` + 资产，
可选 `SIGNATURE`），没有归档格式（`pimepack` 也是复制目录）。Android 的文件
选择器只能给单个文件或一整棵树，所以两种来源都支持：

- **导入插件包**：`OpenDocument` 选一个 `.zip`，解开后安装。zip 里允许带一层
  顶层目录（`pime-plugin-x-1.0.0/manifest.yaml` 与 `manifest.yaml` 平铺都认），
  解压时会剥掉这层前缀；`__MACOSX/`、`.DS_Store` 之类的垃圾条目跳过。
- **从文件夹导入**：`OpenDocumentTree` 选一个已是插件包的目录，递归复制。
  每次选择器都从 Download 开始（`EXTRA_INITIAL_URI`），插件包通常先落到那里。

安装逻辑收在 `PluginStore`：

- 先只读 `manifest.yaml`（zip 走一遍条目名，目录读根下那一个），校验存在
  `id` / `version`、且包里有 `plugin.wasm|lua|dll|so|dylib` 之一；不通过就在
  写盘之前用 Snackbar 说明原因（「找不到 manifest.yaml」「缺少插件模块」…）。
- 目标目录名取清单里的 `pime-plugin-<id>-<version>`，与随包分发的命名一致，
  同名同版本即可天然识别为“已安装”，不同版本可以并存。
- 复制先落到 `files/plugins/.import-<纳秒>`，整包写完再 `rename` 到目标名，
  半截的包不会被加载器看到；失败或已存在时清理 staging。
- 覆盖同名包时，连带删掉 core 自己装的那份 `<id>/<version>/`：同版本换了内容
  是新 code hash，core 会以 `CORRUPT` 拒绝覆盖，留着旧副本只会让新包加载不上。
  父目录空了也一并删掉。
- 路径安全：条目名或目录名里出现绝对路径、`\`、`:`、`.`、`..` 一律拒绝
  （core 也要求“路径不得越出包根”），单文件 64 MB、整包 512 MB 封顶。
- 清单没声明本机 ABI（`android-x64` / `android-arm64`）时，导入前弹确认框说明
  “core 可能拒绝加载”——这不是洁癖，本项目早期就踩过一次清单缺 `android-x64`
  导致模拟器上插件全部 `NOT_FOUND`。

导入完成后发 `PimeImeService.ACTION_RELOAD_PLUGINS`：正在跑的输入服务收到后
重扫 `files/plugins` 再重建当前链，所以新插件立刻可用，不需要重启输入法或
清应用数据。随后页面重建，新包出现在「未启用插件」里，打开开关即加入当前链。
复制在单线程 executor 上做，页面不会被大包卡住。

### 输入视图（键盘与候选）

在 MuMu 12 上看过 PIME 设置页与真机键盘后，用户提出“输入法的输入 UI 你好歹学学
`T:\temp\WeaveText` 和 `E:\work\pime`”：原来的键盘是硬编码深色、五行固定键位、
候选栏是一排裸文字，与两个参照项目的质感差距明显。重做大方向是对齐它们的
“键盘 = 一块自绘表面 + 一条顶栏 + 一行候选”，但只做宿主视图层，不引入 Compose，
也不碰任何输入语义。

- **配色**：新增 `KeyboardTheme`，把键盘用到的颜色收成一组令牌（板底、键面、
  功能键、强调色、候选文字、分隔线…），明暗各一套。之所以用代码而不是主题属性：
  输入视图由 `InputMethodService` 绘制，它不是 AppCompat Context，
  `AppCompatDelegate` 的夜间模式不作用于它，`?attr/…` 取不到用户选的主题。
  `KeyboardTheme.of()` 按 `Prefs.theme` 决定明暗，跟随系统时读 `uiMode`。
- **键盘表面**：`KeyboardView` 自绘，分字母页与符号页，各四行。字母页
  `qwertyuiop / asdfghjkl / ⇧z…m⌫ / 符 , 空格 . 回车`，符号页
  `1234567890 / @#¥&*()-+/ / ABC :;'"?! ⌫ / 字母 ， 空格 。 回车`。每键先画
  1.5dp 的深色底衬再画键面，形成按键的厚度；按下时去掉底衬并用按下色。
  回车与按下的 Shift 用品牌蓝，Shift/退格/回车的图形用 `Path` 描边绘制，
  不用依赖字体的 Unicode 符号。
- **行高**：键盘高度不再写死 224dp，改由屏幕高度推：竖屏
  `clamp(高 × 0.072, 42, 60)dp/行`、横屏 `clamp(高 × 0.105, 34, 48)dp/行`，
  再乘用户选的百分比。横屏因此明显更矮，不会吃掉编辑器。
- **顶栏与候选栏**：IME 视图 = 顶栏（组合串 + “键盘设置”齿轮 + “收起键盘”下箭头）
  + 候选栏 + 键盘。候选栏首选用强调色胶囊（软底 + 强调色粗体），其余为普通文字；
  因为键盘里没有数字行，就没有给候选加数字角标。齿轮 `startActivity` 打开设置页
  （服务里需 `FLAG_ACTIVITY_NEW_TASK`），下箭头走 `requestHideSelf(0)`。
- **不跳动**：`root.setGravity(BOTTOM)`，且 `minimumHeight` 只算顶栏 + 键盘，
  不预留候选栏。候选栏出现/消失时窗口向上长，按键位置不动。
- **配置变化**：`onConfigurationChanged` 重建输入视图（配色与行高都在构建时算好），
  并把组合串和当前候选原样贴回去，切明暗或转屏不会把正在输入的内容丢掉。

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
6. **对话框里的报错被对话框挡住**：新建/重命名链的校验失败用 Snackbar 提示，
   而 Snackbar 挂在 Activity 的 `root` 上，属于对话框下面的窗口，用户根本看不到
   （实测：空名字点确定，界面毫无反应）。改为在输入框上 `setError`，并把两个
   命名对话框统一成 `TextInputLayout`；数值对话框同理。
7. **对话框里的输入框没有焦点**：`AlertDialog` 默认把焦点给按钮，用户打开
   重命名/新建对话框后直接打字没有任何反应。改为 `onShow` 里
   `requestFocus()` + `SOFT_INPUT_STATE_ALWAYS_VISIBLE`，并把已有名字全选，
   重命名时可以直接覆盖输入。

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
- **输入视图实测（2026-11-13 第七轮）**：MuMu 12（900×1600 / 240dpi）竖屏下，
  点击试验输入框弹出新键盘——浅色板底、白色圆角键面带 1.5dp 底衬、蓝色回车、
  顶栏有组合串与齿轮/下箭头。依次敲 `n i h a o`（按行高算出的键心坐标），
  顶栏显示组合串 `nihao`，候选栏出现 `你好 / 您好 / 年好 / 念好 / 粘好`，首位
  `你好` 为强调色胶囊；点首位候选，输入框落字 `你好`，组合串与候选栏同时清空。
  按 `符` 切到符号页（数字行 + `@#¥&*()-+/` + `ABC :;'"?! ⌫`），底行变
  `字母 ， 空格 。 回车`。`cmd uimode night yes` 后键盘就地变深色（深板底、
  深键面、浅蓝回车与首位胶囊），且组合串与候选被完整贴回，没有丢输入；
  转横屏（1600×900）后行高自动变矮、键盘仍贴底。齿轮点击在 logcat 里看到
  `START u0 {flg=0x10000000 cmp=dev.pime.ime/.SettingsActivity}`，下箭头看到
  `ImeTracker … onRequestHide … HIDE_SOFT_INPUT_FROM_IME … onHidden`、
  `mInputShown=false`。验证完已还原竖屏与浅色。
- **插件与链页重做实测（2026-11-14）**：MuMu 12（1080×1920 / 480dpi / 360dp 宽）
  竖屏下逐项验证，每一步都对照设备上的 CBOR 链文件解码比对：
  - 冷启动（`pm clear` 后全新数据）打开页面：链 `default` 显示「5 个插件 · 使用中」
    且右侧是勾选图标；顺序卡片 5 行，行内不再折行；没有「未启用插件」卡片。
  - 展开「拼音候选」：面板给出完整 id `pime-pinyin-translate` 与「最大候选数 现在 8」；
    改成 12 后解码链文件为 `{'max_candidates': 12}`，同一插件其余设置与其它插件的
    `{'page_size': 5}` 原样保留，面板副标题同步变为「现在 12」，滚动位置不变。
  - 关掉「按键映射」：链里少一条、序号自动重排，插件出现在「未启用插件 · 1 个」；
    点 Snackbar 的「撤销」后解码结果与操作前逐项一致（顺序 + 设置全还原）。
    注意 LENGTH_LONG 的 Snackbar 约 2.7 秒，脚本里要先点撤销再截图。
  - 上移/下移：把「按键映射」从第 5 步连按两次上移，落到第 3 步，链文件顺序同步。
  - 链方案：点行切换（`active_chain` 变化 +「已切换链：…」）；`⋮ → 删除链`
    对当前链置灰、删非当前链成功且文件消失；`⋮ → 重命名` 预填并全选当前名，
    输出后文件与 `active_chain` 一起改名（`1111.cbor` → `renamed.cbor`）；
    `新建链` 空名在对话框内报「名称不能为空」且对话框不关闭，输入 `test1` 后
    生成 `test1.cbor`（内容为当前链副本）并立即变为使用中。
  - 空链：把 5 个开关全部关掉 → 链行显示「0 个插件 · 使用中」，顺序卡片显示
    「这条链还没有插件…」，5 个插件都在「未启用插件」。
  - 缺插件：用工具生成一条引用 `ghost-plugin` 的链推入 `files/chains` 并切过去，
    该行显示「parse · 9.9.9 · 未安装」（琥珀色），面板给出包缺失说明。
  - 深色：`cmd uimode night yes` 下卡片、分隔线、开关、箭头与「未安装」提示配色
    正常。
  - 回归：`pm clear` 后用 PIME 键盘实测输入，组合串与候选正常出现
    （`nijao` → 你骄傲…），点首位候选提交成功；链文件格式与宿主输入路径未受影响。
- **插件包导入实测（2026-11-14）**：用脚本造了几个测试包（复制第一方
  candidates-format 的 `plugin.wasm`，只改清单里的 `id` / `role`），推到
  `/sdcard/Download` 后从页面导入：
  - `pime-test-format.zip`（带一层顶层目录）→ `files/plugins/pime-plugin-pime-test-format-1.0.0/`
    下正好是 `manifest.yaml` + `plugin.wasm`（前缀已剥离），无 `.import-*` 残留。
  - 同一份清单的平铺 zip：识别为同名包，弹「覆盖」确认框，确认后目录内容改写、
    core 自己的 `<id>/<version>/` 旧副本被删、空掉的父目录也删掉。
  - 清单只声明 `android-arm64` 的包：导入前弹「确认导入 / 清单没有声明
    android-x64，core 可能拒绝加载这个插件」，点「继续」后确实装入，logcat 随即
    给出 `plugin pime-plugin-pime-test-arm-1.0.0: NOT_FOUND`——警告与实际一致。
  - 选一个内容是文本的 `.zip`：Snackbar「导入失败：找不到 manifest.yaml」，
    不落任何文件。
  - **热加载**：输入服务活着时（pid 不变）导入，logcat 立刻出现新包的
    `plugin loaded: …` 与 `chain rebuilt: default.cbor`，说明
    `ACTION_RELOAD_PLUGINS` 走通，不用重启输入法或 `pm clear`。
  - **文件夹导入**：`OpenDocumentTree` 从 Download 打开，Android 15 不允许直接
    选 Download 根（“无法使用此文件夹”），进子目录 `pime-folder` 后
    「使用此文件夹」→「允许」，生成 `pime-plugin-pime-test-folder-1.0.0`。
  - 导入的包按预期出现在「未启用插件」，打开开关后解码链文件确认被追加到链尾；
    首页摘要同步变成「6 个插件包 · 链 default」。
  - 首页试输入框在 PIME 不是当前输入法时，本轮仍无法在 MuMu 上自动唤起软键盘：
    `ime set` 会被 MuMu 改回微信输入法，须走系统输入法选择器点「中文 / 英文」
    才真正切到 PIME（此后 `mCurId` 与 logcat 的 `pime-core 1.0.0` 都正常）。
- **测试注意（本轮新增）**：MuMu 12 上 `ime set` 会被系统改回微信输入法
  （`com.tencent.wetype`），需要每次重设；用 `adb shell input text` 往对话框输入
  时，若软键盘是被测输入法且处于联想模式，字符会停在输入法的候选条里而不落进
  输入框（不是应用缺陷），因此本轮把三个 IME 都临时禁用后再做输入类验证。

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
- 链里可以引用没装包的插件（页面会标「未安装」），但宿主不阻止这种链被激活，
  也还没有“一键清掉缺失插件”的操作。
- 链的处理顺序只在页面内用上移/下移调整，没有拖拽排序；5 个以内的链够用，
  更长的链要点多次。
- 设置页的插件列表按“目录内直接存在 `manifest.yaml` + `plugin.wasm`”筛选；
  旧版本留下的 `files/plugins/<id>/<version>/` 目录不会显示，也不会被清理。
- 插件的中文名/设置项中文名是硬编码映射（`SettingsActivity.pluginLabel` /
  `settingLabel`），第三方插件只能显示原始 id 与键名。
- 插件包只在首次启动时从 `assets/` 复制到 `files/plugins/`；`adb install -r`
  保留应用数据，因此重装 APK 不会更新插件；随包插件的更新仍要靠
  `pm clear`（会清空设置）或提升插件版本号。第三方/新插件则可以用页面上的
  「导入插件包」装进来，不必再 `adb push`。
- 页面只能导入、不能卸载插件包（`files/plugins` 下的目录要手动删）。导入后的
  包是否有 core 能加载，宿主并不知道：只有清单缺本机 ABI 时会在导入前提醒一句，
  其它拒绝（签名、依赖、ABI major）要等 core 报 `NOT_FOUND` / `CORRUPT`，
  而这类包在「未启用插件」里看起来和正常包一样。
- 未使用 `pime_core_touch`（手写输入 UI 未实现）。

## 同步 18 个插件并复测（2026-11-15）

插件仓库把 WeaveText 能力补齐后，本轮把 Android 宿主同步到新插件集并复测：

- `assets/plugins/` 从 5 个包换成 18 个 WASM 包（拼音、拆字、特殊候选、快捷短语、
  繁简、表情、验证码、用户学习、联想、模式记忆、词库包、个人资料、模型目录）。
- `assets/chains/default.cbor` 由 `pime-public-plugin` 的
  `chains/pinyin/default.yaml` 生成（去掉 Lua 插件）。
- `CHAIN_CAPABILITIES` 增加 `store.kv`、`time`、`context.read`、
  `surrounding.read`。

MuMu 12（Android 15 / x86_64）实测通过：`nihao → 你好`、`dz → 地址`、
`rq → 2026年11月15日`、`v1234 → 1234/大写金额/中文数字`、`umumu → 林`、
`shuo → 說`、`xiexie → 謝謝` 且提交后重打仍首位。

期间插件侧修复三个只在 WAMR/Android 暴露的问题（WASM `memset/memcpy`
未定义导入、v 模式数字被当选词、u 模式被拼写纠正改写），宿主侧只需同步包与链。

### 复验：错拼纠正（2026-11-15）

同步 `pime-pinyin-parse` 1.1.2 / `pime-pinyin-translate` 1.2.1 后复验：

- `xxie`：预编辑保持 `xxie`，候选首位「謝謝」；
- `xxiex`：候选首位「謝謝」（纠正读法 xiex）；
- `nihhao`：候选首位「你好」，第三位保留「你好好」。

### 复验：纠正阈值（2026-11-15）

同步 `pime-pinyin-translate` 1.2.2 后复验 `nihhao`：候选为三个字（你好好 /
你會好 / 年後好 / 你很好），不再混入两个字的「你好」；`xxie` 仍为「謝謝」。

## 顶栏工具栏（2026-11-15）

参考 WeaveText 的顶栏：空闲时是一排等分的图标格，输入中则是组合串与候选行。
本轮把 PIME 原「设置 + 组合串 + 收起」的顶栏换成同结构的宿主工具栏，并把组合串
移到候选行左侧，保证工具栏在输入中也不跳动。

- `ImeToolbar.java`：四格等分（页切换、粘贴、设置、收起），按下有主题 ripple
  与振动反馈；页切换格在符号页时用内缩胶囊 + 强调色图标高亮，内容描述随之在
  「符号键盘 / 字母键盘」之间切换。
- `KeyboardView`：新增 `isSymbolPage()` / `togglePage()`，页变化通过
  `Listener.onPageChanged(symbols)` 回调宿主，键盘内的「符 / ABC / 字母」键与
  工具栏双向同步。
- `PimeImeService`：组合串与候选改为同一行（组合串在左、候选横向滚动在右），
  只有组合或候选存在时显示；粘贴是宿主动作，组合中先 `reset()` 并收起候选，
  再 `commitText`，避免 `commitText` 替换组合区域时留下旧候选。
- 宿主偏好：设置页改主题/高度/候选数量后广播 `ACTION_DISPLAY_CHANGED`，输入
  服务用当前 palette 重建输入视图并保留组合串与候选；候选项固定用当前视图的
  palette，避免切换主题后候选文字色与背景不同步。

实测（MuMu 12 / Android 15 / x86_64）：

- 明暗两套工具栏、横竖屏重建后图标与胶囊正常；
- `nihao` 组合中：工具栏上移一行，组合串 `nihao` 在候选行左侧，首候选「你好」
  为强调色胶囊；粘贴后组合与候选一起清掉，输入框只剩剪贴板文本；
- 点工具栏键盘格进入符号页并高亮，点键盘内「ABC」返回字母页且高亮清除；
- 齿轮打开设置页，下箭头收起键盘；空剪贴板粘贴给出提示。

横屏踩到一个渲染问题：带 **mask** 的 `RippleDrawable` 作 ImageButton 背景时，
在横屏新建的输入视图里按钮图标会上下镜像（剪贴板缺口跑到下方、下箭头变上箭头）。
去掉 mask、保留内缩胶囊作为 ripple 内容后图标方向恢复正常；该现象在设置页的
普通 ImageView 上不复现，仅 IME 视图 + 横屏出现，代码里已留注释。

## 字母键副标签与上滑键入（2026-11-15）

参考 WeaveText 的 26 键行为：三行字母键下方都画副标签，上滑该键键入副标签
字符。首行 `q–p` 为 `1–0`，第二行 `a–l` 为 `~!@#%“”*?`，第三行 `z–m` 为
`（）-_：；、`，与 WeaveText 中文模式的上滑表一致。

- `KeyboardView.Key` 增加 `hint` 与 `up`（上滑要发的键）；构造时一次建好，
  手势路径不分配。绘制时按词条高度给字母与副标签分带：字母 0.42×键高、
  副标签 0.22×键高，竖屏与横屏都不重叠。
- 上滑判定与 WeaveText 同量级：竖向位移 ≥ `max(28dp, 0.55×键高)` 且
  大于横向位移的 1.5 倍；只对带副标签的键生效。
- 手指上滑会离开键位（首行上方没有别的行），`ACTION_MOVE` 在有上滑意图时
  保留起始键的高亮，`ACTION_UP` 再决定发副标签还是正常按键；横向滑过换键的
  旧行为不变。

MuMu 12（Android 15 / x86_64）实测：`q→1`、`w→2`、`p→0`，`a→~`、`h→“`、
`z→（`；普通点按仍是字母；Shift 打开后上滑仍出副标签且 Shift 保留；60px 的
短上滑仍按字母；横屏（键高约 34–48dp）副标签与字母不重叠，上滑同样生效。
EditText 里取回的字符为 U+007E / U+201C / U+FF08，确认是清单里的码位，
不是显示近似。

## 中/英切换键（2026-11-15）

键盘此前没有中英文切换：装上拼音链后每个字母都进组合，想打英文只能靠宿主
在 core 不处理时回传。参考 WeaveText 的 `KeyCode.LANG` 补了一个「中/英」键。

- **布局**：字母页与符号页底行都在「.」和「回车」之间加 `中/英`（权重 1.4），
  空格由 4.6 收到 4.0，仍是底行最宽的键。绘制不依赖字体里的斜杠字重：`中`
  与 `英` 分开画，当前模式一侧 16sp 加粗、另一侧 12sp 用 hint 色，整组按
  键宽等比缩放，窄屏与横屏都不裁字。
- **英文是宿主模式**：`KeyboardView` 只保存 `english` 并回调
  `onLanguageChanged`；`PimeImeService.onKey` 在英文模式下不再把按键交给
  core，直接走原有的 `passThrough`（字符 `commitText`、退格
  `deleteSurroundingText`、回车发 `KeyEvent`），所以不会出现拼音组合与候选。
  Shift 的大小写仍在宿主侧生效。
- **切换时保留已打的字**：组合中切到英文，先把组合串按原样提交（与 WeaveText
  `commitRaw()` 一致），再清空组合与候选；切回中文从空组合开始。
- **reset 契约（插件侧修复）**：`pime_core_reset` 只是向链发一个 `reset`
  事件，而随包的 parse 插件都不消费该事件，缓冲区仍留在会话的插件 state 里。
  实测直接 `reset()` 后切回中文再打 `nihao`，组合串变成 `hnihao`（旧的 `h`
  复活）。根因在插件，已在 `pime-public-plugin` 修复：五个 parse 插件在
  `reset` 时清空缓冲并发 `set_composition("")` + `hide_candidates`，
  shuangpin 丢弃半音节，candidates-format 清页码；同时把契约写进
  `docs/plugin.md`。Android 侧同步 `pinyin-parse 1.1.3` /
  `candidates-format 1.3.2` 并更新链 CBOR 的版本字段，宿主改回
  `core.reset()`，不再用“重建会话”绕过。
- **偏好**：模式写入 `SharedPreferences`（`english`），输入服务重建视图时
  读取，所以重启输入法后仍保持上次选择。

MuMu 12（Android 15 / x86_64）实测：中文组合 `h` 时点「中/英」，输入框留下
普通文本 `h`、候选清空、键上 `英` 变粗；英文模式连打 `nihao` 得到字面
`hnihao`，无组合串与候选；切回中文再打 `nihao`，组合串恰为 `nihao`、候选首位
「你好」，旧 `h` 未复活（日志显示 `pinyin-parse 1.1.3` 与
`candidates-format 1.3.2` 已加载、链 `default.cbor` 激活）。
`shared_prefs/pime.xml` 中 `english` 随点击在 `true`/`false` 间落盘；
符号页底行同样有该键。

## 拼音行独立于候选行之上（2026-11-15）

用户提出“拼音应该在候选词的上方而非左方”。此前 `candidateRow` 是横向
`LinearLayout`：组合串在左（最宽 45% 屏宽、从头部截断），候选滚动条占剩余
宽度。改为纵向排布：

- `candidateRow` 改 `VERTICAL`；`compositionView` 独占上方一行（30dp、全宽、
  左对齐、尾部截断），候选 `HorizontalScrollView` 在下方保持 48dp。
- 两层各自固定高度，容器 `WRAP_CONTENT`：无候选时只显示拼音行，无拼音时
  只显示候选行，两者都没有时整块 `GONE`；`root` 仍靠底，键盘位置不动。
- MuMu 12（1080×1920）试输入框实测：输入 `nh` 时拼音在上一行，候选
  你好/你會/女孩/男孩/年後 在下一行且未被挤占；横屏布局同样正常。

## 拼音分隔选项（2026-11-15）

用户建议给候选上方的拼音行加一个设置项，选择用撇号还是空格分隔音节。

- **音节边界本来就有**：pinyin-parse 的 `set_composition` 命令带 `segments`
  （每个音节的起止），core 的 `pime_composition` 也原样带出；此前 JNI 只转发
  了文本和光标。这次把 `starts`/`ends` 按 UTF-16 换算后一并传给 Java。
- **只改显示**：`PimeImeService` 只在组合串行按
  `Prefs.pinyinSeparator` 重排音节（`'` 或空格）；编辑器里的组合文本仍是链
  给的原文，分隔符不会进入实际输入，光标映射不受影响。单音节或 v-mode
  单段时原样显示。
- **设置入口**：「键盘与候选 → 显示 → 拼音分隔」，可选“撇号（'）”或
  “空格”，默认撇号；改动通过 `ACTION_DISPLAY_CHANGED` 立即刷新正在显示的
  键盘，无需重开。
- **实测**：两档分别输入 `nihao`，上行显示 `ni'hao` 与 `ni hao`，编辑器里
  仍是 `nihao`，候选不受影响；偏好落盘为 `pinyin_separator`。

## 英文候选（2026-11-15）

用户问“为什么英文目前没有候选词”，要求该补插件就补插件。根因有两条：宿主在
英文模式下**刻意绕过插件链**（上一节的设计），而随包的 18 个插件与 `default.cbor`
里也**没有英文链**。两处都要动。

### 宿主：中/英改为两代切换

- `buildGenerations()` 在 `onCreate` 与 `ACTION_RELOAD_PLUGINS` 时各构建一代：
  中文代用 `files/chains/<活动链>`，英文代用随包复制的
  `files/chains/english.cbor`。中/英键只调 `activateForLanguage()`，输入中切换
  不重建链；切换完成后 `pime_generation_retire` 旧的另一代。
- 会话与插件 state 不随代切换丢失（core 的会话按插件别名保留状态），但英文
  组合中的切回中文仍按原规则先提交原码、再 `reset`。
- 英文链的数据目录与中文链相同；`ChainStore.isReservedName()` 把
  `english.cbor` 标为保留名，设置页不列它，新建/重命名/导入时拒绝占用。
- `english.cbor` 由 `tools/chains/android-english.yaml` 生成
  （`python pime-public-plugin/tools/chainc ... -o app/src/main/assets/chains/english.cbor`）：
  keymap-default 1.2.0 → english-parse 1.0.1 → english-translate 1.0.1 →
  candidates-format 1.3.2（`page_size: 9`）。

### 宿主：空格与标点的英文语义

插件只负责“原文在前、补全在后”，提交语义按 WeaveText 在宿主薄层补齐：
空格键走链（链提交原码），`status == 1` 时再 `commitText(" ")`；点候选只提交
词本身，不加空格；字母/撇号/1–9 以外的字符先把原码提交、`reset`、再插入该
字符，不补空格（1–9 仍交给链做候选选择）。

### 插件：`pime-english-translate` 1.0.1

- **漏词**：旧实现按词表顺序把匹配塞进最多 N 个槽位，`hel` 的槽位被
  `held`/`helen`/… 占满，`hello` 根本没进来，频次排序等于没起作用。改为
  维持 top-N（新候选只看是否优于当前最差项），补全上限 `completion_limit`
  改到排序之后只对补全生效，原码与精确词条永不被截。
- **词表**：starter 90 行换成 `tools/english_dict` 从 rime-wanxiang
  `en.dict.yaml` 生成的 36,345 条（758,967 字节，插件 1 MB 上限内）。
  键 = 小写 + 去撇号（匹配时同样去撇号，`dont` 补出 `don't`）；text 保留原
  拼写（`GitHub`/`WiFi`/`API`）；只收单 token，频次 ≥ 50，另保留带大写或
  撇号的条目。
- `data/english/english.dict`、包内 `assets/english.dict`、`latin.lock.yaml`
  的版本字段同步；18 个英文用例全部通过。

### 验证（MuMu 12 / Android 15 / x86_64）

- 输入 `hel`：候选 `hel / help / held / helped / hell`；`dont` 出现 `don't`。
- 空格：提交原码 `hel` 并插入一个空格；点候选 `help` 只落 `help`，无空格；
  句点：先提交原码再落 `.`，无空格。
- 中/英来回切换正常（logcat `active generation: english/chinese`），重启输入法
  后仍停在英文；`shared_prefs/pime.xml` 的 `english` 同步。
- 设置页「插件与链」只列 `default`（18 个插件），`english.cbor` 不出现。

### 观察到但未修

`pime-predict-context-1.1.0` 在 `onStartInput` 时日志报 `context: NOT_FOUND`：
该包清单声明了 `continuation.tsv` / `collocations.tsv`，但包里没有这两个资产。
它在中文链末尾，失败只跳过自身，不影响候选。属插件包内容问题，与本轮无关。

## 插件描述（2026-11-15）

用户指出设置页的插件列表缺少“插件描述”。复查后确认问题只在 Android 外壳：
`pime-public-plugin` 的 17 个源清单与随包清单早已有 `description`（首行，
如“全拼解析：音节切分、自动纠错…”），`tools/check_plugins` 也已把它写进
插件索引；但 `SettingsActivity` 的 `manifestInfo` 只读了 id/version/role，
页面上只显示“role · version”。

### 改动（`SettingsActivity.java`）

- `manifestInfo` 多读 `description`（去掉一层 YAML 引号），返回
  `{id, version, role, description}`；构建插件页时按 id 建一次索引，
  避免每行重复读清单。
- 链内插件行：`role · version` 下新增描述行，12sp、最多两行、超出省略；
  行无障碍描述改用新字符串 `plugin_row_desc_described`，把描述一并读出。
- 未启用插件行同样显示描述；未安装的链内插件不显示（没有清单可读）。
- 展开面板顶部显示完整描述，下面是原来的插件 id 与链内设置。

描述是随包/导入包自带的清单字段，宿主不再维护第二份文案；插件仓库无需
改动。

### 验证

- `./gradlew :app:compileDebugJavaWithJavac` 与 `assembleDebug` 通过。
- MuMu 12（Android 15 / x86_64）实测「插件与链」页：链路顺序里 18 个插件
  都在 `role · version` 下显示描述（如“默认键位：把控制键映射为链内动作，
  文本键放行”），未启用区的 `pime-english-parse` / `pime-english-translate`
  同样显示；展开插件后描述以完整两行出现在 id 上方。

## 中文候选不可用 / 内置插件包资产（2026-11-15）

用户问“当前中文候选词质量为什么很低”。实测发现不是排序差，而是中文链在第一个
translate 插件就失败：按 `a` 时 logcat 是 `key 97: NOT_FOUND`，字段里只落下原字母，
没有组合串和候选。

### 根因

- `pime-pinyin-translate 1.2.2` 的 manifest 声明
  `assets: [pinyin.dict, pinyin.collocations]`，但插件源码的
  `CMakeLists.txt` 只打 `ASSETS assets/pinyin.dict`，构建出的包缺
  `pinyin.collocations`；Android 仓库只是原样镜像了这个残缺包。
- 插件在 `collocation_weight != 0`（默认 500）时 fail-closed，缺资产直接
  `PIME_NOT_FOUND`；core 的 `run_event` 遇到任一插件错误会中断整条链。
- `pime-predict-context 1.1.0` 同样声明了 `continuation.tsv` /
  `collocations.tsv`，包里却没有 `assets/`（它容错，只让联想空转）。
- `PimeImeService.installBundled()` 只在目标目录不存在时复制，APK 升级不会
  更新 `files/plugins`，所以即使包修好了，老安装也看不到。

### 改动

- `pime-public-plugin`：两个插件 CMake 的 `ASSETS` 补齐；重出包并把
  `pinyin-translate 1.2.2` / `predict-context 1.1.0` 整个包目录同步到
  `app/src/main/assets/plugins/`。
- APK 更新时刷新内置插件包：`installBundled()` 用 APK `lastUpdateTime` 做
  同步戳（`Prefs.bundledStamp`），变化时删除旧包与 core 的
  `<id>/<version>` 副本后重拷；链文件仍只在缺失时复制，保留用户改动。
- `app/build.gradle` 新增 `checkBundledPluginAssets`，在 `preBuild` 前校验
  每个内置包 manifest 声明的资产确实存在；缺失时构建失败。
- `pime-hans-hant` 的 `enabled` 默认改为 `false`：它是可选的简→繁转换，
  默认开启会把默认链的简体候选整体改写成繁体（`dianhua` → 電話）。

### 验证

- `checkBundledPluginAssets` 通过；临时移走 `pinyin.collocations` 时构建
  按预期失败并指出具体路径。
- MuMu 12（Android 15 / x86_64）：`nihao` → 你好/您好/年好…；
  `dianhua` → 电话（简体）；logcat 不再出现 `NOT_FOUND`；设备上
  `files/plugins/pime-pinyin-translate/1.2.2/assets/` 已含
  `pinyin.collocations`，`pime-predict-context/1.1.0/assets/` 已含两个 TSV。

## 中英混输与候选翻页（2026-11-15）

用户：“中英混合输入现在缺乏这个能力，比如：我在中文状态下输入明显是英文的东西，
比如 `goo`，就要意识到 good、google…，当然，这并不意味着没有中文候选。”

### 链

- `tools/chains/android-pinyin.yaml` 加入 `pime-english-translate 1.0.2`
  （`max_candidates: 16`，排在所有中文候选源之后），同步
  `pinyin-parse 1.1.4`（新增 `complete`）、`pinyin-rank 1.2.1`（合并视图不
  学习）、`candidates-format 1.3.3`（英文合并视图优先于中文源）。
  重出 `default.cbor` / `english.cbor`，打包 1.1.4 / 1.0.2 / 1.2.1 / 1.3.3。

### 宿主

- 内置**链**也随 APK 升级刷新：`Prefs.chainBaseline` 记录上次写入的 SHA-256，
  只在文件仍等于 baseline（用户没编辑过）时替换；从没有 baseline 的旧版本升级
  时先写一份 `<name>.cbor.bak` 再替换。插件包仍按 `lastUpdateTime` 整包重拷。
- 候选栏 `CandidateStrip`：页宽于屏幕时横向滚动；滚到头、或本来就放得下时，
  横向滑动翻页（给链发 `PageDown/PageUp = 34/33`）。此前链每页只给 5 条，
  第 6 条之后（包括合并视图里的中文）永远看不到。
- 候选点击从 `candidate {index}` 改为数字选词：翻页后窗口里的下标只是页内
  序号，而链的 `digit` 动作会按 format 自己的页码换算绝对下标。

### 实测（MuMu 12 / Android 15 / x86_64）

- `goo` → 第一页 `goo good goods 够 工`；左滑 → `共 购 公 供 功`；再左滑进入
  其余英文补全（`goodbye goose goody…`）；右滑回上一页。
- 点第 1 页 `good` 提交 `good`；翻到第 2 页点 `共` 提交 `共`（不再误提交 `goo`）。
- 空格提交原码 `goo`，与英文链“原码优先”一致。
- 纯拼音 `nihao` 等不受影响（`complete=true` 时不查英文词表）。
- 插件侧 281 条用例全过、conformance ok；APK 升级后 `files/chains/default.cbor`
  自动换成新链，无需清除应用数据。

## 候选点选后的组合状态残留（2026-11-15）

用户反馈：中文状态输入 `goog`，候选里出现 `Google`，点选提交后输入法仍把
`goog` 当作正在输入的码串，再按 `l` 会接着得到 `googl`。

### 根因

- 上一轮为了翻页后的正确选词，候选点击从 `pime_core_candidate`（事件类型
  `candidate`）改成发数字键（keymap 映射为 `action=digit`，format 按页内偏移
  提交）。
- parse 插件只在 `candidate` / 空格 / 回车这些“提交阶段”清空自己的 `state.buf`；
  数字键走到 parse 时 `append()` 不接受数字，缓冲原样保留，而提交发生在下游的
  candidates-format。旧的 `candidate` 事件会把缓冲清掉，换用数字键后这条语义丢了。
- 于是提交虽然成功，链的会话状态里仍是提交前的码串，下一次按键在旧码上继续拼接。
  中文候选、英文候选、符号页数字选词都受影响。

### 改动（`PimeImeService.java`）

- `onCommit` 在链按键调用期间置 `commitSeen`；`selectCandidate` 与 `onKey` 的
  数字键路径在按键返回后检查它。
- 只有确实发生提交时才发 `reset()` 再 `pump()`：`reset` 是链契约里的“组合结束/
  宿主取消组合”，parse 清缓冲、format 清页码；v-mode 数字、页码越界等没有提交的
  情况不会被误清。
- 不在 `onCommit` 里直接 reset：sink 回调发生在 core 的 `run_event` 内，重入
  core 不安全，所以放到按键返回之后。

### 实测（MuMu 12 / Android 15 / x86_64）

- 中文混输：`goog` → 候选 `goog / Google / 够欧冠 / 工欧冠`，点 `Google` 提交
  后拼音行与候选栏清空；再按 `l`，拼音行为 `l`（不再是 `googl`）。
- 中文：`nihao` → 点 `你好` → 按 `n`，拼音行为 `n`。
- 英文：`hel` → 点 `help` → 按 `p`，拼音行为 `p`。
- 数字选词：英文组合 `p` 时切符号页按 `2` 提交 `people`，返回字母页按 `p`，
  拼音行为 `p`。

## 中文删除键后的联想残留（2026-11-15）

用户反馈：中文输入下按删除键，联想候选行为异常。

### 复现

- `nihao` 提交 `你好` 后按一次退格，编辑框只剩 `你`，候选栏仍按 `你好` 出联想；
  再按一次退格把编辑框清空，候选栏继续显示 `吗 / 啊 / 的`。
- 根因：`pime-predict-context` 在收不到宿主 surrounding 时退回
  `pime-learn-user-dict/last`（最后提交的词）。退格改的是编辑器文本，链并不
  知道它已经变短，联想便针对已不存在的文本继续出词。

### 改动

- JNI/Java 补上 core 既有的 `pime_core_surrounding`（`PimeCore.surrounding`）。
- `PimeImeService` 新增 `refreshSurrounding()`：从 `InputConnection` 读光标前后
  文本，先收起旧联想，再上报真实上下文；左文为空时只收起（没有上下文可联想）。
- 调用点：`onStartInputView`（进入输入框即按实际文本给联想）、链未处理的透传键
  之后（退格/标点/回车等）、粘贴之后。密码字段不上报文本。
- `reportContext` 的 host caps 增加 `HOST_SURROUNDING`。

### 实测（MuMu 12 / Android 15 / x86_64）

- `nihao` → `你好` 后联想 `吗 / 啊 / 的`；退格一次文本变 `你`，联想改为
  `的 / 们`；再退格清空，联想栏收起。
- 空输入框进入时不再显示上一次输入遗留的联想；组合内退格、标点与英文链行为
  不变，logcat 无 `surrounding` 报错。

## 联想候选点按无效（2026-11-16）

修退格残留时发现：没有组合串时，点候选栏里的联想词没有任何反应。

### 根因

- 候选点击统一走数字键（`selectCandidate` 发 `1`–`9`），由 candidates-format
  按页内偏移提交。这条路径只在有组合时成立。
- 联想候选是 predict 插件在 format 之后直接 `show_candidates` 出来的，链里没有
  可提交的组合；数字键到达 format 时页码/候选都不存在，于是什么都不发生。

### 改动（`PimeImeService.java`）

- `selectCandidate` 按组合状态分流：有组合仍发数字键；无组合转
  `commitPrediction`。
- `commitPrediction` 由宿主直接 `commitText` 插入联想词（先收起候选栏），随后
  `refreshSurrounding()` 按新光标上下文重新取联想。
- 取舍：直接提交绕过了 candidates-format / learn-user-dict，选中过的联想词不会
  进入用户词库（core 目前没有“提交非候选文本”的链路接口）。

### 实测（MuMu 12 / Android 15 / x86_64）

- `nihao` → `你好`，退格到 `你`（联想 `的 / 们`），点 `的` 文本变 `你的`，
  联想更新为 `话 / 是`；再退格两次清空后候选栏收起。
- 有组合时点候选仍是数字选词（含翻页后第二页）；英文链与符号页数字选词不变。
- 期间误触设置页开关导致 default 链少了 parse/rank 三项，已用 assets 的
  `default.cbor` 恢复，链回到 19 个插件。

## 中文前缀 nih 混入异常英文（2026-11-16）

用户反馈：中文输入 `nih`，候选栏出现 `big / air / ah` 等无关英文。

### 根因

- 中文链的 `pime-english-translate` 只在 `pinyin-parse/complete == false` 时
  混入英文，但“不是完整拼音”也包括 `nih`（`ni` + 下一音节声母）、`zhongg`
  这类正常途中输入。
- 英文词表没有 `nih` 前缀，插件回退到拼写建议，按编辑距离给出 `big`/`air`，
  并与原码一起排到中文候选前面。

### 改动

- `pime-english-translate 1.0.3`：中文链只做前缀补全，不再输出拼写建议；
  英文侧没有真实新增时输出空数组并返回 `handled=false`。独立英文链的拼写建议
  保留。
- 同步插件包目录 `pime-plugin-pime-english-translate-1.0.3`（旧 1.0.2 删除），
  `tools/chains/android-pinyin.yaml` / `android-english.yaml` 版本改为 1.0.3，
  重出 `default.cbor` / `english.cbor`。

### 验证

- 插件侧 283 条用例全过、`check_plugins` 与 conformance ok。
- `assembleDebug` 通过（含 `checkBundledPluginAssets`）。
- MuMu 12（Android 15 / x86_64）：`nih` → 你好 / 你会 / 年后 / 你还 / 您好，
  无英文候选；`goo` 仍是 goo/good/goods/够/工；`nihao` → 你好；
  英文链 `hel` → hel/help/held/helped/hell。

## 语音输入：原生 sherpa-onnx 插件（2026-11-16）

WeaveText 的离线 ASR 之前只有 WASM 占位插件，pime 也没有宿主音频路径。现在
把它做成可信原生插件，宿主只负责权限、采集和链路切换。

### 插件（pime-public-plugin/plugins/recognize/pime-speech-sherpa）

- `runtime: native`、`trusted: true`、`role: recognize`；能力
  `audio.capture / audio.pcm / asset.read / log`，平台
  `android-arm64 / android-x64 / windows-x64`。
- `voice { action: 0 start | 1 stop | 2 cancel }`：`start` 时才 `dlopen`
  `libonnxruntime.so` / `libsherpa-onnx-c-api.so` 并创建 streaming
  recognizer；工作线程用 `audio.capture` 的 `start/read/stop` 取 100 ms
  16 kHz mono s16le PCM，`accept_waveform` + 解码，增量结果用
  `host.emit(set_composition)` 回写。端点（2.4 s / 1.2 s / 20 s）切句后
  已定文字累积到本轮文本。`stop` 补尾音并返回 `commit`，`cancel` 丢弃。
- 模型/运行时由宿主提供（`model_dir` / `runtime_dir` 设置）；插件不联网。
- 开发回放：`wav_file` 设置可用 16 kHz wav 代替麦克风；Android 宿主只在
  debug 构建、且 `files/voice-test.wav` 存在时传入。
- `tools/check_plugins` 的沙箱禁用词（`dlopen` / `windows.h` 等）改为只检查
  wasm：native 插件本就是可信逃生舱。README / CHANGELOG / 用例补齐，
  `check_plugins` 0 error。

### 宿主（pime-android）

- 新增 `pime_core_voice(session, PIME_VOICE_START/STOP/CANCEL)`：
  core 把 `voice` 事件派发到当前代；语音代是独立的一条链，只含
  speech 插件，录音期间切过去，结束切回中文/英文代，按键路径不受影响。
- JNI：`nativeVoice`、`nativeAudioIn`（构造 `pime_audio_frame` 推进 core
  音频环）、`onNativeWake`（插件线程请求 owner 线程 `pump`）和
  `onNativeLog`（core/插件日志进 logcat）。debug 构建 core 日志级别为
  DEBUG，秒级打印采集电平。
- `PimeImeService`：工具栏麦克风；`RECORD_AUDIO` 缺失时启动透明
  `VoicePermissionActivity`，授权后广播回到 IME 并直接开录；
  `VoiceModels` 在首次使用时就近下载模型（镜像 + sha256 + bzip2/tar）。
- 插件 `.so` 从 `nativeLibraryDir` 复制到插件包并 `setReadOnly()`，
  满足 Android 10+ 对 `dlopen` 可执行代码的要求。

### 验证（MuMu 12 / Android 15 / x86_64）

- 插件包加载、代构建设置 `runtime_dir/model_dir` 生效、识别器就绪。
- 采集：`AudioRecord(VOICE_RECOGNITION, 16000, MONO, PCM_16BIT)` 启动，
  `pime_core_audio_in` 持续返回 OK，插件 `audio.capture read` 拿到
  3200 字节/帧；MuMu 无宿主麦克风时电平为 0，无候选，属预期。
- 回放 `0.wav`：预编辑实时输出「对我做了介绍那么我想说的是呢大家如果」，
  麦克风按钮高亮，停止后提交 81 字，代切回中文，键盘继续可输入。
- 按需下载：删掉模型目录后点麦克风，直连 GitHub 15 s 超时，自动改用
  `ghproxy.net` 镜像（约 1 分钟下完 21,264,113 字节），sha256 通过后
  bzip2/tar 只解出 `model.int8.onnx`（26,342,340）与 `tokens.txt`（13,366），
  缓存归档删除；完成回调直接开录，`recognizer ready` → `listening` →
  采集线程运行，停止后 0 字提交。模型就绪后再点麦克风不再下载。
- 注意：验证时临时禁用了 Sogou/百度输入法（它们会抢回默认），结束后恢复。

## 语音人口改为长按空格（2026-11-16）

麦克风按钮占着工具栏一格，但语音是"按住说话"的交互，按钮的"点一下开始、
再点一下结束"并不贴合。现在语音只在空格键上：长按开麦，松手提交，工具栏
回到四个宿主动作。

### 宿主（pime-android）

- `KeyboardView`：按下空格键按系统 `ViewConfiguration.getLongPressTimeout()`
  起定时器；超时后回调 `onVoiceHold()`，宿主开麦返回 `true` 才进入"语音
  按住"状态，否则这次长按退化成普通空格。`ACTION_UP` 回调
  `onVoiceRelease(false)`（提交），`ACTION_CANCEL` / 视图被移除回调
  `onVoiceRelease(true)`（丢弃）。长按期间 `ACTION_MOVE` 直接吞掉，
  滑出键区也不会落到别的键上；`clearPressed` 与 `onDetachedFromWindow`
  都会撤掉定时器。
- 视觉：说话手势不靠文字。`drawSpace` 负责两种形态：空闲态是一条空白宽栏
  加 `drawMic` 画的静音色小麦克风（宽栏本身就是空格）；按住期间整键填强调
  色，键面中央只留一个大麦克风（`onAccent` 描边），因为录音指示器原来挂在
  工具栏麦克风上。麦克风与 Shift / 回车 / 退格同为一套描边字形（胶囊头 +
  托架弧 + 支撑 + 底座），笔画宽度随半高缩放（下限 `dp(1)`、比例 0.22）。
  长按判定成功补一次 `LONG_PRESS` 触感，与普通按键的 `KEYBOARD_TAP` 区分。
- `KeyboardView.Listener` 新增 `onVoiceHold()` / `onVoiceRelease(cancelled)`，
  `isSwipeUp` 额外要求 `key.up != null`，避免将来给空格加副标签时把上滑
  当成空输出。
- `PimeImeService`：`toggleVoice()` 拆成 `onVoiceHold()`（密码框、缺
  `RECORD_AUDIO`、缺插件、缺模型各自提示后返回 `false`）与
  `onVoiceRelease()`；`startVoice()` 改为返回 `boolean`。缺模型时只发起
  下载并提示，下载完成回调改为提示「模型已就绪，长按空格说话」——手指早就
  离开屏幕了，不能替用户开麦。`ACTION_VOICE_READY`（授权后广播）同样只
  提示，不再自动开录；`voiceActive` 仍留着，第二次长按会先关掉上一个没正常
  结束的回合。
- `ImeToolbar`：删掉 `TOOL_VOICE` 与 `setVoiceActive`，只剩页切换、粘贴、
  设置、收起；`ic_mic.xml` 与 `ime_action_voice*` 字符串一并删除，新增
  `voice_ready` / `voice_permission_granted`。空格键上的手势提示改用字形后
  `space_voice_hint` / `space_voice_release` 两条文案也删掉了，键面上不再出现
  提示性文字。

### 验证（MuMu 12 / Android 15 / API 35 / x86_64）

- `assembleDebug` 通过（只有 `performHapticFeedback` 既有的 deprecated 提示）。
- 先把默认输入法切到 PIME（`ime set`；Sogou 会抢回默认，需重新设置），
  打开设置页试验输入框拉起键盘，IME 窗口 frame 为 `[0,1115]-[1080,1920]`，
  空格键中心约 `(436,1830)`。
- `adb shell input swipe 436 1830 436 1830 1500`（同位长按 1.5 s）：
  logcat 出 `speech-sherpa: recognizer ready` → `listening, session=1`，
  松手后 `stopped, 0 chars`（MuMu 无宿主麦克风，采集到 0 电平属预期），
  之后键盘立即回到中文代；`uiautomator dump` 里试验输入框文本仍为空，
  说明长按没有多打一个空格。
- 同一位置短按（`input tap`）：输入框内容变成 `' '`，短按仍是空格。
- 空格键字形用截图做像素级核对（不看图也能判断形态）：空闲态键面为
  `keyFunc`、底部窄带出现静音色（`theme.hint`）字形，包围盒 24×34 px，位图
  呈「胶囊轮廓 + 托架弧 + 支撑 + 底座」；后台跑 4 s 同位长按、1.2 s 时截屏，
  键面主色变成 `accentPressed`（`0xFF2559D0`），键面中央是 `onAccent` 白色
  麦克风（包围盒约 40×54 px），没有残留文字像素。
- 未在物理设备上验证识别出的整句文本，但 `VOICE_STOP → commit` 这段
  （插件 `stop` 补尾音并 `commit`）本次未改动，仍按上一节回放用例覆盖。

## 键面去文字：功能键改用字形，中/英重做（2026-11-16）

空格键去掉文字之后，底行还剩「符」「字母」「ABC」「中/英」四处文字标签，
字号与描边图标混在一起很杂。功能键统一改成自绘字形；中/英是唯一保留文字
的键——那两个汉字本身就是要表达的内容，问题出在排版上，所以重做设计。

### 绘制（`KeyboardView`）

- `drawSpace()`：空白宽栏 + 静音麦克风；按住时整键强调色 + 大麦克风。
- `drawPageGlyph()`：去符号页画 `#`（两竖两横的描边），回字母页画 `A`
  （两撇 + 横杠）。两页的第三个键位与底行键位共用同一字形，不再区分
  「符 / 字母 / ABC」。
- `drawLanguage()` 重做：中/英是一枚左右两格的开关——「中」在左、「英」在
  右，中间一道 1dp 半透明竖线（`theme.hint` 叠 0x70 alpha，用 `keyPaint`
  填充，不动共享的描边 paint）。当前语言那侧 `theme.keyFuncText` + 加粗，
  另一侧压成 `theme.hint` 常规字重；两字同字号共基线，先按两边都取较宽的
  字宽算总宽，窄屏时用 `canvas.scale` 把整块（含分隔线）等比缩小，不截字。
  第一版把中/英换成地球图标、用强调色表示 English，实测下来语言状态不如
  文字直观，改回文字排版。
- 这些字形与既有的 Shift 上箭头、退格带叉箭头、回车拐弯箭头共用
  `glyphPaint`（圆头描边），笔画宽度按 2.1dp / 1.7dp 两档，随键高不变形。
- `Key` 上的 `label` 字段对这几类键不再参与绘制（`drawSpace` 忽略 label，
  页面键与语言键走各自的 `draw*`），label 只留给字符键与副标签。

### 验证（MuMu 12 / Android 15 / x86_64）

- 空闲态底行逐键截图核对（按精确颜色取像素，不看图也能判断形态）：
  - 符号页键：`#` 字形 55×55 px，键面 `keyFunc`；
  - 空格键：文字色像素 **0**，只有 267 px 的静音色（`theme.hint`）麦克风
    24×33 px，居中；
  - 中/英键：整块 103×41 px，左半全是加粗 `keyFuncText`（中），右半全是
    `theme.hint`（英），中间 3 px 竖线（混合色 117,124,137）；
  - 回车键：`accent` 键面 + `onAccent` 拐弯箭头（既有行为不变）。
- 点符号页键后截图：底行键变 `A` 字形 46×50 px；第三行 `ABC` 键同理。
- 点中/英键：logcat `active generation: english`，同一块像素里加粗色
  （`keyFuncText`）697 px 全部落在右半（英），`theme.hint` 落在左半（中）；
  再点回 `chinese`，强调侧与颜色复原。
- 语音手势未回归：短按空格仍输入一个空格，长按仍只开识别、松手不多打字符。

## 候选栏展开：微信输入法式 `⌄`（2026-11-16）

微信键盘在候选行右端常驻一个小箭头：候选只有一行时它朝下（`⌄`），点开后
候选区向上展开成多行网格，键盘位置不动；点任一候选直接上屏并收起。这一轮把
这个机制搬进宿主。

### 机制（先看清楚了再动手）

在 MuMu 里用微信输入法 4.0.0 实测（`com.tencent.wetype`，悬浮候选条状态）：

- 折叠：`ni'hao` + `1 你好 2 🤞 3 你号 4 你要 ⌄`，箭头在行末；
- 点箭头：候选区展开成 5 列多行（首行大字、含表情，往下依次排），键盘不动；
- 点展开面板里的候选：直接提交，面板收起。

注意这里的 Arrow 与「工具栏」无关：微信的工具栏是另一套（`定制工具栏`、可固定
语音/手写找字等图标）。箭头只管候选本身的展开，所以宿主这边也不需要新增宿主
动作，只加一个视图状态。

### 实现（`PimeImeService`）

- 候选行拆成 `candidateLine`（水平）：横向滚动的 `CandidateStrip` 占权重 1，
  右端一格是 `expandButton`（`ic_expand_more`，强调色胶囊 + ripple）。箭头只在
  有候选时出现，跟着 `onCandidates` / `hideCandidates` 走。
- 展开面板 `candidatePanel` 挂在候选行下面、键盘上面：5 列等宽的
  `candidateGridItem`，每格单行省略，首项沿用强调胶囊。行数 = ⌈候选数 / 5⌉，
  因为链一页最多 9 个，最多两行。
- 展开时**隐藏横向 strip**、`candidateLine` 压到 28dp 只留箭头（右对齐，箭头
  转 180° 变 `⌃`）。不隐藏 strip 的话同一页候选会上下各显示一遍。
- 收起时机：再点箭头、选完候选（`hideCandidates` 会把 `candidatesExpanded` 清
  掉）、组合结束。翻页（左右滑）时若面板开着，`onCandidates` 会用新页重建网格。

### 选词为什么只走数字键

一开始写过「按全局索引选第 10 个以后的候选」（`pime_core_candidate` 的 index
是全局的），需要宿主自己记住 format 插件的页码；查插件才发现它的 `page_size`
设置上限就是 9（`pime_init` 里 `size <= 9`），一页最多 9 个候选，正好是数字键
1–9 的覆盖范围。所以那段页码记账是多余的、而且猜错就会选错词，删掉：网格里
每个格子的槽位一定有数字可点。

### 链的一页候选数：5 → 9

`pime-candidates-format` 的 `page_size` 原本是 5，网格永远只有一行，"展开"等于
没有。改成 9（`tools/chains/android-pinyin.yaml`，用
`python pime-public-plugin/tools/chainc tools/chains/android-pinyin.yaml -o app/src/main/assets/chains/default.cbor`
重新生成），与英文链一致，展开后才有两行。rank 插件的 `page_size` 是它自己的
用户词分页，不裁候选，未动。

### 验证（MuMu 12 / Android 15 / x86_64）

本机截图（`screenshots/` 未入库）：`expand_{collapsed,grid,dark,english}.png`

- `shi` 折叠态显示 6 个候选 + 行末 `⌄`；点箭头 → 两行网格
  （是 时 十 事 拾 / 使 市 式），箭头变 `⌃`，键盘 q 行仍在 y≈1300 不动。
- 点网格第二行「市」（第 7 个，数字键 7）→ 上屏「市」，面板收起，联想刷新为
  「场」，箭头回到 `⌄`。
- 长词候选（`ni'hao'ni'hao` → 「你搞你好」）在 5 列里按 4 字宽截断成「你搞…」，
  属预期。
- 深色（`cmd uimode night yes`）下网格、强调胶囊、箭头配色与键盘一致。
- 英文模式（`hel`）展开为 hel/help/held/helped/hell + hello/helping/helps 两行，
  说明混合视图与英文链同样适用。

### 横屏实测（2026-11-16，MuMu 12 / 1080×1920 → 1920×1080）

本机截图：`expand_landscape_{strip,grid,picked,text}.png`。

- 横屏 `shi` 的 8 个候选在**折叠态就能一行排下**（是 时 十 事 拾 使 市 式），
  strip 不出现横向溢出；行末 `⌄` 仍在。这一点和竖屏不同，收起状态下“展开”读
  起来会像可选装饰，属预期（候选多于一页时才有信息增量）。
- 点箭头 → 仍是 5 列两行等宽网格（是 时 十 事 拾 / 使 市 式），首项强调
  胶囊，箭头转 `⌃`，键盘 q 行 y≈590 未动；换行处空格子只占位不绘制。
- 点第二行第 2 格「市」（第 7 个，数字键 7）→ 面板收起、联想刷新为「场」，
  收起键盘后输入框内容为 `heln.市`，说明横屏下也按数字槽位选对了词。
- 已知取舍：横屏可用高度只有 1080px，键盘本身占 ~560px，再加两行网格后 IME
  窗口把编辑器整块挤出可见区（只剩组合串 `shi` 在顶部）。这是“键盘不动”的
  代价，微信键盘横屏展开同样是全屏覆盖；组合串仍在，用户不会失去正在输入的
  内容，故未改写布局。
- 复测小坑：横屏截图是 1920×1080，而看图时按显示宽度换算会差一个比例
  （1624/1920）；`input tap` 用的是真实像素，先按 uiautomator 的 bounds 校准
  再点，否则会点到空格/句号上。

## Release 构建：签名与 R8（2026-11-16）

`TASK.md` 里一直挂着的「release 签名与混淆」这一轮补上：先前只有 `assembleDebug`
可用，release 是 `minifyEnabled false` 且没有签名配置，等于装不进设备（未签名
APK 系统会拒装）。

### 签名材料不进仓库

- 仓库根目录放 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias`
  / `keyPassword`）与 `app/pime-release.jks`（PKCS12、RSA 4096、10000 天），两者
  都进 `.gitignore`。`app/build.gradle` 只在**文件存在且指向的 keystore 存在**时
  才创建 `signingConfigs.release`，否则 release 继续能构建、只是不签名——这样
  干净检出上跑 `assembleRelease` 做校验不会被缺密钥挡住。
- v1 签名显式打开（`enableV1Signing true`）：minSdk 是 24，API 24/25 只认 v1；
  v2 同时打开。`apksigner verify --print-certs` 显示单签名者、SHA-256
  `d8:e0:48:78…`。

### R8 要保住的东西

混淆的唯一硬约束来自 JNI，两边都不在编译期检查：

1. **导出的符号名**：`pime_jni.cpp` 里是 `Java_com_pimeorg_pime_PimeCore_nativeStart`
   这类固定名字，类名或方法名被重命名就 `UnsatisfiedLinkError`。
2. **回调方法名**：native 拿到 `thiz` 后按字符串 `GetMethodID` 查
   `onNativeComposition` / `onNativeCommit` / `onNativeCandidates` /
   `onNativeHideCandidates` / `onNativeBeep` / `onNativeNotify` / `onNativeWake` /
   `onNativeLog` 及其签名。

因此 `app/proguard-rules.pro` 里 `-keep class com.pimeorg.pime.PimeCore { *; }`
（整类 + 包名），外加通用的 `-keepclasseswithmembernames class * { native <methods>; }`。
清单里声明的组件（`PimeApplication` / `PimeImeService` / `SettingsActivity` /
`VoicePermissionActivity`）AGP 会自动保，规则里也写了一条以表明意图。

Java 层没有任何反射式查找（无 `Class.forName` / `getMethod` / `getIdentifier`），
所以除 JNI 之外不需要额外 keep。`commons-compress` 会探测未随包的可选后端
（XZ / zstd / brotli），加了对应的 `-dontwarn`；语音模型是 tar+bzip2，bzip2 是
库自带实现。

### 体积

| | debug | release |
|---|---|---|
| APK | 35.4 MB | 25.3 MB |
| `classes.dex` | — | 1.4 MB |

差的主要是 native 调试符号（`extractReleaseNativeSymbolTables` 另存到
`build/outputs/native-debug-symbols/`）与 R8 收缩；`.so` 本身未重新编译。

### 验证（MuMu 12 / Android 15 / x86_64，卸载 debug 后全新安装）

签名不同必须先 `adb uninstall`，因此这一轮顺带验证了「首启从 assets 重装插件与链」。

- 冷启日志：19 个插件全部 `plugin loaded`、`chain built: default.cbor` /
  `english.cbor`、`speech generation built`、`active generation: chinese`，
  无 `UnsatisfiedLinkError` / `NoSuchMethodError` / `ClassNotFoundException`。
- `nihao` → 候选 你好/您好/年好/念好 → 空格提交为「你好」。
- `shi` → 行末 `⌄` → 两行网格 → 点第 7 个「市」上屏，联想刷新为「场」。
- 插件与链页：链方案、19 个插件的 `序号 · 中文名`、`role · version`、描述、
  上移下移与开关全部正常（清单与 CBOR 解析在 R8 后无损）。
- 关于页版本显示 `0.1.0`，没有 debug 后缀（`BuildConfig.DEBUG` 已是 false）。
- 语音：数据被清空后长按空格 → 先弹权限 → 授予后提示下载 → 直连 GitHub
  `Connection reset`，自动回退 ghproxy 镜像下载成功，`bzip2 + tar` 解包在 R8
  下正常；再长按得到 `listening, session=1`，MuMu 无宿主麦克风所以采到
  `capture read: -5` 后 `stopped, 0 chars`，与 debug 行为一致。
- 未验证：release 下的语音回放（`voiceReplay` 由 `BuildConfig.DEBUG` 关掉），
  与 arm64 真机一致仍待物理设备。
