# TASK — Android 外壳

- [x] 探查 Android 工具链（JDK 21、SDK 36、NDK 28.2、CMake 3.31.6、Gradle 8.14.5）
- [x] 建立 Gradle 工程（AGP 8.13.2，compileSdk 36，minSdk 24）
- [x] 注册 `PimeImeService`（`android.view.InputMethod`）与 `res/xml/method.xml`
- [x] 启动器 `SettingsActivity`（启用/切换输入法、显示插件目录）
- [x] JNI 桥：core 生命周期、插件加载、代 build/activate、会话、按键/文本/候选/上下文/焦点/安全/复位
- [x] HAL sink → Java：`set_composition` / `commit` / `show_candidates` / `hide_candidates` / `beep` / `notify`
- [x] UTF-8 ↔ UTF-16 转换与组合串字节游标换算
- [x] 软键盘视图（字母/数字/Shift/退格/空格/回车）与候选栏、组合串显示
- [x] 未被 core 处理的按键回传编辑器，零插件时键盘仍可用
- [x] NDK CMake 静态链接 pime-core，产出 `libpime_jni.so`（arm64-v8a + x86_64）
- [x] 修复 `platform_posix.cpp` 在 Android API < 28 下 `getrandom` 不可声明的问题
- [x] 生成 debug APK
- [x] Windows 宿主 smoke test：空链代 build/activate、会话、按键返回 unhandled
- [x] 集成 WAMR 2.4.1（fast interpreter）作为 core 的 WASM 后端，静态链入 `libpime_jni.so`
- [x] 同步重建后的五个第一方插件包与 `default.cbor` 到 `assets/`
- [x] JNI/Java 增加能力授权接口（`pime_core_permission`），启动时授予内置链声明的能力
- [x] 宿主 smoke test：五包加载、链 build/activate、`nihao` → 候选 → 提交 `你好`
- [x] 修复插件清单缺少 `android-x64` 导致模拟器上全部 `NOT_FOUND`
- [x] 修复 WAMR 栈守卫探测在 Bionic 上的启动崩溃（关闭 native 栈边界检查）
- [x] MuMu 12（Android 15 / x86_64）实测：插件加载、`nihao → 你好`、候选提交
- [x] 实测修复：候选提交后组合状态复位（parse 提交阶段保留 workspace、清空 state）
- [x] 实测修复：空组合退格回传编辑器（keymap 不再吞键、`deleteSurroundingText`）
- [x] 实测修复：结束组合用 `setComposingText("", 1)`，避免残留组合串
- [x] 同步增强后的拼音插件（20k 词表、简拼/整句）并重建 APK
- [x] MuMu 实测简拼与整句：`ws` → 首位“我是”并提交成功；`woshi` → 我是；
      `nh` → 你好
- [x] 同步原始字母网格版 translate（`hbg` → 还不够）并重建 APK
- [x] MuMu 实测三字母简拼：`hbg` 候选首位“还不够”，点击后提交成功
- [x] 同步 20,069 条词表与 `kWordBonus=9000` 的 translate 包，重建 APK
- [x] MuMu 复测歧义覆盖：`zsh` → 早上好、`gz` → 广州、`sz` → 深圳、
      `yiqichifanba` → 一起吃饭吧、`wozaijia` → 我在家，首位均正确
- [x] MuMu 多场景实测：`hbg`、`wanan`、`shengrikuaile`、`tiananmen`、
      `nvhai`、`lvse`、`srkl`、`nhm`、`zgm`、`dhrs`、`nh`、`dd`、`wsm`、
      `xian` 共 13 组，候选首位全部符合预期
- [x] 记录 Android 侧 adb 连点退格被合并的测试注意点
- [x] 修复设置页启动闪退：不再读取 Android 15 受限的
      `Settings.Secure.ENABLED_INPUT_METHODS`，改用
      `InputMethodManager.getEnabledInputMethodList()`；MuMu 冷启动验证通过
- [x] 按 WeaveText 首页重做设置页：Material 3 品牌卡、三态状态、试验输入框、
      分组列表、关于/插件/存储对话框
