package com.healix.core.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 平台字段目录，启动时从 {@code fields/field-catalog.json} 载入；任何一条不合格即拒绝启动。
 *
 * <p>选项分两类：{@code dictOptionSets} 是档案页早已在用的字典选项，只认 {@code sys_dict}；
 * {@code optionSets} 是新选项，启动时种进 {@code sys_dict}（不覆盖机构改过的文案），目录里的内容作兜底。
 */
@Slf4j
@Component
public class FieldCatalog {

    static final String PATH = "fields/field-catalog.json";

    private final String version;
    private final Map<String, FieldDef> fields;
    private final Map<String, List<FieldOption>> optionSets;
    private final Set<String> dictOptionSets;

    public FieldCatalog() {
        this(read(PATH));
    }

    FieldCatalog(JsonNode root) {
        this.version = root.path("version").asText("");
        this.dictOptionSets = new LinkedHashSet<>();
        root.path("dictOptionSets").forEach(n -> dictOptionSets.add(n.asText()));
        this.optionSets = new LinkedHashMap<>();
        root.path("optionSets").fields().forEachRemaining(e -> optionSets.put(e.getKey(), parseOptions(e.getKey(), e.getValue())));
        this.fields = new LinkedHashMap<>();
        for (JsonNode n : root.path("fields")) {
            FieldDef def = parseField(n);
            if (fields.put(def.code(), def) != null) {
                throw new IllegalStateException("字段编码重复: " + def.code());
            }
        }
        validate();
        log.info("Field catalog {} loaded: {} fields, {} option sets", version, fields.size(), optionSets.size());
    }

    public String version() {
        return version;
    }

    public Optional<FieldDef> find(String code) {
        return Optional.ofNullable(code == null ? null : fields.get(code));
    }

    public FieldDef require(String code) {
        FieldDef def = fields.get(code);
        if (def == null) {
            throw new IllegalArgumentException("未知字段: " + code);
        }
        return def;
    }

    public boolean contains(String code) {
        return fields.containsKey(code);
    }

    public Collection<FieldDef> all() {
        return fields.values();
    }

    /** 目录里登记了以 {@code code + "."} 开头的字段（扁平化时据此决定是否继续往下拆）。 */
    public boolean hasChildren(String code) {
        String p = code + ".";
        return fields.keySet().stream().anyMatch(c -> c.startsWith(p));
    }

    public boolean isDictOptionSet(String name) {
        return dictOptionSets.contains(name);
    }

    /** 目录自带的选项（新选项集的种子与兜底）；字典选项集返回空。 */
    public List<FieldOption> catalogOptions(String name) {
        return optionSets.getOrDefault(name, List.of());
    }

    public Map<String, List<FieldOption>> catalogOptionSets() {
        return optionSets;
    }

    private void validate() {
        for (FieldDef def : fields.values()) {
            FieldStore.of(def.code());
            if (def.choice()) {
                if (def.options() == null) {
                    throw new IllegalStateException("选择题缺少选项: " + def.code());
                }
                if (!optionSets.containsKey(def.options()) && !dictOptionSets.contains(def.options())) {
                    throw new IllegalStateException("未登记的选项集 " + def.options() + ": " + def.code());
                }
                for (String ex : def.exclusive()) {
                    List<FieldOption> opts = optionSets.get(def.options());
                    if (opts != null && opts.stream().noneMatch(o -> o.code().equals(ex))) {
                        throw new IllegalStateException("互斥选项不在选项集里 " + ex + ": " + def.code());
                    }
                }
            } else if (def.options() != null) {
                throw new IllegalStateException("非选择题不应配置选项: " + def.code());
            }
            if (def.min() != null && def.max() != null && def.min().compareTo(def.max()) > 0) {
                throw new IllegalStateException("取值范围不合法: " + def.code());
            }
            if (def.legacy() && !def.store().metadata()) {
                throw new IllegalStateException("只有档案字段可以标 legacy: " + def.code());
            }
        }
    }

    private static FieldDef parseField(JsonNode n) {
        String code = text(n, "code");
        if (code == null || !code.matches("[a-z]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalStateException("字段编码不合法: " + code);
        }
        String label = text(n, "label");
        if (label == null) {
            throw new IllegalStateException("字段缺少标签: " + code);
        }
        FieldType type;
        try {
            type = FieldType.valueOf(text(n, "type"));
        } catch (RuntimeException e) {
            throw new IllegalStateException("字段类型不合法: " + code);
        }
        List<String> exclusive = new ArrayList<>();
        n.path("exclusive").forEach(x -> exclusive.add(x.asText()));
        return new FieldDef(
                code,
                label,
                type,
                text(n, "options"),
                text(n, "unit"),
                decimal(n, "min"),
                decimal(n, "max"),
                n.hasNonNull("maxLength") ? n.get("maxLength").asInt() : null,
                List.copyOf(exclusive),
                n.path("sensitive").asBoolean(false),
                n.path("legacy").asBoolean(false),
                n.path("readOnly").asBoolean(false),
                n.path("external").asBoolean(false),
                n.path("staffOnly").asBoolean(false),
                n.path("virtual").asBoolean(false));
    }

    private static List<FieldOption> parseOptions(String set, JsonNode arr) {
        List<FieldOption> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : arr) {
            String raw = n.asText();
            int eq = raw.indexOf('=');
            if (eq <= 0 || eq == raw.length() - 1) {
                throw new IllegalStateException("选项格式应为 CODE=文案: " + set + " / " + raw);
            }
            String code = raw.substring(0, eq).trim();
            if (!seen.add(code)) {
                throw new IllegalStateException("选项编码重复: " + set + " / " + code);
            }
            out.add(new FieldOption(code, raw.substring(eq + 1).trim()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("选项集为空: " + set);
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static BigDecimal decimal(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.decimalValue();
    }

    static JsonNode read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return JsonUtils.mapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + path + " 失败", e);
        }
    }
}
