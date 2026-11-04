# Phase 4 · C 端首页 Vue3 + Vant4 落地迁移指引

> 作者：fe-engineer ｜ 范围：Healix C 端首页重做的工程落地（不包含其它页面改版）
> 基线：`frontend-user/src/views/HomeView.vue`（2091 行）+ `src/style.css`（199 行）
> 上游：Phase 1 视觉令牌 / Phase 2 信息架构与体验规范 / Phase 3 高保真原型 `c-home-v8.html`（949 行）
> **硬约束**：本文件只写代码 / 写 / 写；**不要修改任何源码**。

---

## 0. 红线复述（来自 team-lead，三条不可破）

1. **全局令牌只做纯新增**：`--hx-shadow`（34 文件引用，HomeView 1250/1344/1406/2060 处）和 `--hx-bg`（含 App.vue:45 全局外壳，14 文件）**保持原值**。
2. **首页层级升级全部写进 `<style scoped>`**：`.card` 用 `--hx-elev-2`、`.l1-card` 用 `--hx-elev-3`、`.home` 容器用 `--hx-bg-flat`。
3. **全局 `.hx-cat` / `.hx-ck` 本轮不改值**；首页差异用 `.home .hx-cat` / `.home .hx-ck` scoped 覆盖。

---

## 1. 令牌接入（精确到行）

### 1.1 追加进 `src/style.css` 的 `:root`（纯新增，零回归）

| 位置 | 动作 |
|---|---|
| `style.css:33`（现有 `--hx-grad: ...` 行之后，`:root` 闭合 `}` 在 `:60` 之前）| 追加全部新增令牌：`--hx-teal-50…900`（10 个）、`--hx-n-50…950`（11 个）、`--hx-danger/warn/info` + `-bg` + `-text`、`--hx-mint/mint-bg`、`--hx-success-text`、`--hx-white/black`、`--hx-fs-*`(9) + `--hx-fw-*`(8) + `--hx-lh-*`(8) + `--hx-ls-*`(3)、`--hx-r-xs…xl/pill`(6)、`--hx-hairline` + `--hx-elev-0…brand`(5)、`--hx-sp-1…8`(8)、`--hx-dur-1…3`(3) + `ease-std/out/in-out`(3)、`--hx-bg-flat` |

**完整令牌块**见 `c-home-v8.html:38-114`（与 Phase 1 §6 同构）；**唯一区别**：原型的 `:root` 是给人参考的演示版，落地版只挑头 6 条说明的 5 类，纯新增，不修改任何旧变量名。

### 1.2 必须留在 HomeView scoped 的（**不要**写进全局 `:root`）

| 用途 | Token | 备注 |
|---|---|---|
| 首页作用域扁平背景 | `--hx-bg-flat` | 实际值就是新增的，但**只在 `.home { background: var(--hx-bg-flat) }` 使用**，全局仍走原 `--hx-bg`（不破其它 14 文件）|
| 卡片阴影分级 | `.home .card { box-shadow: var(--hx-elev-2) }` | L1 用 `--hx-elev-3`、L2 用 `--hx-elev-2`、L3 内嵌走 hairline / `--hx-elev-0` |
| 焦点卡解耦 | `.l1-card[data-state="…"]` 通过 CSS 自定义属性 `--l1-bg / --l1-fg / --l1-shadow` 切换（c-home-v8.html:174-197）| 同一组件承载 6 态，**不要**新增 6 套 class |

### 1.3 兼容层（**不**在本次范围）

`--hx-shadow` / `--hx-bg` / `--hx-grad` / `--hx-radius` 保持 style.css 原值不动；其它视图统一换这些属独立立项，不夹带。

---

## 2. 组件映射表（原型类名 → HomeView.vue 落地）

> 改动类型缩写：**新**=新增组件 **模**=改 template **样**=模 **数**=改 script 数据源
> **本表 ≠ 原型文件每个 class**，只列**与首页重做直接相关**的项。

