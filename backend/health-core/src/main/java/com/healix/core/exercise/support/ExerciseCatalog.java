package com.healix.core.exercise.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.exercise.dto.ExerciseTypeDto;
import com.healix.core.exercise.enums.ExerciseCategoryEnum;
import com.healix.core.exercise.enums.IntensityEnum;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 平台级运动类型库，启动时从 {@code seed/exercise_types.json} 载入内存。
 *
 * <p>机构不可自定义，因此不落表；记录上保存类型名称/大类/MET 快照，库调整不影响历史。
 */
@Slf4j
@Component
public class ExerciseCatalog {

    private static final String SEED_PATH = "seed/exercise_types.json";
    public static final String OTHER = "OTHER";

    private final Map<String, ExerciseTypeDto> byCode = new LinkedHashMap<>();
    /** 方案（含 AI 生成）里出现过的非标准编码 → 标准编码 */
    private final Map<String, String> codeAliases = new LinkedHashMap<>();

    public ExerciseCatalog() {
        load();
    }

    public List<ExerciseTypeDto> all() {
        return List.copyOf(byCode.values());
    }

    public Optional<ExerciseTypeDto> find(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        String key = code.trim().toUpperCase(Locale.ROOT);
        ExerciseTypeDto hit = byCode.get(key);
        if (hit == null && codeAliases.containsKey(key)) {
            hit = byCode.get(codeAliases.get(key));
        }
        return Optional.ofNullable(hit);
    }

    public ExerciseTypeDto require(String code) {
        return find(code).orElseThrow(() -> new BusinessException(400, "未知运动类型：" + code));
    }

    public List<ExerciseTypeDto> search(String keyword, int limit) {
        int cap = Math.max(1, Math.min(limit, 50));
        if (!StringUtils.hasText(keyword)) {
            return all().stream().limit(cap).toList();
        }
        String k = keyword.trim();
        List<ExerciseTypeDto> exact = new ArrayList<>();
        List<ExerciseTypeDto> partial = new ArrayList<>();
        for (ExerciseTypeDto t : byCode.values()) {
            if (t.name().equals(k) || t.aliases().contains(k)) {
                exact.add(t);
            } else if (t.name().contains(k) || t.aliases().stream().anyMatch(a -> a.contains(k))) {
                partial.add(t);
            }
        }
        exact.addAll(partial);
        return exact.stream().limit(cap).toList();
    }

    /** 类型 + 强度对应的 MET；缺失时退回中等强度值。 */
    public static double metOf(ExerciseTypeDto type, IntensityEnum intensity) {
        Double v = type.met().get(intensity.name());
        if (v == null) {
            v = type.met().get(IntensityEnum.MODERATE.name());
        }
        return v == null ? 3.0 : v;
    }

    public static ExerciseCategoryEnum categoryOf(ExerciseTypeDto type) {
        return ExerciseCategoryEnum.parseOr(type.category(), ExerciseCategoryEnum.AEROBIC);
    }

    private void load() {
        ClassPathResource resource = new ClassPathResource(SEED_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("Exercise seed " + SEED_PATH + " not found");
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = JsonUtils.mapper().readTree(in);
            for (JsonNode t : root.path("types")) {
                String code = t.path("code").asText("").trim().toUpperCase(Locale.ROOT);
                if (code.isEmpty()) {
                    continue;
                }
                List<String> aliases = new ArrayList<>();
                t.path("aliases").forEach(a -> aliases.add(a.asText()));
                List<String> tags = new ArrayList<>();
                t.path("tags").forEach(a -> tags.add(a.asText()));
                Map<String, Double> met = new LinkedHashMap<>();
                t.path("met").fields().forEachRemaining(e -> met.put(e.getKey(), e.getValue().asDouble()));
                byCode.put(code, new ExerciseTypeDto(
                        code,
                        t.path("name").asText(code),
                        List.copyOf(aliases),
                        ExerciseCategoryEnum.parseOr(t.path("category").asText(null), ExerciseCategoryEnum.AEROBIC).name(),
                        IntensityEnum.parseOr(t.path("defaultIntensity").asText(null), IntensityEnum.MODERATE).name(),
                        Map.copyOf(met),
                        List.copyOf(tags)));
            }
            root.path("codeAliases").fields().forEachRemaining(e -> {
                String target = e.getValue().asText("").trim().toUpperCase(Locale.ROOT);
                if (!byCode.containsKey(target)) {
                    throw new IllegalStateException("Exercise code alias " + e.getKey() + " → unknown " + target);
                }
                codeAliases.put(e.getKey().trim().toUpperCase(Locale.ROOT), target);
            });
            log.info("Exercise catalog {} loaded: {} types", root.path("version").asText(""), byCode.size());
        } catch (IOException e) {
            throw new IllegalStateException("Exercise seed malformed: " + e.getMessage(), e);
        }
    }
}
