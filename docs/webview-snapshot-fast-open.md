# WebView 页面快照秒开方案

本文定义 2Libra Android 客户端 WebView 页面的"快照秒开 + 后台刷新"功能：页面加载成功后持久化首屏截图，下次打开先展示截图实现秒开，同时后台加载真实页面，成功后切换到新页面，失败时保持截图并提示。

> 状态：方案定稿，待实施
> 更新日期：2026-08-28
> 适用工程：`com.suixin.sx2libra`
> 关联文档：[`android-client-development.md`](./android-client-development.md)

## 需求定稿

| 项 | 结论 | 依据 |
| --- | --- | --- |
| 首要目的 | 秒开体验 + 离线可看 | 两者组合，静态资源省流量由 WebView 原生磁盘缓存覆盖 |
| 体验权衡 | 秒开期间展示旧内容、加载完成后整页切换（轻微闪烁/回顶部）可接受 | 用户确认 |
| 加载失败 | toast 提示，保持截图展示，不弹全屏错误页（有截图时） | 用户确认 |
| 依赖策略 | 不引入开源库；本方案不需要 `shouldInterceptRequest` 拦截缓存 | 方案讨论结论 |

## 方案选型

候选方案对比（讨论定稿记录）：

| 方案 | 结论 | 原因 |
| --- | --- | --- |
| **截图盖层（本文）** | **采纳** | 零安全改动、无 MHT 兼容坑、不动 `RoutePolicy`；秒开内容为不可交互假图 |
| MHT 快照（`saveWebArchive`） | 排除 | `file://` 被现有安全配置（`allowFileAccess=false`）与 `RoutePolicy` 双拦截；MHT over `WebViewAssetLoader` 需 spike 验证；部分 ROM 中文/懒加载图片缺失 |
| 拦截缓存（`shouldInterceptRequest`） | 排除 | ~200 行自写或 vendored 开源库；动态 HTML（登录态/CSRF）不可缓存，只能缓存静态资源，无法独立满足秒开+离线 |
| 双 WebView 预载 | 排除 | 内存翻倍，状态同步复杂 |

截图方案唯一损失：秒开窗口期（约 1~2 秒）内页面不可点击。论坛列表页该窗口内用户以浏览为主，可接受。未来若需"离线可交互翻旧帖"，再评估 MHT 升级，与本方案不冲突。

## 总体流程

```
打开页面（LibraWebViewHost.start）
 ├─ savedState 非空（进程/Fragment 恢复）
 │    → 不启用快照，走 webView.restoreState() 原路径
 ├─ 无截图文件 → 直接 loadUrl(url)，原路径
 └─ 有截图文件 → ImageView 盖层显示截图（秒开，瞬时）
                  WebView 同时 loadUrl(url)（后台加载）
  ├─ onPageCommitVisible → 淡出盖层（约 150ms），露出新页面
  │    （若淡出后页面半渲染，降级为 onPageFinished 时淡出，见 spike 项）
  ├─ onPageFinished      → 截图落盘，覆盖同 URL 旧图（异步，失败静默）
  └─ 主文档 onReceivedError
      ├─ 盖层在展示 → toast「网络不佳，已展示上次内容」，盖层保持
      └─ 无盖层     → 现有 WebPageError 错误 UI 原路径
```

## 状态设计

`WebPageUiState` 增加快照状态字段（建议枚举，避免多布尔组合）：

```kotlin
enum class WebPageSnapshot {
    NONE,      // 无截图或不启用（恢复态）
    SHOWING,   // 盖层展示中，WebView 后台加载
    FALLBACK,  // 加载失败，截图保持，需 toast
}
```

状态迁移规则（由 `WebPageViewModel` 驱动，ViewModel 不持有 View）：

| 触发 | 迁移 |
| --- |
| `start` 时截图文件存在且非恢复态 | `NONE → SHOWING`（读文件在 View/数据层，结果回调进 ViewModel） |
| `onPageCommitted`（或 `onPageFinished`，按 spike 定稿） | `SHOWING → NONE`（View 层据此淡出） |
| `onLoadingError(isMainFrame=true)` 且当前为 `SHOWING` | `SHOWING → FALLBACK` |
| `onLoadingError` 且无盖层 | 不变，走现有 `error` 字段 |
| toast 已展示 | `FALLBACK → SHOWING`（保持展示，清除一次性 toast 标记） |

截图**落盘**动作在 View 层执行（`Fragment`/`LibraWebViewHost` 观察 `isLoading=false && error==null` 时触发），磁盘 IO 走 `Dispatchers.IO`，失败静默降级（下次无快照可用，不影响功能）。

## 持久化设计

| 项 | 设计 |
| --- | --- |
| 文件 key | `SHA-256(normalize(url))` 前 16 位 hex，同 URL 覆盖 |
| 目录 | `context.filesDir/web_snapshots/`（私有目录，截图含登录态头像等个人信息，禁止落 external storage） |
| 格式 | WebP quality 85（`minSdk 26` 原生支持），单张约 100~300KB |
| 截取范围 | 可视区（`webView.draw(canvas)`，bitmap 尺寸 = 当前 `width × height`） |
| 容量上限 | 目录 20MB；超过时按 `lastModified` 升序删除旧文件（懒清理：进程启动后台线程执行一次，不做实时监控） |
| 读取 | `BitmapFactory` 按 ImageView 尺寸 `inSampleSize` 降采样，避免大图内存 |