| # | 原型元素 | 改动 | 涉及行号（HomeView.vue） | 改法 |
|---|---|---|---|---|
| C1 | **`.l1-card`** 健管师焦点卡 | **新 + 模 + 样 + 数** | 模 813–818 区段前新增 `<section class="l1-card" :data-state="managerState">`；数 新增 `managerState`/`managerInfo`/`careHint`/`companionedSince`/`estimatedReplyMin`；样 在 `<style scoped>` 新增整个 `.l1-card` 块（参考 v8.html:174-280）| 5 态：`online`(默认) / `offline` / `unread` / `matching` / `loading` / `failed`（**注：v8 与 Phase 2 是 5+1=6 态**，Loading 与 Failed 各算独立态）。**见 §3 数据源缺口** |
| C2 | **`.gap-24`** 焦点后留白 | **样** | 813–817 区段前加 `<div class="gap-24">` | `.gap-24 { height: var(--hx-sp-6); flex: none; }` 写在 scoped |
| C3 | **`.quick-tile`** L3 健康速记入口横滑 | **新 + 模 + 数** | 模 在原 `.record-card` 屏 1 第 7 位新增 `<section class="quick-row">`（v8 行 310–370）；数 新增 `quickTiles = ref([{key:'bp', title:'血压', icon:'ascend'}, …4 项])` | `overflow-x: auto; scroll-snap-type: x mandatory;`；4 个 tile 96×72；`openRecord(key)` 复用现 HomeView:324 函数 |
| C4 | **`.record-grid`** 屏 2 收紧（去掉 4 个体征） | **数** | 数 HomeView:451-533 `recordEntries` 只保留 5 项生活记录；删除其中 `bmi/bp/glucose/heart` 4 项 | 现状已只有 5 项生活记录（**此项已天然达成**），但需确认体征入口未以其它方式残留 |
| C5 | **`.metric-tile`** 整体可点 | **模 + 样** | 模 HomeView:1121-1150 `<div class="metric-tile">` → `<button class="metric-tile" @click="router.push(`/health?tab=metrics&type=${m.key}`)">`；样 `<button>` 需重置默认样式（`border:0; background:inherit; cursor:pointer;`）| **删除** line 1146 的 `.metric-record` "记录" 按钮（与 C3 重复）；整 tile 替代 |
| C6 | **`.hx-cat` 圆角（首页内）** | **样**（scoped 覆盖）| 样 HomeView scoped 加 `.home .hx-cat { border-radius: var(--hx-r-xs); }` | **不**改 style.css:94 的全局值（CarePlanView 共用） |
| C7 | **🔥 emoji → 线性火苗图标** | **模** | HomeView:961 `<span class="rs-flame" :class="{ dim: !reward.todayActive }">🔥</span>` → 改 `<van-icon name="fire" :class="['rs-flame', { dim: !reward.todayActive }]" />` | van-icon `fire-o` 是线性版；先确认 v4 图标集支持 `fire-o`/`fire`，不支持则用 inline SVG |
| C8 | **🎯 emoji → 靶心图标** | **模** | HomeView:982 `🎯 本周挑战 …` → 改 `<van-icon name="aim" />` | 同上：先 `grep "name=\"fire\"" /frontend-user/node_modules/@vant/icons`，不支持即 inline SVG |
| C9 | **✓ 字符 → van-icon success** | **模** | HomeView:1014 `<span class="hx-ck" :class="{ on: !taskPending(task) }">{{ taskPending(task) ? '' : '✓' }}</span>` → 改 `<van-icon v-else name="success" size="12" />` | 整行 `.todo-item` 改为 `tabindex="0" @keydown.enter="toggleTask(task)"`，6.2 触达区优化的一部分 |
| C10 | **`.progress-fill` 去渐变** | **样** | HomeView:1669 `.progress-fill { background: var(--hx-grad); }` → `.progress-fill { background: var(--hx-teal-600); }` | scoped 内改，不动全局 |
| C11 | **`.more-hint` 文案改写** | **模** | HomeView:1181 改文案为「健康数据看「健康」Tab · 评估 / 随访 / 档案在「我的」Tab」| Phase 2 §1.5 锁定的字句 |

---

## 3. 数据源缺口清单（**需后端提供，不要写假接口**）

