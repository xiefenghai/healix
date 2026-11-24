# PRD · C 端首页双态重构

> 状态：已按设计总览落地（`frontend-user/src/views/HomeView.vue`）  
> 判定信号：`GET /api/c/v1/membership/status` → `inCare`（禁止用「有无就诊人」代替）

## 状态机

| 模式 | 条件 | 首屏叙事 |
|------|------|----------|
| **loading** | `bootReady=false` | 骨架屏，不提前渲染 Free/Care |
| **error** | 非「无就诊人」的加载失败 | 提示 + 重试 |
| **empty** | 无就诊人 | 添加就诊人 / 激活码 |
| **free** | `inCare=false` | 体征 → 连续打卡 → 快捷记录 → 趋势 → 弱入组 |
| **care** | `inCare=true` | 健管师 → 目标进度 → 今日待办 → 记录工具 → 连续打卡 → 评估条 |

切换就诊人：`/patient-cards` select 后 `replace('/home')` remount → `bootstrapHome()` 重新拉 membership + 对应数据。

## 品牌

浅蓝底 `#F3F8FB`、薄荷绿主色、空态主 CTA 浅蓝；卡片 16px 圆角、轻阴影。
