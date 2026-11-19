# MuMu 上键盘"有时不弹出"的成因

调查日期：2026-11-19
环境：MuMu Nx 15.0（Windows 宿主 `T:\Program Files\Netease\MuMu\nx_device\15.0`），
模拟器 Android 15 / x86_64 / 1080×1920 / `127.0.0.1:16384`，默认输入法为本项目
（`com.pimeorg.pime/com.tencent.qqpinyin.LocalImageBridge`）。

## 结论

不是本输入法的键盘逻辑问题。MuMu Nx 的「标签页」机制
（`com.android.server.mumu.tab.TaskTab`，`persist.tab_manager.use_vaddress=true`）
给每个新任务分配一个**独立虚拟 display**，而系统把 IME 窗口始终建在
**display 0**（主屏）。于是：

- 输入框拿到焦点，`mInputShown=true`，IME 窗口 `h=816`、`mViewVisibility=0x0`（可见）
  —— 键盘真的画出来了；
- 但它画在 display 0 上，用户正在看的标签页是 display 10/11/12/13，所以看不见键盘
  —— 表现就是"点了输入框，键盘不弹出"。

换搜狗输入法同样如此、系统「设置」app 同样如此，所以与具体输入法无关，
属于 MuMu Nx 的 ROM 行为。同一台机器上把 app 放到 display 0（MuMu 主屏上的
商店/桌面）时，本输入法连续 5 轮「弹出 / 收起 / 再弹出」全部正常。

## 复现

```bash
# 1. 新开任意 app（本项目设置页 / 系统设置都会中）
adb shell am start -n com.pimeorg.pime/.SettingsActivity

# 2. 看它被放到了哪块 display（每次启动都会换一个新号：10 / 11 / 12 / 13 …）
adb shell dumpsys activity activities | grep -E "^  displayId=|Task\{.*pime"

# 3. 在它所在的那块 display 上点输入框
adb shell input -d 12 tap 540 1480

# 4. 系统认为键盘已显示
adb shell dumpsys input_method | grep -E "mInputShown|mCurTokenDisplayId"
#       mCurTokenDisplayId=0
#       mInputShown=true

# 5. 但 IME 窗口在 display 0，而不是 12
adb shell dumpsys window windows | grep -A14 "Window #.*InputMethod" \
  | grep -E "mDisplayId|Requested|mViewVisibility"
#     mDisplayId=0 ...
#     Requested w=1080 h=816
#     mViewVisibility=0x0        # VISIBLE

# 6. 截屏对照：display 0 有键盘，display 12 只有光标
adb shell screencap -p -a /sdcard/all.png
```

`dumpsys input_method` 的 `mStartInputHistory` 里同一件事写得很直白：
`targetDisplayId=12` 而 `imeDisplayId=0`。

`ImeTracker` 也留下了痕迹（一次 show 请求失败、一次 10 s 超时）：

```
I/ImeTracker( 4999): com.pimeorg.pime:5c163943: onRequestShow ... fromUser true
I/ImeTracker(  840): com.pimeorg.pime:5c163943: onFailed at PHASE_SERVER_UPDATE_CLIENT_VISIBILITY
I/ImeTracker(  840): com.pimeorg.pime:11056de3: setFinished at PHASE_CLIENT_CONTROL_ANIMATION with STATUS_TIMEOUT
```

MuMu 侧日志确认了这个机制：

```
E/[MUMU]TaskTab(  840): setPresentationRotation 0 for com.pimeorg.pime:35
    at com.android.server.mumu.tab.TaskTab.setPresentationRotation(TaskTab.java:52)
    at com.android.server.wm.TaskUtils.updateTaskTabRotation(TaskUtils.java:1957)
```

## 判定依据（排除法）

| 观察 | 说明 |
|---|---|
| 输入框有焦点、光标在闪 | app 侧 `showSoftInput` / 焦点链路正常 |
| `mInputShown=true`、IME 窗口 VISIBLE、`h=816` | 键盘窗口真的建出来了，尺寸也正常 |
| IME 窗口 `mDisplayId=0`，任务在 display 12 | 两者不在同一块屏 |
| 搜狗输入法同样落在 display 0 | 与输入法实现无关 |
| 系统「设置」app 同样不出键盘 | 与 app 无关 |
| display 0 上连续 5 轮弹出/收起正常 | 键盘逻辑本身没问题 |

## 次要因素（已修）

`PimeImeService.onCreate()` 原来在主线程一口气做完这些事，之后
`onCreateInputView()` 才会跑：

```
installBundled() → core.start() → loadPlugins(20 个插件包) → buildGenerations()
```

实测这一段约 **0.7–2.5 s**（`PimeIme` 日志跨 `pime-core` 到
`active generation`），期间键盘窗口排不上，表现为"第一次点输入框要等一会儿
才出键盘"，超过系统阈值就变成 `STATUS_TIMEOUT`。

现在这串工作拆成 prepare 步骤队列、每帧跑一步（插件一个一步），键盘窗口在前
几步之后就能画出，链随后就绪；链没就绪时的按键会被按住并在就绪后重放。整个
序列仍在主线程执行：实测把 core 调用搬到 worker 线程后，插件层每次事件都返回
`PIME_CORRUPT`（WAMR 后端与线程绑定），所以没有采用后台线程方案。详见
`PROGRESS.md` 的「冷启动不再挡键盘」一节。

## 结论汇总

| 症状 | 原因 | 归属 |
|---|---|---|
| 标签页里的 app 完全不弹键盘 | MuMu Nx 把 app 任务放到独立虚拟 display，IME 窗口固定在 display 0 | MuMu ROM |
| 第一次点输入框要等一会儿才出键盘 | `onCreate()` 一次性做完全部启动工作 | 本仓库（已修） |
