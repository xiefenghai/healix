# Phase 3 · c-home-v8 原型一致性验收报告

> 验收人：ux-designer（Phase 2 规范作者）｜ 验收对象：`docs/ui-redesign/c-home-v8.html`（949 行）+ `c-home-v8.png` / `c-home-v8-scrolled.png` / `c-home-v8-states.png`
> 依据：`_phase2-ia-ux.md`（Phase 2）+ `_phase1-visual-direction.md`（Phase 1）
> 性质：**只报告问题，未改动任何被验收文件**。本报告为唯一新增文件。
> 已排除：team-lead 声明的 2 项已知问题（`#fff`→`var(--hx-white)` 三处十六进制已修复；状态画廊宽度重出中）。

---

## 结论速览

| 组 | 条目 | PASS | FAIL |
|---|---|---|---|
| A 结构与高度 | A1–A4 | 4 | 0 |
| B L1 焦点卡 | B1–B4 | 3 | 1 |
| C 反 slop | C1–C6 | 6 | 0 |
| D 可读性与触达 | D1–D3 | 1 | 2 |
| E 状态可演示性 | E1–E2 | 1 | 1 |
| F 响应式 | F1 | 0 | 1 |
| **合计** | **20** | **15** | **5** |

**整体判断**：结构与反 slop 两条主线**满分**（A 组 4/4、C 组 6/6），说明 Phase 3 忠实执行了视觉禁令与 IA 重排。5 个 FAIL 中有 **1 个功能性 bug（E1，必改）**、2 个 a11y 合规缺口（D1/D3，必改）、2 个完备性偏差（B1/F1，可改可豁免）。

---

## A. 结构与高度

### A1 屏 1 模块顺序 — **PASS**
证据（行号）：
- `.home{padding:12px 16px 28px}` L151
- `.hero{height:48px}` L160 → 结构 `<div class="hero">` L543
- `.gap-16` L554（Hero 后 16px）
- `.l1-card{...min-height:112px}` L183 → `<div class="l1-card" id="l1">` L557
- `.gap-24` L565（焦点后留白 24px）
- L2 今日待办 `<section class="card" id="t-todo">` L568
- `.gap-20` L594
- L3 速记入口 `#quickSlot` L597
- `.gap-12` L622

顺序与间距结构（Hero 48 → 16 → L1 → 24 → L2 → 20 → L3 → 12）与 Phase 2 §1.2 完全一致。

### A2 屏 1 总占用 ≤ 672px — **PASS（实测 ≈ 579px）**
CSS 逐项累加（390×844 基准，md 档）：

| 项 | 来源 | 高(px) |
|---|---|---|
| `.home` padding-top | L151 | 12 |
| `.hero` | L160 | 48 |
| `.gap-16` | L157 | 16 |
| `.l1-card` 实际渲染高（padding 18×2 + l1-top 14 + l1-main margin-top 10 + 56 头像） | L183/199/201/202 | 116 |
| `.gap-24` | L157 | 24 |
| L2 今日待办卡（sec-head 36 + reward-strip 56 + exec 47 + todo-list 2 条 120） | L232/238/261/270 | 259 |
| `.gap-20` | L157 | 20 |
| `#quickSlot`（`.quick-tile{height:72px}`） | L301 | 72 |
| `.gap-12` | L157 | 12 |
| **合计** | | **579** |

- 对 724px 屏 1 可视区：余量 145px
- 对 672px 可堆叠上限：余量 93px → **未溢出，PASS**
- 说明：579px 后（屏 1 底部 145px）露出屏 2 的续卡，属滚动页正常；但见 A3 的 minor。

### A3 屏 2 模块顺序 — **PASS（带 1 处 minor）**
证据：剩余任务 `#todoMore` L627 → 5 列今日记录 L637 → 今日用药 `#medCard` L650 → 最近指标 L668 → 管理报告 `#reportCard` L696 → `.more-hint` L715。
- more-hint 新文案 L715：`健康数据看「健康」Tab · 评估 / 随访 / 档案在「我的」Tab` = Phase 2 §1.5 目标文案 ✓
- 最近指标**无内嵌记录按钮** L671–690 ✓

