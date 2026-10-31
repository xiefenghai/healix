package com.healix.core.careplan.guideline;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 平台级指南推荐条目库，启动时从 {@code guidelines/library.json} 载入内存。
 *
 * <p>文件由 {@code scripts/guideline-import.mjs} 从审定表格生成；任何一条不合格即拒绝启动，不静默跳过。
 * 只有 REVIEWED 条目进匹配；{@code healix.guideline.allow-draft=true}（仅开发环境）时 DRAFT 也进匹配。
 * 没有可用条目时整库视为未上线：不喂模型、不出依据相关校验。
 */
@Slf4j
@Component
public class GuidelineLibrary {

    static final String PATH = "guidelines/library.json";
    static final Set<String> KINDS = Set.of(GuidelineRec.KIND_SAFETY, GuidelineRec.KIND_TARGET);
    static final Set<String> BLOCKS = Set.of("EXERCISE", "DIET", "MONITOR", "LIFESTYLE");
    static final Set<String> STATUSES =
            Set.of(GuidelineRec.STATUS_DRAFT, GuidelineRec.STATUS_REVIEWED, GuidelineRec.STATUS_RETIRED);

    public record Data(String version, Map<String, GuidelineSource> sources, List<GuidelineRec> recs) {}

    private final Data data;
    private final boolean allowDraft;
    private final List<GuidelineRec> usable;

    @Autowired
    public GuidelineLibrary(@Value("${healix.guideline.allow-draft:false}") boolean allowDraft) {
        this(load(), allowDraft);
    }

    public GuidelineLibrary(Data data, boolean allowDraft) {
        this.data = data;
        this.allowDraft = allowDraft;
        this.usable = data.recs().stream()
                .filter(r -> GuidelineRec.STATUS_REVIEWED.equals(r.status())
                        || allowDraft && GuidelineRec.STATUS_DRAFT.equals(r.status()))
                .sorted(Comparator.comparingInt(GuidelineRec::priority).reversed())
                .toList();
        log.info(
                "Guideline library {} loaded: {} recs, {} usable (allowDraft={})",
                data.version(),
                data.recs().size(),
                usable.size(),
                allowDraft);
    }

    public boolean enabled() {
        return !usable.isEmpty();
    }

    /** 当前匹配会用到的条目是否含未审定草稿（仅开发环境可能为 true）。 */
    public boolean includesDraft() {
        return allowDraft && usable.stream().anyMatch(r -> GuidelineRec.STATUS_DRAFT.equals(r.status()));
    }

    public String version() {
        return data.version();
    }

    public List<GuidelineRec> usable() {
        return usable;
    }

    public List<GuidelineRec> all() {
        return data.recs();
    }

    public Map<String, GuidelineSource> sources() {
        return data.sources();
    }

    public Optional<GuidelineRec> find(String code) {
        return data.recs().stream().filter(r -> r.code().equals(code)).findFirst();
    }

    public String sourceLabel(GuidelineRec rec) {
        GuidelineSource s = data.sources().get(rec.sourceCode());
        String base = s == null ? rec.sourceCode() : s.label();
        return rec.section() == null || rec.section().isBlank() ? base : base + " · " + rec.section();
    }

    private static Data load() {
        ClassPathResource resource = new ClassPathResource(PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("Guideline library " + PATH + " not found");
        }
        try (InputStream in = resource.getInputStream()) {
            return parse(JsonUtils.mapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("Guideline library malformed: " + e.getMessage(), e);
        }
    }

    public static Data parse(JsonNode root) {
        String version = root.path("version").asText("").trim();
        if (version.isEmpty()) {
            throw new IllegalStateException("条目库缺 version");
        }
        Map<String, GuidelineSource> sources = new LinkedHashMap<>();
        for (JsonNode s : root.path("sources")) {
            String code = s.path("code").asText("").trim();
            String name = s.path("name").asText("").trim();
            if (code.isEmpty() || name.isEmpty()) {
                throw new IllegalStateException("指南来源缺 code 或 name");
            }
            if (sources.containsKey(code)) {
                throw new IllegalStateException("指南来源重复：" + code);
            }
            sources.put(code, new GuidelineSource(
                    code,
                    name,
                    s.path("edition").asText(null),
                    s.path("publisher").asText(null),
                    s.path("status").asText("ACTIVE"),
                    s.path("supersededBy").asText(null)));
        }
        List<GuidelineRec> recs = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode r : root.path("recs")) {
            String code = r.path("code").asText("").trim();
            String where = "条目 " + (code.isEmpty() ? "(缺 code)" : code);
            if (code.isEmpty() || !codes.add(code)) {
                throw new IllegalStateException(where + " code 缺失或重复");
            }
            String sourceCode = r.path("sourceCode").asText("").trim();
            if (!sources.containsKey(sourceCode)) {
                throw new IllegalStateException(where + " 来源不存在：" + sourceCode);
            }
            String kind = upper(r, "kind");
            String block = upper(r, "block");
            String status = upper(r, "status");
            if (!KINDS.contains(kind) || !BLOCKS.contains(block) || !STATUSES.contains(status)) {
                throw new IllegalStateException(where + " kind/block/status 非法");
            }
            String staffText = r.path("staffText").asText("").trim();
            if (staffText.isEmpty()) {
                throw new IllegalStateException(where + " 缺 staffText");
            }
            GuidelineQuant quant = GuidelineQuant.parse(r.get("quant"), where);
            String reviewer = blankToNull(r.path("reviewer").asText(null));
            LocalDate reviewedAt = date(r.path("reviewedAt").asText(null), where);
            if (GuidelineRec.STATUS_REVIEWED.equals(status) && (reviewer == null || reviewedAt == null)) {
                throw new IllegalStateException(where + " 已审定条目必须有审定人与审定日期");
            }
            recs.add(new GuidelineRec(
                    code,
                    r.path("rev").asInt(1),
                    sourceCode,
                    blankToNull(r.path("section").asText(null)),
                    kind,
                    block,
                    GuidelineCondition.parse(r.get("appliesWhen"), where + " appliesWhen"),
                    r.hasNonNull("contraindicatedWhen")
                            ? GuidelineCondition.parse(r.get("contraindicatedWhen"), where + " contraindicatedWhen")
                            : null,
                    staffText,
                    blankToNull(r.path("patientHint").asText(null)),
                    quant,
                    blankToNull(r.path("evidence").asText(null)),
                    r.path("priority").asInt(0),
                    status,
                    reviewer,
                    blankToNull(r.path("reviewerTitle").asText(null)),
                    reviewedAt));
        }
        return new Data(version, Collections.unmodifiableMap(sources), List.copyOf(recs));
    }

    private static String upper(JsonNode n, String f) {
        return n.path(f).asText("").trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? null : s.trim();
    }

    private static LocalDate date(String raw, String where) {
        String s = blankToNull(raw);
        if (s == null) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalStateException(where + " reviewedAt 须为 yyyy-MM-dd");
        }
    }
}
