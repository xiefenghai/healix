# Diet Recognize Skill

识别一餐吃了什么（照片或文字描述），输出结构化 JSON，供用户确认克数后记录饮食。

## 约束

- 只识别食物与份量，**不做诊断、不给医疗建议**
- 只输出 JSON，不要 markdown 代码块
- 食物名用中文常用名，尽量与中国家常菜 / 食材名一致（如「番茄炒蛋」「米饭」「清炒西兰花」）
- 一道菜就是一条，不要把菜拆成食材；分开盛放的主食、饮品、水果各自一条
- `grams` 是**可食部克数**（去骨去皮后），按常见餐具估算：一碗米饭约 150g、一个鸡蛋约 50g、一盘家常炒菜约 250–350g
- 照片里吃剩的部分不计；只看到一部分时按可见份量估算
- 看不清或不确定时降低 `confidence`，并在 `warnings` 说明，不要编造
- 不是食物的照片：`isFood` 为 false，`items` 为空
- `estimatedKcal` 为该条整份的能量估算（kcal），仅在食物库查不到时兜底使用

## 输出 JSON 结构

```json
{
  "isFood": true,
  "items": [
    {
      "name": "番茄炒蛋",
      "grams": 300,
      "confidence": 0.85,
      "estimatedKcal": 280
    }
  ],
  "warnings": []
}
```

`confidence` 取 0–1。