- [x] 启用 AndroidX + Material Components，补明暗主题与 640dp 内容列
- [x] 使用 `E:/work/pime` 的蓝色 PIME 标志生成自适应应用图标
- [x] MuMu 实测明暗模式、默认输入法状态切换、试验输入框拉起键盘并输入
- [x] UX 二轮：未就绪时两步引导（编号圆圈/完成勾/隐私说明）、状态胶囊
- [x] UX 二轮：对话框改为二级页（关于/插件与链/存储位置），返回箭头 +
      系统返回键；插件页按 manifest 显示 role/version，点行复制路径 + Snackbar
- [x] UX 三轮：复制行改用复制图标、组标题带数量、Snackbar 只报短名、
      试验输入框加清空图标与示例文字、页面切换淡入、竖屏实测
- [x] UX 四轮：启用/默认状态变化时 Snackbar 提示；复制图标补无障碍描述；
      方向性图标支持 RTL
- [x] UX 五轮：首页加“键盘与候选”入口与二级页，提供真实可配置项（键盘高度
      88/100/115%、按键振动、按键音、候选数量 全部/3/5/7/9、主题 跟随系统/
      浅色/深色），偏好存 `SharedPreferences`
- [x] MuMu 实测宿主偏好：高度 115% 键盘变高、候选数量 3 时候选栏只显示 3 个
      （改回“全部”同一输入出现 5 个以上）、主题改深色后立即重建，偏好落盘并复原
- [x] 修启动器图标：品牌蓝底 + 白气泡（界面品牌标记与启动器一致），
      `tools/gen_launcher_icons.py` 出各密度位图与自适应前景，删掉旧的
      `mipmap-anydpi` 位图兜底
- [x] 修试输入框：`EditText` 的 `OnClickListener` 不触发，改 `OnFocusChangeListener`
      + 显式 `showSoftInput`；insets 里处理 IME 底部内边距
- [x] 试输入框在 PIME 不是当前输入法时给出诚实提示（helper 文案 + Snackbar
      带「选择输入法」动作），并补 `onWindowFocusChanged` 刷新状态
- [x] 键盘与候选页每行换成语义正确的图标，不再复用拼图/信息/同一个滑杆
- [x] 输入 UI 重做：`KeyboardView` 自绘字母/符号两页，明暗两套配色令牌
      （`KeyboardTheme`），键面带底衬、回车与 Shift 用强调色，行高按屏幕推
- [x] 输入 UI 重做：顶栏（组合串 + 键盘设置 + 收起键盘）与候选栏，首选用
      强调色胶囊；候选栏出现/消失时按键不跳动
- [x] 明暗主题或横竖屏变化时重建输入视图，并保留组合串与候选
- [x] MuMu 实测输入 UI：`nihao` → `你好`（首位胶囊）、符号页、深色、横屏、
      齿轮打开设置页、下箭头收起键盘
- [x] 插件与链页重做：链方案（勾选在用链、重命名/删除菜单、新建/导入）、
      一份插件只列一次的链路顺序（展开箭头 + 序号/中文名 + role·version +
      上移下移 + 开关）、未启用插件卡片、插件设置就地展开（数值改直接输入）、
      缺失插件标「未安装」、启停带「撤销」
- [x] MuMu 实测插件与链页：切换/新建/重命名/删除链、插件启停与撤销、顺序调整、
      数值设置写入 CBOR、空链、缺插件、深色，以及 `pm clear` 后的输入回归
- [x] 插件包导入：页面提供「导入插件包（.zip）」与「从文件夹导入」两条路径，
      `PluginStore` 校验清单与模块、剥掉 zip 顶层目录、原子落盘、覆盖时清掉
      core 的旧副本、拒绝越界路径；清单缺本机 ABI 时导入前提醒
- [x] `ACTION_RELOAD_PLUGINS`：运行中的输入服务重扫插件目录并重建当前链，
      新导入的插件无需重启输入法或清应用数据即可用
- [x] MuMu 实测导入：zip（带顶层目录/平铺）、覆盖确认、平台不匹配警告、非插件包
      报错、文件夹导入、热加载日志、导入后可加入链
