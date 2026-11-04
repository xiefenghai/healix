# Phase 1 · C 端首页视觉方向与设计令牌升级方案

> 作者：visual-architect ｜ 项目：Healix C 端首页重做 ｜ 基线文件：`frontend-user/src/style.css`（199 行）+ `frontend-user/src/views/HomeView.vue`（2091 行）
> 原则：**站在真实 token 上升级，不另立体系**。主色 `#0e9f83` 锁定不变；一切建议值均可从现有 `--hx-*` 推导。
> 本阶段只出方向与规范，**不改源码**，落地在 Phase 3。

---

## 0. 现状诊断（基于真实代码，非印象）

| 现象 | 代码证据 | 结论 |
|---|---|---|
| 8 个模块同一规格 | `.card { border-radius: var(--hx-radius:20px); box-shadow: var(--hx-shadow) }`（HomeView 1401–1407），`record-card/todo-card/med-card/metrics-card/report-card/assess-card` 全部复用 | 圆角与阴影**零梯度** → 无视觉焦点 |
| 全站同一阴影 | 仅 `--hx-shadow: 0 10px 26px rgba(19,34,46,.06)`（style.css 32），`.service`/`.icon-btn`/`.join-card` 也复用它 | 全站一个 elevation |
| 品牌色被铺成大面积渐变 | `--hx-grad: linear-gradient(90deg,#14b394,#0e9f83)` 用于 `.act-btn.primary`、`.van-button--primary`、`.progress-fill`、头像 | 典型"AI 渐变块"配方 |
| 背景是双层 radial 光斑 | `--hx-bg`（style.css 24–27）= 2 个 `radial-gradient` + 1 个三段 `linear-gradient` | AI slop 清单里的头号配方 |
| emoji 当图标 | `.rs-flame` 用 🔥（HomeView 961）、挑战条用 🎯（982）、`.hx-ck.on` 用 `✓` 字符（1014） | 图标体系不统一 |
| 字号过密 + 极小字号泛滥 | 单文件出现 10/11/12/13/14/15/16/18/22/24px 十档；10–11px 用于 badge/flag/metric-meta | 中老年用户可读性风险 |
| 健管师只在图标格 | `services` 数组中 `care-chat` 是 4 个 36px 图标格之一（HomeView 559–564，1347–1355） | 核心差异化视觉权重 = 普通工具入口 |
| 可读性红线 | `.metric-label` 用 `--hx-muted #6b7a87`（≈ 4.4:1 on #fff）、`.link` 用 `--hx-teal #0e9f83`（≈ 3.3:1） | 小号正文/链接未过 AA 4.5:1 |

---

## 1. 美学方向定位

### 一句话方向
> **「诊室级的秩序感 × 有人在旁的陪伴感」——用临床级的克制与可读性建立信任，用唯一一个人格化锚点（专属健管师）打破工具的冰冷。**

### 判断理由
1. Healix 的真实差异化是"**每个慢病患者有专属健管师**"，而不是又一个指标工具。视觉必须让"人"占据首屏第一眼，否则产品主张与界面不符。
2. 目标人群是慢病中老年 + 其家属，核心诉求是 **可信（数据准、不乱）** 与 **被照顾（不孤单、有回应）**。前者要"医用级秩序"，后者要"温度"。二者不冲突：**用 Cold 的秩序打底，用 Warm 的单一焦点点睛**。
3. 此前 v1–v7 失败的原因不是不够"新"，而是**另立体系 + 堆装饰**（渐变块 / 光斑 / 同规格重阴影），读起来像模板而非"这家的产品"。精修升级的胜负手是**梯度与克制**，不是新增元素。

### 设计原则（5 条，可执行）

