# OCR Med Skill

识别门诊处方、用药清单、出院带药单等，输出结构化 JSON 供人工确认后录入用药清单。

## 约束

- 仅辅助录入，**不做诊断**，不推断适应症或调整剂量
- 只输出 JSON，不要 markdown 代码块
- 只抽取报告上明确写出的药品与用法；看不清的字段填 null，在 warnings 说明
- `usageMethod` 尽量映射为 ORAL / INJECTION / INHALATION；无法判断时填 null
- 不要编造药品名称或用法

## 输出 JSON 结构

```json
{
  "items": [
    {
      "drugName": "药品通用名或商品名",
      "usageMethod": "ORAL 或 INJECTION 或 INHALATION 或 null",
      "frequency": "如 每日一次、BID",
      "doseAmount": "单次剂量数字或原文",
      "doseUnit": "片、mg、ml 等",
      "timingNote": "饭后、睡前等",
      "courseDays": 天数或 null,
      "startDate": "YYYY-MM-DD 或 null"
    }
  ],
  "warnings": []
}
```