- [x] 顶栏改为 WeaveText 式工具栏：字母/符号页切换、粘贴、键盘设置、收起
      四格等分，活动格用内缩强调色胶囊；工具栏在组合时也保留
- [x] 组合串与候选合成一行：组合串在左、候选横向滚动在右，两者都空时整行隐藏
- [x] 工具栏与键盘内部“符 / ABC / 字母”键双向同步当前页状态
- [x] 粘贴作为宿主动作：空剪贴板提示，组合中先 reset 并收起候选再提交
- [x] 主题/键盘高度变化时广播重建输入视图，候选配色固定使用当前视图的 palette
- [x] MuMu 实测工具栏：明暗主题、横竖屏、页切换与高亮同步、粘贴（含组合中）、
      齿轮打开设置、下箭头收起
- [x] 上滑副标签：首行 q–p → 1–0，第二行 a–l → `~!@#%“”*?`，
      第三行 z–m → `（）-_：；、`；键高变化时字母与副标签字号自适应，横屏不重叠
- [x] 上滑需 28dp 与 0.55×键高取大、且纵向 > 1.5×横向；短滑/斜滑仍按字母，
      按下高亮在手指离开键位后保持到抬起
- [x] MuMu 实测：q→1、w→2、p→0，a→~、h→“、z→（；普通点按仍是字母；
      Shift + 上滑仍出副标签；短距离上滑不出字符；横屏上滑正常
- [x] 中/英切换键：字母页与符号页底行加「中/英」，当前侧加粗放大；切换时把
      进行中的组合按原样提交并 `reset`（当时英文绕过插件链，后续改为英文链）
- [x] 英文候选：随包 `pime-english-parse 1.0.1` / `pime-english-translate 1.0.1`
      与内置 `english.cbor`（keymap → parse → translate → candidates-format）
- [x] 中/英改为两代切换：启动时构建中文链与英文链两代，切换只 activate，
      不重建链；旧代在切换后 retire
- [x] 英文词表：rime-wanxiang `en.dict.yaml` 生成 36,345 条（759 KB），
      键去撇号（`dont` → `don't`）、保留大小写（`GitHub`/`WiFi`）、
      单 token 且频次 ≥ 50（另保留带大写或撇号的条目）
- [x] `pime-english-translate` 1.0.1：候选先收集再排序，`hel` 不再漏掉
      `hello`；补全上限改为排序后生效
- [x] 宿主英文语义：空格由链提交原码后补一个空格；标点先提交原码再插入符号
      （不补空格）；点候选只提交词本身
- [x] 设置页：`english.cbor` 不出现在链列表，新建/重命名/导入禁止占用该名字
- [x] MuMu 实测英文：`hel` → hel/help/held/helped/hell，`dont` → don't，
      空格/候选/标点三条提交路径与中英切换、重启后模式保持均正确
- [x] MuMu 实测：组合 `h` 切英文后提交 `h`、无候选；英文 `nihao` 原样上屏；
      切回中文再打 `nihao` 组合串为 `nihao`（无残留），候选首位「你好」
- [x] 设置页插件行补上清单里的 `description`：链内行与未启用行各多一行说明，
      展开面板显示完整描述；未安装的链内插件不显示描述
- [x] 候选栏展开（微信输入法式）：候选行右端常驻 `⌄`，点开候选区；
      首版做成“同一页重排成网格”，被推翻（见下条）
- [x] 候选栏展开重做（向微信键盘重学）：在 MuMu 里重新实测微信键盘
      （`wx_folded/expanded/scrolled.png`）后才看懂精髓——展开是**候选面板
      接管整个键盘区域**（IME 高度不变，编辑器不重排）、候选远多于折叠态
      （可纵向滚动多页）、右下角留退格。宿主照此重写：表格与键盘互斥地
      占据同一矩形；`home`+`pagedown` 把链的候选整表收来（上限 5 页）；
      选词改用 format 插件的 `candidate` 事件（index 是**全局序号**，
      `commit = index < total`），第 23 个候选不用翻页直接提交；上一版
      “只走数字键、index 记账多余”的结论被推翻
