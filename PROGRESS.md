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
