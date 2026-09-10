# RC2 刷新回跳修复

## 原因

RC1 在得到完成 25 条红包确认后，停在 CommonWebActivity。ADB Logcat 出现
`Background activity launch blocked!`，调用方为伴侣，状态为 FOREGROUND_SERVICE，
`inVisibleTask=false`。后台 startActivity 无异常返回不等于成功显示界面。
页面的 location.href 和普通链接也未触发有效回跳。

本机“后台弹出界面”已经为 Always allow。没有改动该权限或关闭 Android BAL 保护。
此前 RC1 验收记录只证明同步保存完成；不能将人为重新打开后的 companion_returned
视为自动回跳通过，本轮对此作出更正。

## 有限范围互操作检查

- 范围：用户 USB 手机 99ce3644 的正常确认页导航；只读检查已安装得到 APK。
- 使用 apk-reverse 流程检查 Manifest、CommonWebActivity、JsAgentAdapter、JsJumpAdapter。
- CommonWebActivity 非导出，不尝试绕过组件限制或从伴侣直接调用它。
- Java 层 JsJumpAdapter 的 `jump.universal` 接受 `{type:"scheme", route:...}`，由
  前台页面执行 ACTION_VIEW。JsAgentAdapter 的 agent.close 只关闭当前页，不足以返回伴侣。
- 无 Hook、无第三方 APK 修改，无凭据/音频/DRM 数据读取或导出。

## 修改

- 确认页通过既有原生桥调用 jump.universal，返回固定 dedaocompanion://bridge 路由。
- 普通链接重试也走原生导航；桥不支持时保留 URI 和通知/手动打开兜底。
- BridgeReturn 给前台导航优先窗口；未确认返回才尝试旧路径及显示可点击结果通知。
- 仅在 MainActivity 真正 resumed 后确认成功、取消结果通知；新扫描使旧回跳回调失效。
- MainActivity 使用 singleTask，避免重复刷新累积多个首页。
- 设置说明与诊断补充刷新回跳状态，未改变登录、权益、播放或缓存格式。

## 验证

- 旧路径在 USB 真机复现；新增通知兜底点击后返回伴侣，pending 清除。
- 原生导航接入后，两个开发包试次自动回到伴侣。
- 最终包在 08:19:53、08:20:01、08:20:09 连续三轮检查通过：只点击刷新按钮，
  不用 am start 人为回前台。mCurrentFocus 均为伴侣 MainActivity，
  return_pending=false、return_notice=not_needed、25 条确认成功；三轮 ActivityRecord
  均为同一个实例，任务栈仅一个首页。刷新没有开始播放，官方会话保持 PAUSED。
- `testDebugUnitTest` 27 项、`assembleDebug`、`lintDebug` 通过。
- `node scripts/test-bridge-page.mjs` 验证成功、失败、手动重试、原生桥异常兜底，
  并核对 Kotlin 字符串中的网关占位符未改变。
- 最终 APK：0.4.0-kotlin-rc2 / versionCode 13。
- SHA-256：`bb368aa229f1d368a67ace2503043091e58efd82d3f12766fa5a156abd9bc559`。

此版本仍只在 Kotlin 验收分支，不合并 main、不替换正式下载。

## 检查清单

- [x] 只读检查范围限定在导航互操作，未扩大到账号或内容访问。
- [x] 使用已验证的本机 JADX / ADB 工具；检查入口和关键 Java 导航逻辑。
- [x] 没有修改第三方 APK、做动态 Hook 或变更系统安全设置；不需要执行这些步骤。
- [x] 记录根因、修复、脚本回归和真机前台证据；没有以“接口调用无异常”代替验收。