- [x] MuMu 实测展开：`goo` → 5 列 × 4 行（20 个候选，4 页收集）可滚动，
      点第 4 行 `Google`（全局 16）直接上屏；表格退格删字后表格重建；
      英文链 `her` 展开同样适用，点 `hero` 上屏；折叠回条带页码不变
- [x] 教训：想回到 page_size 5 时用仓库里的 yaml 重新生成了 default.cbor，
      结果中文候选全变成英文补全——yaml 与已提交的 CBOR 不同步（yaml 生成的
      链只有 18 个插件，CBOR 是 19 个），已 `git checkout` 恢复原 CBOR；
      yaml/CBOR 同步问题留待后续以真实源修
- [x] MuMu 实测横屏展开：表格接管键盘区后编辑器**完整可见**（上一版
      “编辑器被挤出”不再存在），点第 2 个候选「仍然」上屏正确
- [ ] arm64 物理设备验证
- [x] release 签名与混淆：`keystore.properties` + `app/pime-release.jks`（均不进仓库，
  缺失时 release 仍可构建但未签名）；`minifyEnabled`+`shrinkResources`，
  `proguard-rules.pro` 钉住 JNI 符号（`PimeCore` 包名/类名/native 方法/`onNative*`
  回调）；APK 35.4 MB → 25.3 MB，v1+v2 签名
- [x] MuMu 实测 release（R8 后）：19 个插件加载、default/english/speech 三代构建、
  `nihao` → 你好、空格提交、`shi` 展开网格点第 7 个「市」、插件与链页、关于页
  `0.1.0`（无 debug 后缀）、语音权限引导与模型下载（GitHub 超时→ghproxy 镜像，
  bzip2/tar 解包在 R8 下正常）、长按空格 `listening, session=1`
- [x] 修复内置插件包资产缺失：`pinyin.collocations` 与 predict-context 两个 TSV；
      插件 CMake `ASSETS` 补齐并重出包
- [x] APK 更新时刷新内置插件包与 core 的 `<id>/<version>` 副本，旧安装无需
      `pm clear`
- [x] 构建期 `checkBundledPluginAssets`：manifest 声明的资产缺一即失败
- [x] `pime-hans-hant` 默认关闭，中文候选不再默认变繁体
- [x] MuMu 实测 `nihao` → 你好、`dianhua` → 电话，logcat 无 `NOT_FOUND`
- [x] 中英混输：中文链挂 `pime-english-translate 1.0.2`，`goo` → 原码/补全 +
      中文候选合并视图；纯拼音与简拼不混入
- [x] 候选栏翻页 `CandidateStrip`：内容滚到头或放得下时横滑翻页（PageDown/Up），
      合并视图的中文候选与后续英文补全都能翻到
- [x] 候选点击改用数字选词，翻页后第 2 页候选不再提交成第 1 页的候选
- [x] 内置链随 APK 升级刷新（baseline 哈希；旧版本首次升级先留 `.cbor.bak`）
- [x] MuMu 实测 `goo` 混输视图、左右翻页、第 2 页选字提交、空格提交原码
- [x] 修复数字选词后链的缓冲未清：点选 `Google` 后仍从 `goog` 继续拼接；
      提交后由宿主补发 `reset()`（中文/英文候选、符号页数字选词均覆盖）
- [x] MuMu 实测提交后组合复位：`goog`→Google→`l`、`nihao`→你好→`n`、
      `hel`→help→`p`、数字 `2` 选 `people`→`p`，拼音行均为单个新字母
- [x] 修复中文退格后的联想残留：把光标前后真实文本上报给链，左文为空时收起联想
- [x] MuMu 实测：`你好` 退格到 `你` 联想改按 `你`，再退格清空后联想栏收起
- [x] 修复无组合时点联想候选无效：宿主直接提交联想词，再按新上下文刷新联想
- [x] MuMu 实测：`你` 点联想 `的` 得到 `你的`，联想更新为 `话 / 是`；
      有组合时数字选词不变；default 链恢复为 19 个插件
- [x] 修复中文输入 `nih` 候选栏混入英文拼写建议：中文链只做前缀补全，
      英文无实际新增时保持中文视图；MuMu 实测 `nih`/`goo`/`nihao`/英文 `hel`