**minor**：屏 2 续卡 L628–631 复用了 `<h2 id="t-todo-more">今日待办</h2>` + `.sec-sub 剩余任务`，与屏 1 的 `<h2 id="t-todo">今日待办</h2>`（L570）**文本重复**。视觉与屏读会识别为「第二个今日待办卡」。
建议改法（二选一）：
1. 删掉 L629 的 `<h2>`，仅保留 `.sec-sub`；或
2. 给 h2 加 `.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}`，视觉隐藏但保留 a11y 分组语义。

### A4 体征移出宫格 / 健管师移出服务格 / 速记 4 格 — **PASS**
- 5 列宫格 L639–645 = 饮食 / 运动 / 饮水 / 睡眠 / 心情，**无体征** ✓
- 全文**无 `.service-row` / `.service`**，健管师已移入 L1 ✓
- 速记 4 格 L600–603 = 血压 / 血糖 / 心率 / BMI，`overflow-x:auto` L299 ✓

---

## B. L1 焦点卡

### B1 六态齐全且染色正确 — **FAIL（画廊展示层缺 loading）**
- 逻辑层：`const L1 = { online, offline, unread, matching, failed, loading }` L782–794 → **6 态定义齐全** ✓
- 染色：`matching` = `--l1-bg:var(--hx-teal-50)` + `--l1-border:var(--hx-teal-200)` + `--l1-shadow:var(--hx-elev-2)`，**不染 teal** ✓ L188–192；`::before` 单点柔光被关闭 L198 ✓
- `loading` = `skeleton:true` → 深色骨架 `.l1-av.loading{background:var(--hx-teal-700)}` ✓ L223 / L801–812
- **FAIL 点**：状态画廊 `renderGallery()` 的 `order` 数组仅 5 项 L913：`['online','offline','unread','matching','failed']`，**缺 `loading`**；`titles`/`meta` 亦无 loading 条目 L914/915–921。截图 `c-home-v8-states.png` 实测只有 5 列，与 team-lead「六态齐全」的验收口径不符。

**修法（精确）**：
```js
// L913
const order=['online','offline','unread','matching','loading','failed'];
// L914 增加
titles.loading='加载中（深色骨架）';
// L915-921 meta 增加
meta.loading='teal-700 深色骨架 · shimmer 动画 · 无点击';
// renderGallery 的 inner 分支增加 loading 骨架（复用 renderL1 的实现 L802-811）：
const inner = s==='loading'
  ? `<div class="l1-top"><span class="l1-dot"></span>加载中</div>
     <div class="l1-sk"><div class="l1-av loading"><span class="dots"><i></i><i></i><i></i></span></div>
       <div style="flex:1"><div class="bar b1 shimmer"></div><div class="bar b2 shimmer"></div><div class="bar b3 shimmer"></div></div></div>`
  : (s==='failed' ? ... : ...);
```
> 注：6 列 × (`.gcol` 300 + gap 18) = 1908px，team-lead 重出用的 1860px 仍差 48px，建议 `.gcol{width:290px}` 或换行（`.gcards{flex-wrap:wrap}`）。

### B2 容器规格 — **PASS**
- `border-radius:var(--hx-r-xl)` = 28px ✓ L179
- `background:var(--l1-bg)` = `--hx-teal-700` 纯色**零渐变** ✓ L175/180
- `box-shadow:var(--hx-elev-3)` ✓ L176/182
- `.l1-av{width:56px;height:56px}` ✓ L202
- `box-shadow:0 0 0 2px var(--hx-teal-100)`（teal-100 环）✓ L203
- 全页唯一深色块：仅 `.l1-card` 默认态用 teal-700 实底，其余卡为白/浅灰 ✓

### B3 unread 5s nudge 320ms×2 — **PASS**
- `.l1-card.nudge{animation:nudge .32s var(--hx-ease-in-out) 2}` L228（320ms、iteration 2）✓
- `@keyframes nudge{0%,100%{translateX(0)}25%{-3px}75%{3px}}` L229 ✓
- JS：`setTimeout(...,5000)` → 加 `.nudge`，1400ms 后移除 L874 ✓
- `prefers-reduced-motion` 下 `animation:none` L483 ✓