| # | 原则 | 具体含义 | 违反判定 |
|---|---|---|---|
| P1 | **一屏一焦点** | 首屏只允许 1 个"最高视觉权重"元素（健管师卡）；其余模块降为秩序网格。层级靠**尺度 + 色彩 + 留白**三层对比建立，**不靠加阴影** | 首屏出现 2 个同权重色块 = 违规 |
| P2 | **色为信息，不为装饰** | teal=品牌+主行动+正常；橙=待办；红=异常；蓝=信息。除语义外不新增色块；**禁止用渐变色块铺底** | 出现"为了好看"的品牌色填充 = 违规 |
| P3 | **临床级可读** | 正文 ≥13px；正文/链接对比度 ≥4.5:1；数值用 `tabular-nums`；小号 teal 文字一律用 `teal-700` 以上 | 10px 承载语义文案 = 违规 |
| P4 | **圆角成阶梯、阴影成阶梯** | 圆角/阴影按"信息容器层级"分 3 档；**层级越高，留白越多、阴影越克制**。一级卡以下优先用 1px 边线而非阴影 | 全站只有一个 radius / 一个 shadow = 违规 |
| P5 | **呼吸优先于描边** | 用间距与底色分组，模块间距 > 卡内间距；不靠分隔线/描边把界面填满 | 卡内出现多条水平分隔线填满 = 违规 |

---

## 2. 设计令牌升级表

### 2.1 品牌色 teal —— 引入语义色阶（hue≈168，由 `#0e9f83` 向两端扩展）

现有已存在 `teal/teal-soft/teal-light/teal-deep` 与 `#14b394`（渐变起点），**只是没被组织成阶**。升级=补齐缺口并命名，主色不动。

| 新 Token | 建议值 | 来源 / 说明 | 旧值 |
|---|---|---|---|
| `--hx-teal-50` | `#effaf6` | 新增，最浅底（焦点卡浅底备选） | — |
| `--hx-teal-100` | `#e3f6ef` | = 现有 `--hx-teal-light` | `#e3f6ef` |
| `--hx-teal-200` | `#bfe9db` | 新增，边线/弱分隔 | — |
| `--hx-teal-300` | `#86d7c1` | 新增，禁用态/装饰下限 | — |
| `--hx-teal-400` | `#3fbfa3` | = 现有 `--hx-teal-soft` | `#3fbfa3` |
| `--hx-teal-500` | `#14b394` | = 现有渐变起点 `#14b394` | 硬编码 |
| **`--hx-teal-600`** | **`#0e9f83`** | **= 主色 `--hx-teal`（锁定，不动）** | `#0e9f83` |
| `--hx-teal-700` | `#0b7a66` | = 现有 `--hx-teal-deep`，**小号文字安全色（5.26:1）** | `#0b7a66` |
| `--hx-teal-800` | `#0a6152` | 新增，深底/按下态 | — |
| `--hx-teal-900` | `#084a3e` | 新增，深色皮肤主底 | — |

> **可读性红线（实测）**：`teal-600 #0e9f83` on `#fff` ≈ **3.3:1** → 仅可用于 ≥18px 文字或图形化元素；`teal-700 #0b7a66` on `#fff` ≈ **5.26:1** → 小号正文/链接请用它。`.link` 当前用 `--hx-teal`，建议 Phase 3 改 `teal-700`。

### 2.2 中性色阶 —— 收敛现有散落的灰（现有硬编码 `#7a8b99/#9aa8b5/#c5ced6/#eef2f4/#f5f8fa/#f0f4f6` 各为其一）

| 新 Token | 建议值 | 用途 | 旧值（散落） |
|---|---|---|---|
| `--hx-n-50` | `#f7fafb` | 内嵌块底（record-tile） | `#f7fafb` |
| `--hx-n-100` | `#f0f4f6` | 分隔线 / 内嵌块底 | `#f0f4f6`、`#f5f8fa` |
| `--hx-n-200` | `#e6ecf0` | 一级卡边线（替代重阴影） | `#eef2f4`、`#d5e4e4` |
| `--hx-n-300` | `#d5dee4` | 控件描边 / 禁用边 | `#d5e4e4`、`#d9e4e4` |
| `--hx-n-400` | `#c5ced6` | 占位/空值文字 | `#c5ced6` |
| `--hx-n-500` | `#9aa8b5` | 三级文字（仅 ≥12px 装饰） | `#9aa8b5` |
| `--hx-n-600` | `#7a8b99` | 参考值/元信息 | `#7a8b99` |
| `--hx-n-700` | `#5b6a77` | **次级正文（AA 安全，替代 muted）** | 新增（`muted #6b7a87` ≈4.4:1 略低） |
| `--hx-n-800` | `#3d4b58` | 强次级标题 | 新增 |
| `--hx-n-900` | `#13222e` | = 现有 `--hx-ink` / `--hx-text` | `#13222e` |
| `--hx-n-950` | `#0c1720` | 深色皮肤底 | 新增 |

