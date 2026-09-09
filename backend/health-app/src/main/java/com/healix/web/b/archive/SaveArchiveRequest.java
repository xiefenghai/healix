package com.healix.web.b.archive;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 基础档案 / 病种档案共用的保存请求体。
 *
 * @param version     当前档案版本（乐观锁；新建传 0）
 * @param contentJson 档案全文 JSON（字段由 sys_dict 定义）
 */
public record SaveArchiveRequest(@NotNull Integer version, @NotNull Map<String, Object> contentJson) {}