### B4 S07 无就诊人 = `.join-card` 替换 L1 — **PASS**
- `if(p.get('noPatient')==='1'){#join.hidden=false; #l1.hidden=true}` L873 ✓
- `.join-card` 规格 = `r-xl 28` + `n-50` 底 + `n-200` 边 + `elev-2` L386 ✓（符合 Phase 2 §2.4）

---

## C. 反 slop（Phase 1 §5 十条）

### C1 零 emoji — **PASS**
ripgrep `[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2705}\x{2714}\x{274C}\x{2B50}\x{FE0F}]` → **No matches**。全部图标走 SVG `<symbol>` 精灵 L492–524。

### C2 无左 4px accent 竖条 — **PASS**
`border-left` 全文仅 1 处：L376 `.assess{... border-left:0;}` = 显式 **0**，合规。

### C3 无双层 radial 光斑 — **PASS**
- 页面背景 `.screen{background:var(--hx-bg-flat)}` L133，而 `--hx-bg-flat` = 单段 linear L109 ✓
- 双层 radial `--hx-bg` L48–51 **仅保留为全局原值**（L24 注释声明，`.screen` 未引用）✓
- L187 `.l1-card::before` 单点柔光 = Phase 1 §4 方案 B **明确允许** ✓

### C4 主按钮/进度条 teal-600 实底 — **PASS（比规范更严格）**
- `.bar-fill{background:var(--hx-teal-600)}` L266 ✓
- `.act-btn.primary` L291 / `.btn-solid` L389 / `.sheet-save` L414 均 teal-600 实底 ✓
- `--hx-grad` **全页零使用**（仅 L55 定义 + L25 注释）✓

### C5 `.hx-cat` 圆角 8 — **PASS**
`.hx-cat{border-radius:var(--hx-r-xs)}` = 8px ✓ L281。胶囊（`r-pill`）仅用于 `.badge` L170 / `.rs-lv` L246 / `.metric-flag` L358 / `.act-btn` L288 / `.l1-btn` L211 / `.ms-b` L340 / `.bar-*`——均为徽章/按钮/状态，合规。

### C6 字重纪律 — **PASS**
`font-weight:900` 仅 2 处：`.hero .hi h1{font-size:22px;font-weight:900}` L164（≥18 问候名）✓；`.metric-value{font-size:24px;font-weight:900}` L360（≥18 数字）✓。其余标题一律 800 / 正文 600。

---

## D. 可读性与触达

### D1 对比度 — **FAIL（火苗熄灭态不足 AA）**
| 元素 | 原型值 | 实测对比度 | 要求 | 判定 |
|---|---|---|---|---|
| `.sec-head .link` | teal-700 L234 | 5.26:1 | ≥4.5 | ✓ |
| `.metric-label` | n-700 L357 | 5.57:1 | ≥4.5 | ✓ |
| `.sec-sub`（=section-sub） | n-700 L235 | 5.57:1 | ≥4.5 | ✓ |
| **`.reward-strip.dim .rs-tx .rs-days`** | **n-600 on #fff** L244 | **≈3.51:1** | ≥4.5 | ❌ |
| **`.reward-strip.dim .flame`** | **n-600 on n-100** L241 | **≈3.17:1** | ≥4.5 | ❌ |

Phase 2 §6.1 明确要求「火苗 dim 改 n-600 **on n-50**（≈5:1）」，原型误用 `n-100` 作底、且数字文字用 n-600 承载语义（18px·800 未达"大文本"阈值），两处均未过 AA。

**修法（精确）**：
```css
/* L241 由 n-100 改 n-50；图标色 n-600→n-700 */
.reward-strip.dim .flame{background:var(--hx-n-50);color:var(--hx-n-700);}
/* L244 数字文字 n-600→n-700（on #fff = 5.57:1，on n-50 = 5.30:1）*/
.reward-strip.dim .rs-tx .rs-days{color:var(--hx-n-700);}
```