- [x] 语音输入：内置 `pime-speech-sherpa` 原生插件（sherpa-onnx 流式
      Zipformer2-CTC）+ 长按空格说话；`RECORD_AUDIO`、`AudioRecord` 采集
      16 kHz PCM 经 core 音频环送插件，边说边预编辑、松手整句提交
- [x] 语音人口从工具栏麦克风改为长按空格：工具栏只留页切换/粘贴/设置/收起；
      短按仍是空格，长按期间滑出键区不会变成别的按键
- [x] 键面去文字：空格键改为空白宽栏 + 静音色小麦克风（长按时整键强调色、
      只画大麦克风）；去符号页用 `#`、回字母页用 `A`，与 Shift / 回车 /
      退格同为一套描边字形
- [x] 中/英保留文字但重做设计：左右两格开关 + 中间 1dp 半透明细分隔，当前
      语言那侧实色加粗、另一侧压成 hint 色常规字重；两字同字号共基线，
      窄屏时整块等比缩小不截字
- [x] 语音开不了时不吞键：密码框/缺插件/缺模型/缺权限只提示原因，松手照旧
      输入一个空格；缺权限授权后提示再次长按，不在手指已离开时自动开麦
- [x] 语音模型按需下载（GitHub release 镜像 + sha256 校验 + bzip2/tar 解包），
      首次长按空格提示下载；权限由透明 `VoicePermissionActivity` 申请
- [x] MuMu 实测（模型已就位）：插件加载、识别器就绪、采集线程运行；
      用 `voice-test.wav` 开发回放验证 `0.wav` → 「对我做了介绍…」预编辑，
      松手后 81 字提交成功
- [x] MuMu 实测长按空格（adb `input swipe` 同位长按）：按住触发
      `recognizer ready` → `listening, session=1`，松手 `stopped` 并切回中
      文代；文本框没有多出空格；短按空格仍插入空格
- [x] MuMu 实测按需下载：删掉 `files/models/asr-stream-small` 后触发语音
      （当时的人口还是工具栏麦克风），直连 GitHub 超时后自动走 ghproxy 镜像，
      21 MB 归档下载 + sha256 校验通过、只解出 `model.int8.onnx` 与
      `tokens.txt`，缓存归档删除；模型就绪后再次使用不再下载
- [ ] 物理设备麦克风实测（MuMu 无宿主麦克风输入）
- [x] 候选面板顶部分类：词频 / 单字 / 手写找字（单字只留单个汉字，切换时清筛选）
- [x] 左侧音节栏：按候选首字拼音分组筛选，保留全局候选下标
- [x] 首笔画二级筛选：横 / 竖 / 撇 / 点 / 折，按模板首个子笔画方向归类
- [x] 手写找字：内置 `hanzi/mmah.bin` 笔画模板 + 独立 Java 识别器，
      画布 / 撤销 / 清空 / 结果条，提交后清空并停留面板
- [x] 单字拼音表 `hanzi/pinyin.tsv`（`tools/gen_hanzi_pinyin.py` 可重生成），
      Arphic Public License / CC BY 4.0 署名进 README 与关于页
- [x] 手写识别单测（9,507 模板；`一`/`十`/`氣` 第 1、`元` 第 2）；
      `testDebugUnitTest` 3/3 与 `assembleRelease`（R8）通过
- [x] MuMu 实测：三分类、音节筛选、首笔画筛选后的提交下标、手写 `一` 上屏、
      退格/撤销不折叠手写面板
- [ ] 侧栏「首笔画」固定在底部（音节多时目前要滚到底才能点到）
- [ ] 空输入框里的手写入口（目前只在有组合/候选时可展开面板）
- [ ] 横屏时手写画布与结果条的比例
- [x] 候选数量=全部时不再封顶：translate/rank/format 上限 32 → 200，
      translate 在束搜索之外补整串直配表，format 的 `pime_init` 不再把
      `page_size` 锁死在 9（此前链里的 200 被静默忽略）
- [x] 宿主一次向链要整页候选，3/5/7/9 改为本地窗口翻页（`stripOffset`），
      空格在翻页后提交窗口首项；折叠条候选视图复用，不再每次重建整排
