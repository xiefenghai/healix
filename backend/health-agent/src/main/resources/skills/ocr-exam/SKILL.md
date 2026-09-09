# OCR Exam Skill

识别检查报告（超声 / 影像 / 心电 / 肺功能 / 骨密度 / 眼底），输出结构化 JSON 供人工确认后录入。

## 约束

- 仅辅助录入，**不做诊断**：`conclusion` 只允许照抄报告上「诊断意见 / 检查结论 / 超声所见」原文，不得改写或补充判断
- 只输出 JSON，不要 markdown 代码块
- `examType` 必须是给定枚举之一；报告名称对不上任何一类时填 null，并在 warnings 说明
- `findings` 只填该 examType 允许的字段，不确定的字段直接省略，不要猜
- 数值只取报告上的测量值，带范围时取实测值而非参考值；单位换算不要自己做
- 字迹不清、字段缺失，在 warnings 里逐条说明

## 结论抄录要点

- 优先取「诊断意见」「检查结论」段落；没有结论段时取「所见」段的最后一句
- 保留否定表述（如「未见明显异常」），不要简化成「正常」
- 超过 500 字时截断到关键句，不要拼接多段

## 常见对应关系

- 超声心动图 / 心动超声 → UCG，EF 值填 `efPercent`
- 颈动脉超声 → CAROTID_US，内-中膜厚度 IMT 填 `cimtMm`，报告提到斑块即 `hasPlaque=true`
- 肝胆胰脾超声 → ABDOMINAL_US，脂肪肝按轻/中/重度映射 MILD/MODERATE/SEVERE，未提及填 UNGRADED
- 甲状腺超声 → THYROID_US，TI-RADS 只填数字等级
- 胸片 / 胸部 CT → CHEST_IMAGING；腹部 CT → ABDOMINAL_CT
- 肺通气功能 → PFT；双能 X 线骨密度 → BMD，T 值填 `tScore`
- 眼底照相 → FUNDUS，糖网分级映射 NONE/MILD/MOD/SEVERE/PDR
