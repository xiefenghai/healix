# Healix 健管师 / 医生手机工作台

独立 H5（Vue 3 + Vant），复用 B 端 API，面向手机上的日常操作。

## 首期能力

- 登录 / 选机构
- 待办：公共池领取、我的任务、打卡跟进 / 指标异常 / 常规随访填单
- 沟通：收件箱 + 聊天（SSE）
- 患者：列表与摘要
- 随访：定期随访办结（ROUTINE）

电脑端仍负责：档案大表单、方案编辑、OCR、报告审阅、组织管理等。

## 本地开发

```bash
npm ci
npm run dev   # http://localhost:5175 ，代理 /api → localhost:8080
```

账号与 PC 机构工作台相同（`/api/b/v1/auth/login`）。
