package com.healix.agent.diet;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.ocr.OcrRunner;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.exception.BusinessException;
import com.healix.core.diet.dto.DietDraftDto;
import com.healix.core.diet.dto.DietMealItemDto;
import com.healix.core.diet.dto.NutrientsDto;
import com.healix.core.diet.enums.MealTypeEnum;
import com.healix.core.diet.enums.OilLevelEnum;
import com.healix.core.diet.service.FoodCatalogService;
import com.healix.core.diet.service.FoodCatalogService.FoodEntry;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 饮食识别：照片（识图模型）或文字描述 → 食物名 + 克数草稿。
 *
 * <p>模型只负责「是什么、多少克」，营养值一律由食物库计算；未命中食物库的条目用模型估算能量兜底并标为 C 级。
 * 图片不落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DietRecognitionService {

    private static final String SKILL = "diet-recognize";
    private static final String FALLBACK_PROMPT = "你是饮食记录助手，识别食物与可食部克数，仅输出 JSON，不做诊断。";
    private static final int MAX_ITEMS = 15;
    private static final int MAX_PHOTOS = 4;
    private static final int MAX_BASIS_CHARS = 60;
    private static final double MAX_GRAMS = 2000;
    private static final double MAX_KCAL_PER_GRAM = 9.0;
    private static final int MAX_TEXT_CHARS = 300;
    private static final int MAX_HINT_CHARS = 200;

    private final LlmClient llmClient;
    private final OcrRunner ocrRunner;
    private final AiUsageGuard aiUsageGuard;
    private final FoodCatalogService catalog;

    public record Photo(byte[] bytes, String mimeType) {}

    /**
     * 同一餐的一张或多张照片一次识别，由模型合并重复出现的同一份食物。
     *
     * @param hint 用户补充说明（选填）：吃了多少、看不清的是什么，模型以此为准调整
     */
    public DietDraftDto recognizePhotos(List<Photo> photos, String mealType, String hint) {
        if (photos == null || photos.isEmpty()) {
            throw new BusinessException(400, "请上传图片");
        }
        if (photos.size() > MAX_PHOTOS) {
            throw new BusinessException(400, "一次最多识别 " + MAX_PHOTOS + " 张照片");
        }
        String note = StringUtils.hasText(hint) ? hint.trim() : "";
        if (note.length() > MAX_HINT_CHARS) {
            throw new BusinessException(400, "补充说明不超过 " + MAX_HINT_CHARS + " 字");
        }
        photos.forEach(p -> ocrRunner.validateImage(p.bytes(), p.mimeType()));
        aiUsageGuard.checkCurrent(FeatureFlagKeyEnum.AI_OCR, QuotaKeyEnum.OCR_MONTHLY);
        requireEnabled();
        List<LlmClient.VisionImage> images = photos.stream()
                .map(p -> new LlmClient.VisionImage(Base64.getEncoder().encodeToString(p.bytes()), p.mimeType()))
                .toList();
        String ask = photos.size() == 1
                ? "请识别这张照片的拍摄主体食物（忽略画面边角只露出一小角的菜），按约定输出 JSON。"
                : "以下 " + photos.size() + " 张照片是同一餐，请合并识别拍摄主体食物："
                        + "同一份食物在多张照片里出现只算一条，忽略画面边角只露出一小角的菜，按约定输出 JSON。";
        String userMessage = note.isEmpty() ? ask : ask + "\n\n用户补充说明（只用于确定食物和份量）：\n" + note;
        LlmResponse llm = llmClient.chatWithImages("diet-photo", systemPrompt(), userMessage, images);
        return toDraft(parse(llm), mealType);
    }

    public DietDraftDto recognizeText(String text, String mealType) {
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(400, "请描述吃了什么");
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_TEXT_CHARS) {
            throw new BusinessException(400, "描述不超过 " + MAX_TEXT_CHARS + " 字");
        }
        aiUsageGuard.checkCurrent(FeatureFlagKeyEnum.AI_PATIENT_AGENT, QuotaKeyEnum.AI_CALL_MONTHLY);
        requireEnabled();
        LlmResponse llm = llmClient.chat(
                "diet-text", systemPrompt(), "用户描述的一餐：\n" + trimmed + "\n\n请按约定输出 JSON（isFood 按描述判断）。", List.of());
        return toDraft(parse(llm), mealType);
    }

    private DietDraftDto toDraft(JsonNode root, String mealType) {
        String type = StringUtils.hasText(mealType)
                ? MealTypeEnum.parse(mealType).name()
                : MealTypeEnum.byTime(LocalTime.now()).name();
        List<String> warnings = new ArrayList<>();
        root.path("warnings").forEach(n -> {
            if (n.isTextual() && StringUtils.hasText(n.asText())) {
                warnings.add(n.asText().trim());
            }
        });
        if (root.has("isFood") && !root.path("isFood").asBoolean(true)) {
            warnings.add(0, "没有识别到食物，请重新拍摄或手动添加");
            return new DietDraftDto(type, List.of(), warnings);
        }
        List<DietMealItemDto> items = new ArrayList<>();
        List<String> estimated = new ArrayList<>();
        for (JsonNode n : root.path("items")) {
            if (items.size() >= MAX_ITEMS) {
                break;
            }
            String name = n.path("name").asText("").trim();
            double grams = n.path("grams").asDouble(0);
            if (name.isEmpty() || grams <= 0) {
                continue;
            }
            grams = Math.min(grams, MAX_GRAMS);
            double confidence = clamp01(n.path("confidence").asDouble(0.5));
            OilLevelEnum oil = OilLevelEnum.parseOrNormal(n.path("oilLevel").asText(null));
            Optional<FoodEntry> hit = catalog.matchByName(name);
            DietMealItemDto item = hit.isPresent()
                    ? fromCatalog(hit.get(), grams, oil)
                    : estimate(name, grams, n.path("estimatedKcal").asDouble(0), warnings, estimated);
            if (item == null) {
                continue;
            }
            String basis = n.path("basis").asText("").trim();
            if (!basis.isEmpty()) {
                item.setBasis(basis.length() > MAX_BASIS_CHARS ? basis.substring(0, MAX_BASIS_CHARS) : basis);
            }
            item.setConfidence(round2(confidence));
            item.setGramsConfirmed(false);
            items.add(item);
        }
        if (!estimated.isEmpty()) {
            warnings.add(String.join("、", estimated.stream().map(x -> "「" + x + "」").toList())
                    + "不在食物库，热量为估算值，可删除后搜索相近食物替换");
        }
        if (items.isEmpty()) {
            warnings.add("未识别到可记录的食物，请手动添加");
        }
        return new DietDraftDto(type, items, warnings);
    }

    private DietMealItemDto fromCatalog(FoodEntry e, double grams, OilLevelEnum oilLevel) {
        OilLevelEnum oil = e.isDish() ? oilLevel : null;
        NutrientsDto n = catalog.nutrientsOf(e, grams, oil).rounded();
        DietMealItemDto d = new DietMealItemDto();
        d.setFoodCode(e.item().getCode());
        d.setFoodName(e.item().getName());
        d.setAmount(round1(grams));
        d.setUnit("g");
        d.setGrams(round1(grams));
        d.setOilLevel(oil == null ? null : oil.name());
        fill(d, n);
        d.setPrecisionLevel(e.item().getPrecisionLevel());
        return d;
    }

    private DietMealItemDto estimate(
            String name, double grams, double kcal, List<String> warnings, List<String> estimated) {
        if (kcal <= 0) {
            warnings.add("「" + name + "」未在食物库中且无法估算热量，已忽略");
            return null;
        }
        DietMealItemDto d = new DietMealItemDto();
        d.setFoodName(name.length() > 50 ? name.substring(0, 50) : name);
        d.setAmount(round1(grams));
        d.setUnit("g");
        d.setGrams(round1(grams));
        fill(d, new NutrientsDto(Math.min(kcal, grams * MAX_KCAL_PER_GRAM), 0, 0, 0, 0, 0).rounded());
        d.setPrecisionLevel("C");
        estimated.add(d.getFoodName());
        return d;
    }

    private static void fill(DietMealItemDto d, NutrientsDto n) {
        d.setEnergyKcal(n.energyKcal());
        d.setCarbG(n.carbG());
        d.setProteinG(n.proteinG());
        d.setFatG(n.fatG());
        d.setFiberG(n.fiberG());
        d.setSodiumMg(n.sodiumMg());
    }

    private JsonNode parse(LlmResponse llm) {
        if (llm == null || !llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            throw new BusinessException("识别失败，请稍后重试或手动添加");
        }
        return OcrRunner.parseJson(stripFence(llm.content()));
    }

    /** OcrRunner.parseJson 只处理以 ``` 开头的内容；这里额外容忍前后有说明文字。 */
    private static String stripFence(String content) {
        String t = content.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        return start >= 0 && end > start ? t.substring(start, end + 1) : t;
    }

    private void requireEnabled() {
        if (!llmClient.isEnabled()) {
            throw new BusinessException("AI 识别未启用，请手动添加食物");
        }
    }

    private String systemPrompt() {
        try {
            Resource res = new ClassPathResource("skills/" + SKILL + "/SKILL.md", getClass().getClassLoader());
            if (res.exists()) {
                return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("skill {} missing: {}", SKILL, e.getMessage());
        }
        return FALLBACK_PROMPT;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
