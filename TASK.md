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