> 现有 HomeView 已有的接口（验证自 HomeView:598-723 + CareChatListView:45-79）：
> - `/api/c/v1/profile`（displayName）✅
> - `/api/c/v1/me/care-plan/today`、`/vitals`、`/care-plan/tasks/{id}/checkins`、`/medications/schedule|intakes/batch`、`/assessments/questionnaire-request`、`/notifications/unread-count`、`/care-chat/unread-count` ✅
> - `/api/c/v1/care-chat/threads` / `/contact` / `/unread-count`（CareChatListView）✅
>
> **健管师详情接口整个项目目前没有**（已 grep 全 src 仅 CareChatListView 拿到 `orgName`/`peopleName`/`patientUnreadCount`，无在线状态 / 无角色 / 无头像 / 无电话）。

**L1 焦点卡需要的字段**（Phase 2 §2.6 锁定），对照现有情况：

| 字段 | 类型 | 现有？ | 需后端 |
|---|---|---|---|
| `managerName` | string | ❌ | ✅ 新接口返回（**不**沿用 `threads[0].peopleName`，因可能多线程） |
| `managerRole` | "营养师" 等 | ❌ | ✅ |
| `managerAvatarUrl` | string | ❌ | ✅ |
| `managerPhone` | string | ❌ | ✅ |
| `onlineStatus` | `ONLINE`/`OFFLINE`/`MATCHING` | ❌ | ✅ |
| `unreadCount` | number | ✅ `unreadChatCount` 已有 | 复用 + 重新语义化为"健管师主动关怀未读" |
| `estimatedReplyMin` | number | ❌ | ✅ |
| `careHint` | string | ❌ | ✅ 服务端按时段 + 互动历史生成 |
| `accompaniedSince` | string "5 年" | ❌ | ✅ |
| `matchingEtaHours` | number | ❌ | ✅ |

**结论** — 需后端**新增一个接口**，建议：`GET /api/c/v1/me/manager` 返回上述 9 个字段（除 `unreadCount`）。**前端不要伪造降级**——接口没准备好前 L1 卡直接走 E-ext（loading→failed 兜底，见 Phase 2 §2.3 E-ext）展示"健管师信息加载失败 / 重试"，**不要用线程列表填充假数据**。

---

## 4. 迁移步骤排序（6 步，影响面从低到高）

> 每步标注：影响面 / 验证方式 / 回滚

### Step 1：追加全局令牌（纯新增）— 影响面 = 全 0 站
- `style.css` `:root` 末尾追加 §1.1 全部新 token
- **验证**：`grep -rn "var(--hx-shadow)" frontend-user/src | wc -l` 仍为 34；任一其它页打开视觉零变化
- **回滚**：删除追加块即可

### Step 2：迁移首页文件结构 + 改 `more-hint` 文案 — 影响面 = HomeView
- HomeView.vue 顶部 `script setup` 末尾加 `managerState`/`managerInfo` refs（接口未就绪前恒为 `loading`/`failed`）
- 改 `more-hint` 文案（C11）
- 改 🔥/🎯/✓ 三处 emoji 为 van-icon（C7/C8/C9，需先验证 v4 icon 集）
- **验证**：首页屏 1/屏 2 视觉除三处图标外无变化
- **回滚**：git revert 即可

### Step 3：scoped 样式升级 + .hx-cat 圆角覆盖 — 影响面 = HomeView
- HomeView `<style scoped>` 加 `.home { background: var(--hx-bg-flat); }`（**只覆盖首页，不影响 App.vue 全局外壳**）
- `.home .card { box-shadow: var(--hx-elev-2); }`
- `.home .hx-cat { border-radius: var(--hx-r-xs); }`
- `.progress-fill` 去渐变（C10）
- **验证**：首页有 L1→L2→L3 三层视觉梯度；其它页面零变化（grep 全库确认 `.card` shadow 值未动）
- **回滚**：scoped 内删除这几条

### Step 4：metric-tile 整体可点 + 5 列宫格保留 — 影响面 = HomeView
- 改 `.metric-tile` 为 `<button>`、加跳转路由（C5）；删除 `.metric-record` 按钮
- `recordEntries` 收紧确认（C4）
- **验证**：屏 2 4 个指标块任意点击都能跳 `/health?tab=metrics&type=bp|glucose|bmi|heart`
- **回滚**：把 `<button>` 改回 `<div>`，恢复 `metric-record`