### D2 触达区 — **PASS（带 1 处 minor）**
- `.act-btn{height:32px;min-width:56px}` L288 ✓
- `.todo-item{cursor:pointer}` L271 + 行级 click 委托 L858–867 ✓（整行可点）
- `.metric-tile{tabindex="0" role="button"}` L671 起，**无内嵌 `.metric-record` 按钮** ✓
- **minor**：`#freezeBtn` 被 inline 设为 `height:28px` L618，低于 32px 基准（Phase 2 §5.2 仅允许 xs 断点降 28）。建议删掉该 inline style，使其继承 `.act-btn` 的 32px。

### D3 语义字号下限 11px — **FAIL（3 处 10px 承载语义）**
grep `font-size:9|9.5|10|10.5px` 命中 5 处，其中 demobar 2 处可豁免（「非产品 UI」），**3 处为产品语义文字，违反 Phase 2 §2.4「语义下限 11px」**：

| 行号 | 选择器 | 内容 | 判定 |
|---|---|---|---|
| L334 | `.record-tile .rst` | 「已记 / 达标 / 5/8 / 去记」 | ❌ 语义状态 |
| L358 | `.metric-flag` | 「正常 / 偏高」 | ❌ 语义状态 |
| L396 | `.tabbar .tb` | 首页 / 方案 / 健康 … | ❌ 导航文字 |
| L429 | `.demobar button` | 演示控制 | ✓ 豁免 |
| L432 | `.demobar .db-note` | 演示注释 | ✓ 豁免 |

**修法（精确）**：
```css
.record-tile .rst{font-size:11px;}      /* L334: 10→11 */
.metric-flag{font-size:11px;}           /* L358: 10→11 */
.tabbar .tb{font-size:11px;}            /* L396: 10→11 */
```
> `.metric-flag` 从 10→11 会让胶囊高 +1px（≈19px），仍在 Phase 1 §3.7「计数徽章 16-19px」容忍带内。

---

## E. 状态可演示性

### E1 URL 参数 — **FAIL（todoEmpty 参数失效，且会中断后续参数应用）**

| 参数 | 状态 | 判定 |
|---|---|---|
| `managerState=online\|offline\|unread\|matching\|loading\|failed` | L872/874 → renderL1 | ✓ 已实现 |
| `noPatient=1` | L873 | ✓ |
| `makeup=yesterday-pending-2` | L875 → `#flag` | ✓ |
| `makeup=critical-2days` | L876 → `#rescue` + 隐藏 quickSlot | ✓ |
| `streak=extinguished` | L879 | ✓ |
| `freezeCards=2` | L878 | ✓ |
| `abnormal=bp` | L883 | ✓ |
| `metricEmpty=bp` | L884 | ✓ |
| `todoEmpty=1` | L882 | ❌ **见下** |
| `medAllDone=1` | L885 | ✓ |
| `report=latest / empty` | L886（empty 隐藏；latest 用默认展示） | ✓ |
| `assess=pending` | L887 | ✓ |
| `scroll=bottom/mid` | L889/890 | ✓ |
| `states=1` | L891 | ✓ |

**FAIL 证据**：L882 `document.getElementById('todoList').innerHTML=...`，但实际元素 id 为 **`todoListTop`**（L589：`<div class="todo-list" id="todoListTop">`）。`getElementById('todoList')` 返回 `null` → `.innerHTML` 抛 `TypeError`。
**连带影响**：`applyParams()` 是顺序执行（L871–891），L882 抛错会使 **L883–891 全部中断**——即任一含 `todoEmpty=1` 的 URL 里，`abnormal` / `metricEmpty` / `medAllDone` / `report` / `assess` / `scroll` / `states` 全部不生效。

**修法（精确）**：
```js
// L882
if(p.get('todoEmpty')==='1'){
  document.getElementById('todoListTop').innerHTML=
    '<div style="padding:20px 0;text-align:center;color:var(--hx-n-700);font-size:13px;font-weight:500">暂无今日方案任务</div>';
}
```
并建议将 L871–891 各分支用 `try{...}catch(e){console.warn(e)}` 包裹，避免单点错误全局中断。
（另：空态文字色原为 `--hx-n-600`，一并按 §6.1 升 `--hx-n-700`。）

### E2 演示控制台浮层 — **PASS**
`#demobar` L753 `class="demobar min"`，`.db-head small` 文案「非产品 UI ▾」L754；`.db-note` 明示未实现清单 L776。截图右下角浮层可见 ✓。