## 落地点（现有代码映射）

| 现有代码 | 改动 |
| --- | --- |
| [`WebPageUiState.kt`](../app/src/main/java/com/suixin/sx2libra/ui/web/WebPageUiState.kt) | 增加 `WebPageSnapshot` 枚举与 `snapshot` 字段 |
| [`WebPageViewModel.kt`](../app/src/main/java/com/suixin/sx2libra/ui/web/WebPageViewModel.kt) | 按状态迁移表更新各回调；快照文件存在性查询经 Repository 异步获取 |
| [`LibraWebViewHost.kt`](../app/src/main/java/com/suixin/sx2libra/web/LibraWebViewHost.kt) | `start(savedState)` 增加快照存在性判断入口；暴露 `captureSnapshot(url): Bitmap?`（`webView.draw`）；不改动导航与安全逻辑 |
| [`ForumMenuPageFragment.kt`](../app/src/main/java/com/suixin/sx2libra/ui/posts/ForumMenuPageFragment.kt) | 布局加 ImageView 盖层（`fitStart`，背景与 WebView 一致白色），观察 `snapshot` 状态淡出/保持；`FALLBACK` 时 toast |
| 新增 `data/local/WebSnapshotLocalDataSource.kt` | 存/读/清理截图文件，纯文件操作，无 Android UI 依赖 |
| `WebSessionRepository` 层 | 不动；快照数据源独立，避免污染会话仓库职责 |

布局注意：ImageView 需 `importantForAccessibility="no"` 并设置 `contentDescription`（如「页面加载中预览」）；`scaleType="fitStart"` 保持比例应对横竖屏尺寸变化，轻微留白可接受。

## 关键实现细节与边界

1. **截图时机**：`onPageFinished` 后立即截。2Libra 为服务端渲染，此时首屏内容已渲染。若实测发现图片占位未加载完，加 300ms 延迟再截，不做更复杂的图片完成监听。
2. **淡出时机 spike**：先按 `onPageCommitVisible` 淡出（更早露出新内容）；若实测露出半渲染页面，改 `onPageFinished` 淡出。二选一定稿后写回本文。
3. **恢复态跳过**：`LibraWebViewHost.start(savedState)` 中 `savedState != null` 时 `restoreState` 成功即不启用快照，避免 WebView 自恢复与截图抢屏。
4. **假图窗口期无交互**：盖层期间用户点击无响应，属已知取舍；toast 仅在失败时出现，成功路径用户无感。
5. **内存**：bitmap 用后置空（ImageView drawable = null / `recycle`），读取走降采样；截图落盘在 IO 线程，不阻塞主线程。
6. **隐私**：截图可能含登录态昵称、头像，只存 `filesDir`，系统私有目录，卸载即清除。
7. **错误页被盖住**：离线时 WebView 底层显示系统错误页，被截图盖住属预期；toast 说明当前为缓存内容。
8. **多页面**：`LibraWebViewHost` 每实例一个 `initialPageUrl`，key 按各自 URL 独立存取，天然支持帖子页各 Tab（首页/今日热议/近期热议/新发表）各自秒开。

## 风险与降级

| 风险 | 降级 |
| --- | --- |
| 截图与当前屏幕尺寸不一致（横竖屏切换） | `fitStart` 保持比例，留白可接受；不做按尺寸分组存储 |
| 截图过期内容误导用户（帖子已删/价格已变） | 秒开窗口短，且加载成功即切换；失败 toast 已说明"上次内容" |
| `webView.draw` 在硬件加速异常机型返回黑图 | 落盘前校验 bitmap 非全黑（抽样像素），异常则不覆盖旧截图 |
| 快照目录膨胀 | 20MB 上限 + 启动清理 |

## 实施阶段

| 阶段 | 内容 | 验证 |
| --- | --- | --- |
| 1 | `WebSnapshotLocalDataSource`（存/读/清理）+ 单元测试（key 稳定性、LRU 清理、降采样） | 测试通过 |
| 2 | `WebPageUiState`/`WebPageViewModel` 状态迁移 | 状态迁移单元测试 |
| 3 | `ForumMenuPageFragment` 盖层 + toast + `LibraWebViewHost.captureSnapshot` | 手工验收 |
| 4 | 淡出时机 spike 定稿、黑图校验、启动清理 | 弱网/断网/横竖屏手工清单 |

## 验收标准

- 首次打开某菜单页：正常加载，`filesDir/web_snapshots/` 出现对应文件。
- 第二次打开：截图瞬时展示（无可感知白屏），WebView 后台加载，完成后轻微切换至新页面，回顶部可接受。
- 断网第二次打开：截图保持展示 + toast「网络不佳，已展示上次内容」，无全屏错误页。
- 断网首次打开（无截图）：现有错误 UI 原路径，无 toast。
- 进程被杀后恢复（restoreState 路径）：不出现截图盖层，页面状态由 WebView 自恢复。
- 卸载重装：无快照残留（filesDir 私有目录特性）。
- 快照目录超过 20MB 后启动：旧文件被清理，总大小回落。