### Step 5：屏 1 新增 L1 焦点卡 + 速记入口横滑 — 影响面 = HomeView（最大改动）
- 新增 `<section class="l1-card" :data-state="managerState">`（C1），含 5+1 态切色（CSS 变量方案）
- 新增 `<section class="quick-row">`（C3），复用 `openRecord()` 与 popup
- **接口未就绪时**：L1 走 loading→failed 兜底；速记入口不受影响
- **验证**：屏 1 高度不超 532px（Phase 2 §1.1 硬指标）；焦点后留白 24px
- **回滚**：删除两 section 即可

### Step 6：触达区 / 对比度 / a11y 收尾 — 影响面 = HomeView + 整站 `:root`
- `.act-btn` 高 26→32px；`.metric-record` 已删除；`.hx-ck` 整行可点
- `.link` 改 `var(--hx-teal-700)`；`.metric-label` 改 `var(--hx-n-700)`（全局加 `.home` 前缀限定首页）
- `:focus-visible` 全局规则（**只**追加在 `:root` 之外用 `*:focus-visible`，**不动全局**；放 HomeView scoped 内对首页生效即可）
- **验证**：屏 1/屏 2 关键文字过 AA 4.5:1；Tab 键可见焦点环
- **回滚**：scoped 内删除覆盖

---

## 5. 性能与质量注意

| 项 | 结论 | 理由 |
|---|---|---|
| **SVG 内联 sprite** | **不推荐**内联整本 sprite | v8.html 用了 inline SVG，但 4×2 = 8 个图标 < 4KB；建议放独立 `<svg>` sprite 文件 + `<use>` 引用，避免 HomeView 单文件膨胀 |
| **CSS 变量运行时成本** | 0 | 浏览器原生支持，`var()` 解析在样式计算阶段完成；CWV 无影响 |
| **`prefers-reduced-motion`** | **必须**写 | HomeView scoped 内加 `@media (prefers-reduced-motion: reduce) { * { transition-duration: 0ms !important; } }`（参考 Phase 2 §6.5）|
| **L1 6 态切换：v-if vs class** | **class 切换**（`:data-state="managerState"`）| 6 态互斥但 DOM 共用骨架；v-if 会反复卸载/挂载 avatar，导致图片闪烁。class 切数据态只换 CSS 变量，最便宜；E→E-ext 时再 v-if 切换 |
| **避免深度 watch** | 已有 `computed` 充分 | HomeView `metricTiles` (144-236) / `recordEntries` (451-533) / `sortedTasks` (139-142) 都是 computed；不需 `watch` |
| **popup 复用** | 直接复用现 popup | 速记入口弹的录体征 popup 与现 HomeView:860-949 是同一个 popup 状态机；不要新建第二个 popup 组件 |
| **`onCareChatEvent` 已订阅** | 复用 | HomeView:618 `Promise.all([... loadUnreadChatCount])`；SSE 事件已有，只需新增 `managerState` 字段变化时重 fetch manager |
| **Vant v4 图标支持** | **待确认** | 落地前 `grep "fire\|aim\|success" frontend-user/node_modules/@vant/icons/lib/index.d.ts` 确认；不支持则 inline SVG（参考 c-home-v8.html:480-520 inline sprite）|

---

## 6. 待确认（不假装知道）

- van-icon 是否支持 `fire-o` / `aim` / `chart-trending-o`（c-home-v8.html 用到）
- 健管师信息接口 `/api/c/v1/me/manager` 是否已排期 / 后端工时
- App.vue:45 `background: var(--hx-bg)` 是否在本次同步换 `--hx-bg-flat`？**建议：否**，避免影响全局外壳一致性
- 屏 1 高度 532px 实测：在 lg 断点（430px）下 L1 112px + 24px 焦点后留白 + 220px 待办 + 20px + 80px 速记 + 12px = 468px，余 264px；md (390px) 同理余 140px；xs (360px) 速记改为 3+1 紧凑后余 200px。**不动**