---

## F. 响应式

### F1 四断点 — **FAIL（xs 速记未按 3+1；xl 未用 420 上限）**

| 断点 | 原型实现 | 规范要求（Phase 2 §5.2） | 判定 |
|---|---|---|---|
| xs ≤359 | `.screen{width:326px}` L451；`.record-grid{repeat(4,1fr)}` L463 ✓；`.quick-tile{width:88px}` L462 | 宫格 4 列 ✓；**速记「3 格 + 1 格更多」**，而非缩宽后仍 4 格横滑 | ⚠️ 速记偏离 |
| md 360–419 | 无媒体查询（默认） ✓ | 基准档 ✓ | ✓ |
| lg ≥420 | `.screen{width:430px}` L467；L1 padding 24 / 头像 60 / 按钮 36 L468–470 ✓ | 同左 ✓ | ✓ |
| xl ≥470 | `.screen{width:430px}` L478 | **max-width 420 居中** | ⚠️ 420≠430 |

**说明**：xs 下 `.screen` 326 − 左右 padding 28 = 298px 可用，而 4 格 × 88 + 3 × 8 = 376px > 298，必然横滑；`checkOverflow()` L941 会在溢出时显示右箭头（`.swipe-hint` L312），功能上可用，但**未实现规范要求的「3 格 + 1 格半露/更多」**。xl 与 lg 同为 430px，等于 xl 无独立处理。

**修法（精确）**：
```css
@media (max-width:359px){
  /* 让第 4 格半露，暗示可滑（等价 3.5 格） */
  .quick-tile{width:calc((100% - 16px)/3.5);}
  .quick-wrap .swipe-hint{display:block;}   /* 强制显示可滑提示 */
}
@media (min-width:470px){
  .screen{width:420px;}    /* 由 430 改 420，符合 §5.2「max-width 420 居中」 */
}
```
（`.screen` 居中由 `body{display:flex;align-items:center}` L122 保证，无需额外 margin auto。）

---

## 附：非阻塞观察（不计入 PASS/FAIL）

1. **`.l1-btn` 主按钮底色**：默认态为 `--l1-btn-bg:var(--hx-white)`（白底 teal-700 字）L177，属 Phase 1 §4 方案 B 许可的「内嵌白描边胶囊」，OK；但与 Phase 1 §3.2「主按钮 teal-600 实底」字面不同——**在深色 L1 卡上白底更合理**，此处执行正确，仅记录。
2. **Hero 问候名 22px** L164：Phase 1 §2.4 `--hx-fs-display` 建议 28px。22px 未破可读性，390 宽下更稳，判为可接受偏差。
3. **`›` / `▾` 排版符号** L571/583/669/754：非 emoji（C1 PASS），但原型已有 `#i-chev` 图标，用文字箭头属轻微一致性偏差，可后续统一。
4. **`abnormal` 演示数据**：血糖 tile 在 HTML 中**写死** `class="metric-tile abnormal"` L676，故任何 URL 下血糖都显示「偏高」——演示合理，但 `?abnormal=bp` 时会出现 2 个异常态，注意演示话术。

---

## 可交付判断

**不可直接交付，需先修 3 条**：
1. **E1**（必修）：`todoEmpty` 的 id 拼写 bug，附带全局中断风险。
2. **D1**（必修）：火苗熄灭态 3.17–3.51:1，未过 AA 4.5:1。
3. **D3**（必修）：`.record-tile .rst` / `.metric-flag` / `.tabbar .tb` 三处 10px 语义字，须升 11px。

**可择机修或豁免**：
- **B1**：画廊补 `loading` 态（team-lead 重出截图时一并处理最佳）。
- **F1**：xs 速记「3+1」、xl 改 420px；若不改，需在规范里把 xs 速记降级为「横滑 + 箭头」。

**修复后无需重跑全量验收**——5 项均为局部（CSS 3 行 + JS 1 行 + 画廊数组），改完抽查 E1（点 S35 按钮不再报错）、D1（火苗 dim 肉眼对比）、D3（三处 11px）即可放行。
