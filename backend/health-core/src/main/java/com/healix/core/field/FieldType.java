package com.healix.core.field;

public enum FieldType {
    TEXT,
    NUMBER,
    /** 单选，值为选项编码 */
    ENUM,
    /** 多选，值为选项编码数组 */
    MULTI,
    /** yyyy-MM-dd */
    DATE,
    /** 年份，值为整数年（如 2019） */
    YEAR,
    /** HH:mm */
    TIME,
    /** 结构化数组（家族史明细、过敏、用药），由专属组件编辑 */
    LIST,
    /** 结构化对象（既往史明细），由专属组件编辑 */
    OBJECT
}
