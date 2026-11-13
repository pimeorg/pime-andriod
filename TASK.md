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
- [ ] 设置页增加插件启停/多链管理（当前只读展示）
- [ ] arm64 物理设备验证
- [ ] release 签名与混淆
