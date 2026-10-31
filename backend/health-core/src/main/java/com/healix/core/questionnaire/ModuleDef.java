package com.healix.core.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** 问卷模块定义（code + version）。 */
public record ModuleDef(
        String code, int version, String title, String script, boolean dynamic, List<ModuleItem> items) {

    static ModuleDef parse(JsonNode n) {
        String code = text(n, "code");
        int version = n.path("version").asInt(0);
        String title = text(n, "title");
        if (code == null || version <= 0 || title == null) {
            throw new IllegalStateException("模块缺少 code/version/title");
        }
        boolean dynamic = n.path("dynamic").asBoolean(false);
        List<ModuleItem> items = new ArrayList<>();
        for (JsonNode it : n.path("items")) {
            items.add(ModuleItem.parse(it));
        }
        if (!dynamic && items.isEmpty()) {
            throw new IllegalStateException("模块无题目: " + code + ".v" + version);
        }
        return new ModuleDef(code, version, title, text(n, "script"), dynamic, List.copyOf(items));
    }

    public String key() {
        return code + ".v" + version;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
