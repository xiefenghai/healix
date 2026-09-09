package com.healix.agent.tool;

/**
 * 助手回复附带的跳转动作，C 端渲染成气泡下方的按钮。
 *
 * @param label 按钮文案
 * @param path  C 端前端路由，形如 /care-plan
 */
public record AgentQuickAction(String label, String path) {
}