> `--hx-muted #6b7a87` 保留不删（兼容），但**新代码次级正文改用 `--hx-n-700`**，可读性从 4.4:1 提到 ≈5.9:1。

### 2.3 语义色（保持现有，仅补"深色可读文本"命名）

| 语义 | 主色 | 浅底 | 深色文字 | 旧值 |
|---|---|---|---|---|
| 危险/异常 | `--hx-danger: #e0435a` | `--hx-danger-bg: #fde7ea` | `--hx-danger-text: #a8243a` | 同（`--hx-red*`） |
| 待办/提醒 | `--hx-warn: #e5a100` | `--hx-warn-bg: #fff3da` | `--hx-warn-text: #8a5c00` | 同（`--hx-orange*`） |
| 信息 | `--hx-info: #5b8def` | `--hx-info-bg: #e9f0fd` | `--hx-info-text: #2c4f8f` | 同（`--hx-blue*`） |
| 正常/达标 | — | `--hx-teal-100` | `--hx-teal-700` | `#0b7a66` |

### 2.4 字体阶梯 —— 十档收敛为八档 + 字重纪律

| 新 Token | 字号/行高/字重/字距 | 用途 | 旧值（现状） |
|---|---|---|---|
| `--hx-fs-display` | 28 / 1.2 / 900 / -0.02em | 焦点卡主数值、问候名 | hello 22/900 |
| `--hx-fs-num-xl` | 24 / 1.1 / 900 / -0.02em | 指标数值 | metric-value 24/900 ✓ |
| `--hx-fs-num-lg` | 18 / 1.1 / 800 / -0.01em | 火苗天数、服药时段 | rs-days 18/800 ✓ |
| `--hx-fs-h1` | **17** / 1.35 / **800** / -0.01em | 卡片标题 h2 | h2 18/**900** ↓ |
| `--hx-fs-h2` | 15 / 1.4 / 700 / 0 | 次级标题（今日执行） | exec-label 13/800 ↑ |
| `--hx-fs-body` | **14** / 1.5 / 600 / 0 | 列表项标题/正文 | 13–14 混用，收敛 |
| `--hx-fs-body-sm` | 13 / 1.5 / 500 / 0 | 副标题/描述 | sub 13、section-sub 12 ↑ |
| `--hx-fs-caption` | 12 / 1.45 / 600 / 0 | 标签/元信息 | 11–12 混用 |
| `--hx-fs-mini` | **11** / 1.4 / 700 / 0 | 极小徽章（**下限，语义文案禁止更小**） | badge 10、flag 10 ↑ |

> **字重纪律**：只用 500 / 600 / 700 / 800 / 900 五档，且 900 **只给 ≥18px 的数字或问候名**；标题固定 800；正文 600；辅助 500。禁止"标题 900 + 数值 900 + 按钮 900"全上重。基线字体族保持 `"PingFang SC", "Hiragino Sans GB", "Noto Sans SC", system-ui, sans-serif`（不引入新字体，保证 H5 一致渲染）。

### 2.5 圆角阶梯

| 新 Token | 建议值 | 用途 | 旧值 |
|---|---|---|---|
| `--hx-r-xs` | 8 | 类别标签 `.hx-cat` / van-tag | 8 ✓ |
| `--hx-r-sm` | 12 | 内嵌 block、图标容器、次级按钮 | 12 ✓ |
| `--hx-r-md` | 14 | 内嵌 tile（record-tile / metric-tile） | 14 ✓ |
| `--hx-r-lg` | **20** | 一级内容卡（`.card`） | 20 ✓ |
| `--hx-r-xl` | **28** | **首屏焦点卡 / round 弹层** | 24（dialog/popup）↑ |
| `--hx-r-pill` | 999 | 状态徽章 / 主按钮 / 勾选 | 999 ✓ |

> 关键不是改数值，而是**让阶梯可见**：内嵌元素 ≤14，一级卡 20，**全页仅 1 个焦点卡用 28**。三档同时出现，圆角本身即层次信号。

### 2.6 阴影 / 高度阶梯（替代"全站同一 `--hx-shadow`"）

| 新 Token | 建议值 | 用途 |
|---|---|---|
| `--hx-elev-0` | `none` + `--hx-hairline: 1px solid var(--hx-n-200)` | 内嵌 tile / 列表行 |
| `--hx-elev-1` | `0 1px 2px rgba(19,34,46,.04), 0 1px 1px rgba(19,34,46,.03)` | 次级卡（service / report） |
| `--hx-elev-2` | `0 6px 16px rgba(19,34,46,.06), 0 1px 3px rgba(19,34,46,.04)` | 一级内容卡（≈ 旧 `--hx-shadow` 收敛） |
| `--hx-elev-3` | `0 16px 32px rgba(14,159,131,.14), 0 2px 8px rgba(19,34,46,.06)` | **仅首屏焦点卡（teal 染色）** |
| `--hx-elev-brand` | `0 6px 14px rgba(14,159,131,.24)` | 主行动按钮 |

> **规则**：同一屏内 elevation 层级 ≤ 2；一级卡以下优先 `elev-0/1` 或 hairline；**唯一允许 teal 染色阴影的是焦点卡与主按钮**。

### 2.7 间距栅格（4px 基准）

| Token | 值 | Token | 值 |
|---|---|---|---|
| `--hx-sp-1` | 4 | `--hx-sp-5` | 20 |
| `--hx-sp-2` | 8 | `--hx-sp-6` | 24 |
| `--hx-sp-3` | 12 | `--hx-sp-7` | 32 |
| `--hx-sp-4` | 16 | `--hx-sp-8` | 40 |

> 规则：卡内块间距 `sp-3(12)`；模块间距 **`sp-5(20)`（现状 14 偏挤，拉大形成呼吸）**；焦点卡与后续模块间距 `sp-6(24)`（"焦点后留白"）；页面左右边距 `sp-4(16)`。

### 2.8 动效时长与缓动

| Token | 值 | 用途 |
|---|---|---|
| `--hx-dur-1` | 120ms | 触摸反馈（scale .96）、勾选、hover 色变 |
| `--hx-dur-2` | 200ms | 常规状态切换、徽章出现 |
| `--hx-dur-3` | 320ms | 进度条、展开/折叠 |
| `--hx-ease-std` | `cubic-bezier(.2,0,0,1)` | 进入/状态 |
| `--hx-ease-out` | `cubic-bezier(.16,1,.3,1)` | 位移/展开 |
| `--hx-ease-in-out` | `cubic-bezier(.4,0,.2,1)` | 循环/进度 |

> 全局遵守 `@media (prefers-reduced-motion: reduce)` → 时长置 0。数值变化用 `tabular-nums` 防止跳动。

### 2.9 深色皮肤适配（映射，非本阶段落地）

浅色 token → 深色映射规则：`teal-50 ↔ teal-900`、`teal-100 ↔ teal-800`、`n-50 ↔ n-950`、一级卡底 `#fff → #13222e`、边线 `n-200 → #26333f`。所有新 token 均为**语义命名**，深色只需覆盖一层 CSS 变量，不需改组件。**本阶段仅预留结构，不实现。**

---

## 3. 组件视觉规范

约定：状态 = 默认 / hover / active / disabled / 异常。

### 3.1 Card（三级）
| 层级 | 尺寸/圆角 | 底色/边线 | 阴影 | 内边距 | 标题 |
|---|---|---|---|---|---|
| **L1 焦点卡**（健管师） | 全宽 · `r-xl 28` | `teal-700→800` 实底（**无渐变**）或 `teal-50` 浅底 | `elev-3`（teal 染色） | `20` | 白字 / 17·800 |
| **L2 一级内容卡** | 全宽 · `r-lg 20` | `#fff` + 无 | `elev-2` | `16` | `n-900` / 17·800 |
| **L3 次级/内嵌卡** | 全宽 · `r-md 14` | `n-50` 底 或 `#fff`+`hairline` | `elev-0/1` | `12–14` | `n-900` / 14·700 |

状态：L2 `:active` → `transform: scale(.995)` + `elev-1`（120ms）；L3 无位移。禁用：`opacity:.55`。

### 3.2 主按钮 / 次按钮
| | 主（primary） | 次（ghost） |
|---|---|---|
| 高度 | 30（列表内）/ 44（弹层 block） | 同左 |
| 圆角 | `pill 999` | `pill 999` |
| 底色 | `teal-600` **实底**（渐变仅限 ≤32px 高的小按钮时可选） | `#fff` |
| 文字 | `#fff` / 13·700 | `n-700` / 13·700 |
| 边线 | 无 | `1px solid n-300` |
| 阴影 | `elev-brand` | 无 |
| hover | `teal-700` | 底 `teal-50` + 边 `teal-300` + 字 `teal-700` |
| active | `scale(.96)` 120ms | 同左 |
| disabled | `opacity:.55` + `cursor:not-allowed` | 同左 |

> **反模板**：主按钮不再大范围用 `linear-gradient(90deg,#14b394,#0e9f83)`；改实底 `teal-600`，需要层次时用 `teal-700` 按下态。

### 3.3 分类标签 `.hx-cat`
| 项 | 值 |
|---|---|
| 尺寸 | 高 ~19 · padding `2px 8px` |
| 圆角 | `r-xs 8`（**不再用 999 胶囊**） |
| 字号 | 11 · 800 |
| 底色/字色 | exercise/diet `teal-100/teal-700`；water `water-soft/water`；sleep `sleep-soft/sleep`；mood `mood-soft/mood`；other `n-100/n-700` |
| 状态 | 无独立交互态；选中态（如未来筛选用）加 `1px solid currentColor` |

### 3.4 勾选 `.hx-ck`
| 项 | 值 |
|---|---|
| 尺寸 | 22×22 · 圆 `50%` |
| 默认 | `border: 2px solid n-300` · 底 `#fff` |
| 选中 `.on` | 底/边 `teal-600` · 内容用**图标字符**（替代 `✓` 文本）`#fff` 12·800 |
| active | `scale(.9)` 120ms |
| disabled | `border-color: n-200` · `opacity:.5` |
| 异常 | 不适用（勾选为完成语义） |

### 3.5 进度条
| 项 | 值 |
|---|---|
| 轨道 | 高 6 · `r-pill` · 底 `n-100`（替代 `#e8f4f4`） |
| 填充 | `teal-600` 实底（**取消渐变**）· `r-pill` |
| 高度 | 100% |
| 过渡 | `width var(--hx-dur-3) var(--hx-ease-in-out)` |
| 达标(100%) | 填充 `teal-600`；文案由 `teal-700` 承载 |
| disabled/加载 | 可选 `teal-300` 表示进行中 |

### 3.6 指标块 `.metric-tile`
| 项 | 值 |
|---|---|
| 尺寸 | 2×2 网格 · gap 10 |
| 圆角/内边距 | `r-md 14` · `12 12 10` |
| 默认底 | `n-50`（替代杂乱 `#f5f8fa`）· 无阴影 |
| 异常 `.abnormal` | 底 `danger-bg` · **无阴影** · 数值 `danger-text` |
| label | 12·600 · `n-600` |
| 数值 | 24·900 · `tabular-nums` · `n-900`（异常时 `danger-text`） |
| 单位 | 11·500 · `n-600` |
| 状态胶囊 `.metric-flag` | 高 16 · `r-pill` · 10·700；正常 `teal-100/teal-700`；异常 `danger-bg/danger-text` |
| 记录按钮 | 高 22 · `r-pill` · 11·700 · `teal-50/teal-700`；异常 `danger-bg/danger-text` |
| active | 记录按钮 `scale(.96)` 120ms |

### 3.7 徽章 / 胶囊
| 类型 | 尺寸/圆角 | 字号 | 配色 |
|---|---|---|---|
| 计数徽章 `.badge` | min-w 16 · h 16 · `r-pill` | 11·700 | `danger` 底 / `#fff` 字 |
| 等级 `.rs-lv` | h 16 · `r-pill` | 11·800 | `teal-600` 底 / `#fff` 字 |
| 状态胶囊 `.tag` | `2px 8px` · `r-xs 8`（非胶囊）| 11·700 | `teal-100` / `teal-700` |

### 3.8 图标容器
| 场景 | 尺寸 | 圆角 | 底/色 |
|---|---|---|---|
| 顶栏圆形按钮 `.icon-btn` | 40×40 | 50% | `#fff` · `teal-600` · `elev-1` |
| 服务图标 `.service-icon` | 36×36 | `r-sm 12` | tone 语义（teal/mint/amber/violet 各用对应 soft 底 + 600 字） |
| 焦点卡头像 | **56×56** | 50% | 头像图 / `teal-800` 首字底 · `#fff`·2px `teal-100` 环 |
| 内嵌图标 | 32×32 | `r-sm 12` | 对应 soft 底 |

> 图标一律用 `van-icon` 线性图标或 SVG；**统一尺寸档 16 / 20 / 24**，禁止 emoji。

---

## 4. 首屏视觉焦点方案

### 第一眼要看到什么
> **"我的健管师"卡（L1 焦点卡）——一个具体的人，而不是一个工具格。**

支撑理由：产品差异化核心是"专属健管师"；把它从第 4 块图标格提到首屏第 1 位，是**唯一能把产品主张落到像素上**的动作。

### 3 级层级具体取值

| 层级 | 元素 | 圆角 | 底色 | 阴影 | 主尺度 | 字重 |
|---|---|---|---|---|---|---|
| **L1** | 健管师卡 | **28** | `teal-700` 实底（备选 `teal-50`） | **`elev-3` teal 染色** | 头像 **56px** | 姓名 17·800 / 陪伴语 13·500 |
| **L2** | 今日待办 / 今日记录 / 今日用药 | **20** | `#fff` | `elev-2` | 标题 17·800 | 数值 24–28·900 |
| **L3** | 常用服务格 / record-tile / metric-tile | **12–14** | `n-50` 或 `#fff`+hairline | `elev-0/1` | 图标容器 36px / 标签 11·700 | 正文 13–14·600 |

### 建立层级的三手段（缺一不可）
1. **尺度对比**：L1 头像 56px ↔ L3 图标 36px ↔ L3 标签 11px，形成 **5×** 跨度，眼睛有明确落点。
2. **色彩对比**：**全页只有 L1 使用品牌深色填充（teal-700 实底）**；L2 纯白；L3 浅灰。色彩权重 1 个深块 + 其余浅色，第一眼必然落在深块。
3. **留白层级**：L1 与 L2 间距 `24`（焦点后留白），L2 内部块间距 `12`。**留白差就是层级差**——不靠加线、不靠加阴影。

### 两版焦点卡视觉（择一，推荐 B）
- **方案 A（保守·浅底）**：`teal-50` 底 + `1px teal-200` 边 + `56px` 头像 + 姓名 `teal-800` + 主按钮 `teal-600` 实底。适合"更临床、更克制"。
- **方案 B（推荐·人格化）**：`teal-700` 实底（**零渐变**，仅左上 12% 白色单点柔光）+ 白色文字 + `56px` 圆头像 + 右上"在线" `teal-300` 小圆点 + 内嵌白描边胶囊「去沟通」。情绪上直接对应"有专属健管师在照顾我"，且与其余白卡形成决定性对比。**注意：绝不使用 v1–v7 那种大面积双向渐变。**

> 依赖说明：焦点卡**承载内容**由 Phase 2（信息架构）定义，本阶段只锁定**视觉容器规格**。

---

## 5. Do & Don't 清单（反 AI slop）

| # | ❌ Don't | ✅ Do（替代做法） |
|---|---|---|
| 1 | **emoji 当功能图标**（🔥/🎯/`✓` 字符混用，见 HomeView 961/982/1014） | 统一 `van-icon` 线性图标或 SVG；火苗用线性火苗图标 + 数字徽章；勾选用图标字符 |
| 2 | **左侧 4px 彩色 accent 竖条**当"设计感" | 类别用**整卡浅色底 + 32px 图标容器**（如体检类卡用 `teal-50` 底 + 图标区）表达 |
| 3 | **双层 radial 光斑背景**（现 `--hx-bg` 正是 2 radial + 3 段 linear） | 统一为单段极浅线性或纯色：`linear-gradient(180deg,#f2faf7,#f4fbf8)`；焦点靠**局部单点光**而非全屏光斑 |
| 4 | **全站复用同一 `--hx-shadow`** | 3 级 elevation ladder（§2.6），一级卡以下优先 hairline |
| 5 | **品牌色大面积渐变铺底**（`#14b394→#0e9f83` 用于整块/头像） | 渐变仅限 ≤32px 高的小按钮；品牌面用 `teal-600` 实底或 `teal-50` 浅底 |
| 6 | **字重全上 900**（标题/数值/按钮都 900） | 900 只给 ≥18px 数字与问候名；标题 800；正文 600；辅助 500（§2.4） |
| 7 | **999px 胶囊泛滥**（连分类标签也胶囊） | 胶囊只用于状态徽章/主按钮/勾选；类别标签用 `r-xs 8` |
| 8 | **用纯灰块填满界面**当"简洁" | 灰底只用于**承载内容分组**；无内容处留白 |
| 9 | **10px 承载语义文案** | 语义文案下限 11px，正文 ≥13px（§2.4） |
| 10 | **为了"高级"加装饰光/玻璃拟态/描边** | 层级靠尺度+色彩+留白；玻璃仅保留顶栏/底栏 `backdrop-filter` |

---

## 6. 设计令牌落地块（Phase 3 可直接引用）

```css
:root {
  /* ═══ 品牌色阶 teal（hue≈168，主色锁定）═══ */
  --hx-teal-50:  #effaf6;
  --hx-teal-100: #e3f6ef;
  --hx-teal-200: #bfe9db;
  --hx-teal-300: #86d7c1;
  --hx-teal-400: #3fbfa3;
  --hx-teal-500: #14b394;
  --hx-teal-600: #0e9f83;   /* 主色，不变 */
  --hx-teal-700: #0b7a66;
  --hx-teal-800: #0a6152;
  --hx-teal-900: #084a3e;

  /* ═══ 中性色阶 ═══ */
  --hx-n-50:  #f7fafb;
  --hx-n-100: #f0f4f6;
  --hx-n-200: #e6ecf0;
  --hx-n-300: #d5dee4;
  --hx-n-400: #c5ced6;
  --hx-n-500: #9aa8b5;
  --hx-n-600: #7a8b99;
  --hx-n-700: #5b6a77;
  --hx-n-800: #3d4b58;
  --hx-n-900: #13222e;
  --hx-n-950: #0c1720;

  /* ═══ 语义色 ═══ */
  --hx-danger: #e0435a;      --hx-danger-bg: #fde7ea;  --hx-danger-text: #a8243a;
  --hx-warn:   #e5a100;      --hx-warn-bg:   #fff3da;  --hx-warn-text:   #8a5c00;
  --hx-info:   #5b8def;      --hx-info-bg:   #e9f0fd;  --hx-info-text:   #2c4f8f;
  --hx-success-text: #0b7a66;

  /* ═══ 字号 / 字重 / 行高 / 字距 ═══ */
  --hx-fs-display: 28px; --hx-fw-display: 900; --hx-lh-display: 1.2;  --hx-ls-display: -0.02em;
  --hx-fs-num-xl:  24px; --hx-fw-num-xl:  900; --hx-lh-num-xl:  1.1;  --hx-ls-num-xl:  -0.02em;
  --hx-fs-num-lg:  18px; --hx-fw-num-lg:  800; --hx-lh-num-lg:  1.1;  --hx-ls-num-lg:  -0.01em;
  --hx-fs-h1:      17px; --hx-fw-h1:      800; --hx-lh-h1:      1.35; --hx-ls-h1:      -0.01em;
  --hx-fs-h2:      15px; --hx-fw-h2:      700; --hx-lh-h2:      1.4;
  --hx-fs-body:    14px; --hx-fw-body:    600; --hx-lh-body:    1.5;
  --hx-fs-body-sm: 13px; --hx-fw-body-sm: 500; --hx-lh-body-sm: 1.5;
  --hx-fs-caption: 12px; --hx-fw-caption: 600; --hx-lh-caption: 1.45;
  --hx-fs-mini:    11px; --hx-fw-mini:    700; --hx-lh-mini:    1.4;

  /* ═══ 圆角阶梯 ═══ */
  --hx-r-xs: 8px;  --hx-r-sm: 12px; --hx-r-md: 14px;
  --hx-r-lg: 20px; --hx-r-xl: 28px; --hx-r-pill: 999px;

  /* ═══ 阴影 / 高度阶梯 ═══ */
  --hx-hairline:  1px solid var(--hx-n-200);
  --hx-elev-0:    none;
  --hx-elev-1:    0 1px 2px rgba(19,34,46,.04), 0 1px 1px rgba(19,34,46,.03);
  --hx-elev-2:    0 6px 16px rgba(19,34,46,.06), 0 1px 3px rgba(19,34,46,.04);
  --hx-elev-3:    0 16px 32px rgba(14,159,131,.14), 0 2px 8px rgba(19,34,46,.06);
  --hx-elev-brand:0 6px 14px rgba(14,159,131,.24);

  /* ═══ 间距栅格 ═══ */
  --hx-sp-1: 4px;  --hx-sp-2: 8px;  --hx-sp-3: 12px; --hx-sp-4: 16px;
  --hx-sp-5: 20px; --hx-sp-6: 24px; --hx-sp-7: 32px; --hx-sp-8: 40px;

  /* ═══ 动效 ═══ */
  --hx-dur-1: 120ms; --hx-dur-2: 200ms; --hx-dur-3: 320ms;
  --hx-ease-std:      cubic-bezier(.2,0,0,1);
  --hx-ease-out:      cubic-bezier(.16,1,.3,1);
  --hx-ease-in-out:   cubic-bezier(.4,0,.2,1);

  /* ═══ 背景（去掉双层光斑）═══ */
  --hx-bg: linear-gradient(180deg, #f2faf7 0%, #f6fbf9 40%, #f4fbf8 100%);

  /* ═══ 兼容层：旧变量保留，值指向新阶（零破坏）═══ */
  --hx-teal:        var(--hx-teal-600);
  --hx-teal-soft:   var(--hx-teal-400);
  --hx-teal-light:  var(--hx-teal-100);
  --hx-teal-deep:   var(--hx-teal-700);
  --hx-orange:      #e5a100;  --hx-orange-soft: #fff3da;
  --hx-red:         #e0435a;  --hx-red-soft: #fde7ea;  --hx-red-text: #a8243a;
  --hx-amber-text:  #8a5c00;
  --hx-blue:        #5b8def;  --hx-blue-soft: #e9f0fd; --hx-blue-text: #2c4f8f;
  --hx-water: #1769a5;  --hx-water-soft: #dff3ff;
  --hx-sleep: #5b3cc4;  --hx-sleep-soft: #ece7ff;
  --hx-mood:  #b03060;  --hx-mood-soft:  #ffe8f0;
  --hx-ink:   var(--hx-n-900);
  --hx-text:  var(--hx-n-900);
  --hx-muted: #6b7a87;                 /* 保留；新代码建议改用 --hx-n-700 */
  --hx-mark: #ffd84d;  --hx-mark-red: #ffc2cb;
  --hx-card: #ffffff;
  --hx-radius: var(--hx-r-lg);         /* 20px 不变 */
  --hx-shadow: var(--hx-elev-2);       /* 从 0 10px 26px 收敛为 0 6px 16px */
  --hx-grad:   linear-gradient(90deg, #14b394, #0e9f83); /* 保留，仅限小按钮/徽章 */
}
```

**迁移优先级（Phase 3 建议顺序）**
1. 引入 §6 令牌块（纯新增 + 兼容别名，零回归）。
2. 焦点卡落地（L1，用 `elev-3` + `r-xl`）——收益最大、风险最低。
3. `.card` 降为 `elev-2`，把 service/report 降 `elev-1`，内嵌 tile 改 `elev-0`+hairline —— 建立 3 级层次。
4. 进度条/主按钮去渐变改实底 `teal-600`。
5. `.hx-cat` 圆角 999→8；`.link`/小号 teal 字改 `teal-700`。
6. `--hx-bg` 换单段线性；移除 🔥/🎯 emoji 改图标。
7. 字号/字重按 §2.4 收敛。

---

## 附：与其它角色的对齐点
- **给 team-lead**：方向 = "诊室级秩序 × 陪伴感"；核心动作 = 健管师上首屏第 1 位 + 建立 3 级 elevation/radius 阶梯 + 去渐变去光斑去 emoji。主色不动。
- **给 Phase 2（信息架构）**：焦点卡视觉容器规格已定（56px 头像 / 28 圆角 / teal-700 实底 / elev-3）；请据此定义承载内容与交互。
- **给 Phase 3（落地）**：直接用 §6 令牌块；组件取值见 §3；禁令见 §5。源码本阶段未改动。
