# B 端宽屏 Layout 规范（frontend-admin）

管理端无 Tailwind。用 CSS 变量 + 全局工具类落地宽屏约束，避免在 1440p+ / Retina 上无限拉伸、控件变形、图表压扁。

实现入口：

- 壳层：[`frontend-admin/src/shared/AdminShell.vue`](../frontend-admin/src/shared/AdminShell.vue)
- 全局样式：[`frontend-admin/src/style.css`](../frontend-admin/src/style.css)
- 图表 resize：[`frontend-admin/src/shared/useEchartsHost.ts`](../frontend-admin/src/shared/useEchartsHost.ts)

## 1. 常规页：什么都不用加

非 flush 页面由 `AdminShell` 自动包一层：

```text
main.content
  └─ .page-scroll          /* 唯一纵向滚动 */
       └─ .content-container  /* max-width: 1440px; margin: 0 auto */
            └─ <slot />       /* 你的页面 */
```

列表、表单、详情默认居中限宽。不要再写 `width: 1200px` / `max-width: 100vw`。

需要更宽时，在页面根节点加：

| 类 | 作用 |
|---|---|
| （默认） | max 1440 |
| `content-container--wide` | 需自行再包一层时用；max 1600 |
| `content-container--fluid` | 取消限宽（少用） |

注意：壳已包 `.content-container`。页面根再套一层通常没必要；只有「同一页局部要 fluid」才在内层用 `--fluid`。

## 2. 看板全宽：flush

以下路由在 [`WorkspaceLayout.vue`](../frontend-admin/src/portals/workspace/WorkspaceLayout.vue) 已设 `flushContent`：

- 驾驶舱 `/workspace/cockpit`
- 首诊全屏、方案制定 compose
- 选机构

新增全宽看板时：在 Layout 里把路由加入 `flushContent` 判定，页面自己用 flex/grid 做列内滚动，**不要**再依赖整页 `body` 滚动。

## 3. 左列表 + 右详情

```html
<div class="layout-grid">
  <aside class="layout-aside">…</aside>
  <section class="layout-main">…</section>
</div>
```

| 类 | ≥1024px |
|---|---|
| `layout-aside` | 4 / 12 |
| `layout-main` | 8 / 12 |
| `layout-aside--narrow` | 3 / 12 |
| `layout-main--wide` | 9 / 12 |
| `layout-span-3` … `layout-span-9` | 任意跨列 |

禁止：`width: 300px`、`flex: 0 0 320px` 撑整栏。小控件（图标 24px）仍可用固定尺寸。

## 4. 搜索行与控件高度

全局已强制 Element Plus 输入/选择/默认按钮 `min-height: 40px`（`--control-h`）。

搜索工具条：

```html
<div class="toolbar control-row">
  <el-input class="control-grow" … />
  <el-select class="control-fixed-sm" … />
  <el-button type="primary">查询</el-button>
</div>
```

| 类 | 用途 |
|---|---|
| `control-row` | flex 行；旁侧按钮 `flex-shrink: 0` |
| `control-grow` | 主搜索框弹性宽 |
| `control-fixed-sm` / `control-fixed-md` | 状态下拉等短控件 |

示范：[`OrgRecipesView.vue`](../frontend-admin/src/portals/workspace/OrgRecipesView.vue)、[`GuidelineLibraryView.vue`](../frontend-admin/src/portals/workspace/GuidelineLibraryView.vue)。

## 5. 图表

```html
<div ref="el" class="chart-frame" /><!-- 默认高 300 -->
<div ref="el" class="chart-frame chart-frame--sm" /><!-- 220 -->
```

脚本：

```ts
import { useEchartsHost } from '../../shared/useEchartsHost'

let chart: EChartsType | null = null
const elRef = ref<HTMLDivElement | null>(null)
const { observe } = useEchartsHost(() => chart, elRef)

onMounted(() => {
  chart = echarts.init(elRef.value!)
  observe(elRef.value)
})
```

迷你 spark 图可保留自定义高度，但仍建议接 `useEchartsHost`，保证侧栏折叠后 resize。

## 6. absolute / fixed 白名单

```bash
rg -n "position:\s*(absolute|fixed)" frontend-admin/src
```

**允许**：Tooltip / 气泡、角标、Dialog 遮罩、下拉浮层、浮窗机器人、sticky 顶栏（壳内顶栏已改为文档流）。

**禁止**：用 absolute 撑整页高度、相对视口写死 `left`/`width`、无 `relative` 祖先的业务分栏。

原则：能 flex/grid 不用 absolute；必须定位时相对最近卡片祖先。

## 7. 令牌一览

| 变量 | 默认 | 含义 |
|---|---|---|
| `--layout-page-max` | 1400px | 常规页最大宽（随断点升级） |
| `--layout-page-max-2xl` | 1800px | ≥1536px 时写入 `--layout-page-max` |
| `--layout-page-max-4k` | 2400px | ≥2000px 时写入 `--layout-page-max` |
| `--layout-gap` | 24px（≥1536 为 32） | 栏间距 |
| `--control-h` | 40px | 控件高度 |
| `--chart-h` | 300px | 图表默认高 |
| `--chart-h-sm` | 220px | 图表小高 |

大屏分级延展（勿直接去掉 max-width）：

| 视口 | 内容区 max-width |
|---|---|
| 默认 | 1400px |
| ≥1536px（2xl / 约 2K） | 1800px |
| ≥2000px | 2400px |

6 卡 KPI 用 `summary-row summary-row--6`（md 3 列 / xl 6 列）。
