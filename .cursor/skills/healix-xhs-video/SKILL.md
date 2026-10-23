---
name: healix-xhs-video
description: >-
  Makes Healix Xiaohongshu promo videos with the docs/sales pipeline: narrated
  story slideshows by default, or 30-second product-demo recordings when asked.
  Use when the user names healix-xhs-video, or asks for a 宣传视频, 小红书视频,
  旁白视频, or sales video from the existing sales flow.
disable-model-invocation: true
---

# Healix 小红书宣传视频

按 `docs/sales/` 里已经跑通的流程做一条宣传视频。用户点名本 skill 时才做，不要顺手改产品代码。

默认做**图组旁白**（健康管理方案、客户管理周报那条）。只有用户明确要「界面演示 / 操作录屏 / 30 秒演示」时，才走**界面演示**。不要用 `xhs-cockpit/story-1-morning/sop/tools/build_xhs_video.py`：那是固定 60 秒槽位，旁白会被压速。

成片、脚本、图都在 `docs/sales/`，该目录被 gitignore。不要提交，除非用户明确要求。

机械参数、命令、演示页契约见 [reference.md](reference.md)。

## 开工前

1. 问清或从用户话里确定：宣传哪块能力、主角是谁、走图组旁白还是界面演示。没说格式就用图组旁白。
2. 先读对应产品代码，再写画面和旁白。能力、状态、谁能看见，以代码为准。演示数据可以虚构，产品行为不能编。
3. 系列续集沿用林悦（健管师 / 营养师，带 86 位客户）和已有客户（王丽、王阿姨）。新人物在 `STORY.md` 标明演示虚构。

## 口吻

- 对外称「客户」。界面里产品本来写的「患者」「就诊人」保持原词。
- 口语、故事感。面向健管师 / 营养师。
- 标题第一词不用「AI」。正文先说「系统 / 它」，再点能力。
- 不写诊断、治疗承诺，不写未验证的效率（节省几小时、几分钟出方案、提升百分之几）。
- 未病类型叫「健康管理方案」，不叫「营养方案」。
- AI 只进草稿；人点发布之后，客户才看得到。依据不对客户展示。判断权在健管师。功能若不是这样，按代码写，不要套这句。
- 每张右上角「演示数据」。末张注明「图中为系统演示数据，非真实用户」。

## 图组旁白

故事结构对齐 `docs/sales/xhs-care-plan/story-1-draft-to-publish/STORY.md`：

1. 一句话利益点
2. 主角和客户
3. 以前：3–5 个具体痛点
4. 转场：收成一条闭环
5. 现在：能力递进，每步对上一个痛点
6. 前后对比（定性）+ 边界 + 内测 CTA
7. 分镜表：一张一镜，旁白与画面同一句

目录：`docs/sales/<topic>/story-1-<slug>/`

| 文件 | 作用 |
|---|---|
| `STORY.md` | 故事、分镜、旁白大意 |
| `<name>.html` | 每镜一个 `<section class="page">`，链 `docs/sales/xhs-cockpit/xhs-story.css` |
| `export-pngs.js` | 从最近一份故事复制，改 html 路径和 PNG 前缀 |
| `build_voice_video.py` | 从周报或方案故事复制，改 `LINES` / `COVER` / `OUT` |
| `publishing.md` | 标题 3 个、正文、≤5 个话题、评论区、发布时段 |
| `comment-card.html` | 评论区配图，可后做 |
| `video/<标题>-旁白.mp4` | 成片 |

画面是竖版内页 **1080×1440**，成片画布 **1080×1920**（上下模糊衬底）。封面必须是 `前缀-01.png`，并且是 `LINES[0]`。

旁白写进 `LINES`，一句一镜。给 `say` 的稿里把 `AI` 写成 `A I`；字幕脚本会写回 `AI`。句间留白和句尾尾巴用脚本里的 `GAP_S` / `TAIL_S` / `LEAD_S`，不要手填秒数，不要压速。

做完核对：

- PNG 张数 = `.page` 数 = `LINES` 条数，文件名连续
- 封面是第一张
- 成片有音轨；`mdls -name kMDItemDurationSeconds -name kMDItemPixelWidth -name kMDItemPixelHeight` 为 1080×1920
- 抽看封面和中间一镜：字幕没裁切，右上角有「演示数据」

## 界面演示

静态 HTML 复刻界面，不打开真实登录后的产品，也不要为录视频启动 `start-dev.sh`。

样板：`docs/sales/xhs-diet/demo-diet.html`。舞台 **1080×1920**。`scenes` 用单引号字幕，字段至少有 `t`、`sub`；`const TOTAL` 为总毫秒。必须挂：

```js
window.__HEALIX_DEMO = { durationMs: TOTAL, start: startDemo };
if (!location.search.includes('record=1')) startDemo();
```

`add-voice.py` 用正则读 `t` 和 `sub`。字幕里不要出现单引号。旁白与字幕同一句，不要另写一份稿。

流水线（脚本已在仓库里，直接调用）：

1. `docs/sales/xhs-lab-ocr/record-demo.js` → JPEG 帧
2. `docs/sales/xhs-lab-ocr/encode-webcodecs.js` → 无声 mp4
3. `docs/sales/add-voice.py <demo.html> <无声.mp4> <旁白.mp4>`

输出放在该演示目录的 `video/`。一句若长过镜头，脚本最多 1.25 倍速；仍然塞不下就改短字幕，不要改倍速上限。

## 交付时告诉用户

- 成片路径和时长
- 图组或演示页路径
- `publishing.md` 路径（图组旁白才有）
- 哪几句是按代码核对过的产品行为，哪几处是虚构演示数据
