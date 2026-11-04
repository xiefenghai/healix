# 宣传视频机械约定

## 环境

本机 Google Chrome：

`/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`

Puppeteer 不在仓库里。没有 `/tmp/healix-ppt/node_modules/puppeteer-core` 时：

```bash
npm install puppeteer-core@24 --prefix /tmp/healix-ppt --silent
```

`export-pngs.js`、`record-demo.js`、`encode-webcodecs.js` 都 `require('/tmp/healix-ppt/node_modules/puppeteer-core')`。

旁白依赖 macOS `say` 音色 **Yue (Premium)**，以及带 `imageio_ffmpeg` 的 Python。先找已有 venv（`/tmp/healix-tts`、`/tmp/healix-voice`）。没有再装：

```bash
python3 -m venv /tmp/healix-tts
/tmp/healix-tts/bin/pip install -q imageio-ffmpeg
```

音色缺失时先 `say -v '?'` 确认，不要改成别的英文音色。

## 图组导出

视口 `1200×1600`，`deviceScaleFactor: 2`，对每个 `.page` 截 PNG。页面 CSS 尺寸是 1080×1440，导出图就是这个尺寸。

```bash
node export-pngs.js
```

从这两份改，不要从零写：

- `docs/sales/xhs-care-plan/story-1-draft-to-publish/export-pngs.js`（前缀 `方案-`）
- `docs/sales/xhs-weekly-report/story-1-friday/export-pngs.js`（前缀 `周报-`）

样式只链 `docs/sales/xhs-cockpit/xhs-story.css`。单篇额外样式写在该 html 的 `<style>` 里。

## 图组旁白脚本

复制整份再改常量，不要抽公共库：

- `docs/sales/xhs-weekly-report/story-1-friday/build_voice_video.py`
- `docs/sales/xhs-care-plan/story-1-draft-to-publish/build_voice_video.py`

要改的只有：`OUT`、`COVER`、`LINES`、临时目录前缀、封面文件名断言。

固定参数（两份已一致，保持）：

| 项 | 值 |
|---|---|
| 成片 | 1080×1920 |
| 内页 | 1080×1440，居中，衬底 `boxblur=24:1` |
| 音色 | `Yue (Premium)`，`-r 178`，44100 Hz 单声道 |
| 留白 | `GAP_S = 0.45`，`TAIL_S = 0.35`，`LEAD_S = 0.25` |
| 字幕 | 冬青黑体 `/System/Library/Fonts/Hiragino Sans GB.ttc`，白字 40，黑底，底边距 140，超过 16 字折行 |
| 声音 | highpass 80 → 250 Hz −2 dB → 3200 Hz +2 dB → compressor → 短回声 → loudnorm I=-16 TP=-1.5 LRA=11 |
| 视频 | libx264 veryfast crf 20 yuv420p +faststart，30 fps |
| 音频 | aac 160k |

时间轴按每句真实语音长度排，句间空隙留在上一镜里。`wrap_sub` 会把旁白里的 `A I` 画成 `AI`。

```bash
/tmp/healix-tts/bin/python build_voice_video.py
```

## 界面演示契约

`scenes` 必须能被这条正则整段匹配（字幕单引号、中间不要换行拆开 `t` 和 `sub`）：

```text
\{\s*t:\s*(\d+),\s*sub:\s*'([^']*)'
```

`add-voice.py` 还会读 `const TOTAL = <毫秒>`。找不到 `TOTAL` 时按 30000。

录制：`record-demo.js` 打开 `file://…?record=1`，等 `window.__HEALIX_DEMO`，调用 `start()`，按 **10 fps** 截 JPEG。

```bash
mkdir -p video /tmp/healix-demo-frames
node /Users/mac/healix/docs/sales/xhs-lab-ocr/record-demo.js \
  demo.html /tmp/healix-demo-frames
node /Users/mac/healix/docs/sales/xhs-lab-ocr/encode-webcodecs.js \
  /tmp/healix-demo-frames video/无声.mp4
/tmp/healix-tts/bin/python /Users/mac/healix/docs/sales/add-voice.py \
  demo.html video/无声.mp4 video/旁白.mp4
```

编码走 Chrome WebCodecs（`avc1.640028`，约 6 Mbps），不依赖本机 ffmpeg 编视频。配音仍用 imageio 自带的 ffmpeg。

配音：每句从 `t + 200ms` 开始，说到下一句的 `t`（最后一句说到 `TOTAL`）。超长才 `atempo`，上限 1.25。`say` 前会去掉 HTML 标签，并把 `AI` 读成 `A I`。

## 发布文案骨架

对齐 `docs/sales/xhs-care-plan/story-1-draft-to-publish/publishing.md`：

- 作品标题 3 个，标出首推（与封面主标一致）
- 封面主标、副标、角标「演示数据」
- 正文：痛点枚举 → 问题不在人 → 现在的做法 → 边界四条 → 内测 + 一个评论区问题
- 话题不超过 5 个
- 评论区：配图一句、内测一句、讨论一句
- 发布时段建议

## 不要复用

`docs/sales/xhs-cockpit/story-1-morning/sop/` 是早期固定槽位成片（含 `build_xhs_video.py`、`editing.md`、`copy.md`）。用户没点名这条之前，不读、不复制、不按它的 60 秒表排镜。