- [x] 选词不再限制 9/数字键：`selectCandidate` 接受任意全局下标
- [x] 展开面板去掉 5 页上限（`GRID_PAGE_GUARD` 防死循环）
- [x] 插件版本 1.3.0 / 1.3.0 / 1.4.0 同步进 yaml、CBOR 与 `assets/plugins/`
- [x] 三层上限再提到 640（translate/rank 1.4.0、format 1.5.0），依据是实测
      `yi` 549、`s` 640、`shi` 260；链设置、CBOR、`CANDIDATE_PAGE_SIZE` 同步
- [x] 扩充词库：rime-wanxiang `6ab7737` 的 `zi.dict.yaml` + `jichu.dict.yaml`，
      4 万单字 + 15 万词，合并后 181,656 条 / 4.1 MB（旧 20,069 条的 9 倍），
      字对表 40,000 对 / 520 KB，已同步进插件资产
- [x] 修复 `AssetService::read` 忽略调用方 `len` 的缺陷（词表 >4 MB 时 1 MB
      分块读全部 `BUFFER_TOO_SMALL`）；插件改为分块加载 4.1 MB 词表
- [x] 扩展词表下的纠错分档：整词 log2 分数 ≥ 12200 才无条件否决纠正，
      罕见整词按词长加成后比较；测试 006/030 更新、031 新增
- [x] 修复候选数组 `ArrayView::at()` 的 O(n²) 遍历：新增 `Cursor` 单次游标，
      format/rank/hans-hant/shortcuts/learn-user-dict/共享读取器全部改用；
      设备上 640 条候选的单键从 4.96 s 降到 0.30 s（最小链）
- [x] `ctest` 插件用例 284/284、`assembleDebug testDebugUnitTest` 全过
- [x] MuMu 实测：`shi` 260 条、`sh` 640 条；折叠条选「时」、面板选「师」
      均提交正确；面板词频网格与音节侧栏正常
- [ ] arm64 物理设备上测 640 候选的实际按键延迟（模拟器上 `sh` 约 0.6 s，
      主要是 15 个下游插件重复读取携带 640 条候选的 53 KB 工作区帧）
- [x] 折叠/展开候选预算：`pime_context.max_candidates` + 无派发
      `pime_core_candidate_budget()`；折叠 64、展开 640，帧 52 KB → 6 KB，
      下游整帧解码 70 ms → 12 ms
- [x] translate 单字母快速路径（单音节索引）与后缀可达性剪枝（`g_cover`）
- [x] 整串直配表改为哈希去重 + 池 + 一次归并排序（`sh` 的 33 万次搬移消失）
- [x] 验证：core/plugin ctest、`assembleRelease testDebugUnitTest` 全过；
      MuMu 实测 `s`/`sh`/`n` 候选正确
- [ ] arm64 物理设备上复测折叠态单键延迟
- [x] 候选面板对齐微信输入法外观：分类条改为浮层轨道（居中 250dp 胶囊底 +
      内缩选中块 + 同行右侧 `⌃`），展开时面板吃掉折叠条那一行（总高不变，
      多出一行候选），音节侧栏与候选行等高对齐（`rail`/`railLine`）、
      `全部` 仅在筛选后出现、`首笔画 ›` 固定在侧栏底格（笔画模式变返回），
      候选行 48dp + `rowLine` 细线且只给实际候选等宽分格、表格里不再给首位
      画强调胶囊，退格改为铺满末列的平底格 + 新矢量 `ic_backspace`
- [x] `KeyboardTheme` 补面板色令牌：`panel/rail/railText/rowLine/railLine/
      tabTrack/tabActive/tabText/tabActiveText/keyCap`，明暗各一套
- [x] MuMu 实测明暗两套面板、三个 tab、音节筛选、首笔画上下级、退格删字、
      `⌃` 收起回键盘；截图 `screenshots/candidates_table_{light,dark}.png`
- [x] 修复设置页“是否已切换”误判：API 34+ 框架接口返回他法时回退比对
      `Settings.Secure.DEFAULT_INPUT_METHOD`
