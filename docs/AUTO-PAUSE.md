# 自动暂停（Auto Pause）2.0.2 — 分支说明

本分支基于上游 `v1.0.0` 之后，做了中文化并加了一组「让被限制的应用真的起不来」的能力。
包名保持 `io.github.burakgon.stop` 不变，可与上游版本互相覆盖安装（但签名不同，正式替换前需卸载）。

## 中文化

- `app/src/main/res/values-zh-rCN/` 全量中文（应用名 `自动暂停`，默认语言为 `Auto Pause`）。
- 系统会把 locale 透传给应用，没有硬编码 `Locale.US`，所以跟随系统语言即可。
- 少数脱壳字符串在 `values/strings.xml` 与 smali 之外的部分全部走资源，无遗漏。

## 工作模式（设置 → 工作模式）

| 模式 | 行为 |
| --- | --- |
| `idle` 空闲 | 只做额外限制（电池/待机），不杀进程 |
| `stop` 强行停止 | 离开宽限期后 `forceStop` |
| `suspend_stop` 强停挂起 | 先 `pm suspend` 再 `forceStop`，应用无法自行在后台起来 |
| `freeze` 永久冻结 | 按 suspend → disable → hide 顺序尝试，记录实际生效方式 |

「永久冻结」与「强停挂起」的区别在于记录的集合：
`restrictions.auto`（跟规则走，取消限制即释放）与 `restrictions.frozen` + `manual`（改配置不会偷偷解冻）。

## 挂起优先的冻结

`SystemApis.setSuspended()` 不再写死参数个数，而是按 `IPackageManager.setPackagesSuspendedAsUser`
在本机的真实签名逐位构造参数。Android 15 上它是 9 参且返回 `String[]`：

```
setPackagesSuspendedAsUser(String[], boolean, PersistableBundle, PersistableBundle,
                           SuspendDialogInfo, int flags, String callingPackage,
                           int userId, int callingUid)
```

旧实现用 `parameterTypes.size in 7..8` 过滤，在 9 参机型上匹配不到方法、直接返回 `false`，
界面于是弹「系统拒绝挂起这个包」。现在按名字匹配并逐位填充，`SuspendDialogInfo` 走反射构造，
并带 `BUTTON_ACTION_UNSUSPEND` 中性按钮（系统自带的「解除挂起」）。

挂起失败时回退 `pm suspend` / `pm disable-user`（shell 身份，仅用于用户显式动作，后台拦截不 fork）。

## 点图标即打开

被挂起的应用被点开时，系统会起 `SuspendedAppActivity` 而不是应用本身，任务栈里也查不到原包名。
守护进程改为监听 `logcat -s ActivityTaskManager:I` 的 `START u0 {… cmp=<pkg>/<activity>}`，
命中冻结/挂起集合就解挂、记录「冻结期间被点开」事件并拉起原 Activity；同时 5 秒看门狗负责重启监听。

## 其他

- 预装清理：`assets/bloatware/tiers.csv`（269 条，A/B/C 三档）+ `ui/bloatware/` 批量冻结页。
- 桌面快捷方式：`ui/shortcut/` 一键冻结/解冻（`requestPinShortcut`）。
- 事件记录：`tapped` / `freeze` / `thaw` 三种新 reason，活动页加了对应筛选。
- 诊断：`/data/local/tmp/<pkg>/debug.log`（守护进程跑在 Shizuku user service 里，日志进不了 logcat）。
- UI 状态：回到界面与每次冻结类事件后都会重读包状态，避免「已冻结但其实在跑」。

## 构建

上游依赖 AGP 9 / compileSdk 37 / Kotlin 2.4。本分支为了能在 AGP 8.11.1 + compileSdk 36 的
环境里复现，把 `gradle/libs.versions.toml` 与 `compileSdk` 降了一档，并关闭了 `aidl` 特性
（缺可执行的 `aidl` 二进制），`daemon/IStopDaemon.java` 与 `IDaemonListener.java` 是手写的
AIDL 生成物，接口与 `*.aidl` 保持一致。
