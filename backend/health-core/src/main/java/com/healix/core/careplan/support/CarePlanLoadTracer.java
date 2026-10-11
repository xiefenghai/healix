package com.healix.core.careplan.support;

/** 方案上下文加载过程追踪（供 Agent SSE 展示 tool 调用）。 */
@FunctionalInterface
public interface CarePlanLoadTracer {

    void step(String tool, String status, String detail);
}
