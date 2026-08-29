package com.healix.config;

import com.healix.common.domain.EntityMeta;
import com.healix.core.dict.domain.SysDict;
import com.healix.core.dict.enums.DictTypeEnum;
import com.healix.core.dict.mapper.SysDictMapper;
import com.healix.core.dict.service.DictService;
import com.healix.core.dict.support.DictFieldSchemas;
import com.healix.core.metadata.enums.MetaDataCodeEnum;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DictSeedRunner implements ApplicationRunner {

    private final SysDictMapper sysDictMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (sysDictMapper.countByTenant(DictService.PLATFORM_TENANT) == 0) {
            seedDisease("diabetes", "糖尿病", 100);
            seedDisease("hypertension", "高血压", 90);
            seedPresentIllnessOptions();
            seedOption("appetiteLevel", "NORMAL", "正常", 100, null);
            seedOption("appetiteLevel", "POOR", "较差", 80, null);
            seedOption("exerciseFrequency", "WEEKLY_3", "每周≥3次", 100, null);
            seedOption("exerciseFrequency", "WEEKLY_1", "每周1-2次", 80, null);
            seedOption("sleepQuality", "GOOD", "良好", 100, null);
            seedOption("sleepQuality", "POOR", "较差", 80, null);
            seedOption("smoking", "NEVER", "从不", 100, null);
            seedOption("smoking", "OCCASIONAL", "偶尔", 80, null);
            seedOption("drinking", "NEVER", "从不", 100, null);
            seedOption("drinking", "OCCASIONAL", "偶尔", 80, null);

            seedBasicField(
                    "presentIllness",
                    "现有疾病",
                    DictFieldSchemas.multiSelectField(
                            MetaDataCodeEnum.BASIC_PRESENT_ILLNESS, "现有疾病", "presentIllness"),
                    100);
            seedBasicField(
                    "presentIllnessOther",
                    "现有疾病-其他",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_PRESENT_ILLNESS_OTHER, "现有疾病-其他"),
                    95);
            seedBasicField(
                    "familyHistory",
                    "家族史",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_FAMILY_HISTORY, "家族史"),
                    90);
            seedBasicField(
                    "pastHistory",
                    "既往史",
                    DictFieldSchemas.textField(MetaDataCodeEnum.BASIC_PAST_HISTORY, "既往史"),
                    85);
            seedBasicField(
                    "earlyCvFamilyHistory",
                    "早发心血管病家族史",
                    DictFieldSchemas.textField(
                            MetaDataCodeEnum.BASIC_EARLY_CV_FAMILY_HISTORY, "早发心血管病家族史"),
                    80);
            seedBasicField(
                    "diet",
                    "饮食情况",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_DIET,
                            "饮食情况",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("appetite", "食欲", "appetiteLevel"),
                                    DictFieldSchemas.subTextField("preference", "饮食偏好"),
                                    DictFieldSchemas.subTextField("note", "备注"))),
                    70);
            seedBasicField(
                    "exercise",
                    "运动情况",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_EXERCISE,
                            "运动情况",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("frequency", "频率", "exerciseFrequency"),
                                    DictFieldSchemas.subNumberField("durationMin", "时长(分钟)"),
                                    DictFieldSchemas.subTextField("type", "类型"))),
                    60);
            seedBasicField(
                    "sleep",
                    "睡眠情况",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_SLEEP,
                            "睡眠情况",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("quality", "质量", "sleepQuality"),
                                    DictFieldSchemas.subNumberField("hours", "时长(小时)"),
                                    DictFieldSchemas.subTextField("note", "备注"))),
                    50);
            seedBasicField(
                    "lifestyle",
                    "生活习惯",
                    DictFieldSchemas.compositeField(
                            MetaDataCodeEnum.BASIC_LIFESTYLE,
                            "生活习惯",
                            DictFieldSchemas.fields(
                                    DictFieldSchemas.subSelectField("smoking", "吸烟", "smoking"),
                                    DictFieldSchemas.subSelectField("drinking", "饮酒", "drinking"),
                                    DictFieldSchemas.subTextField("note", "备注"))),
                    40);
            seedDiseaseField(
                    "diabetes",
                    "diagnosisDate",
                    "确诊时间",
                    DictFieldSchemas.textField(MetaDataCodeEnum.DISEASE_DIABETES_DIAGNOSIS_DATE, "确诊时间"),
                    100);
            seedDiseaseField(
                    "hypertension",
                    "diagnosisDate",
                    "确诊时间",
                    DictFieldSchemas.textField(
                            MetaDataCodeEnum.DISEASE_HYPERTENSION_DIAGNOSIS_DATE, "确诊时间"),
                    100);
            log.warn("Bootstrapped platform sys_dict seed data");
        }
        // 已有库也可幂等补齐糖尿病扩展字段
        ensureDiabetesArchiveExtensions();
    }

    /** 糖尿病病种档案：类型 + 典型/不典型症状（幂等）。 */
    private void ensureDiabetesArchiveExtensions() {
        if (hasDict(DictTypeEnum.DISEASE_FIELD.name(), "diabetes", "diabetesType")) {
            return;
        }
        seedDiabetesTypeOptions();
        seedDiabetesTypicalSymptomOptions();
        seedDiabetesAtypicalSymptomOptions();
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "diabetesType",
                "糖尿病类型",
                DictFieldSchemas.selectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_TYPE, "糖尿病类型", "diabetesType"),
                90);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "typicalSymptoms",
                "典型症状（三多一少）",
                DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_TYPICAL_SYMPTOMS,
                        "典型症状（三多一少）",
                        "diabetesTypicalSymptoms"),
                80);
        seedDiseaseFieldIfAbsent(
                "diabetes",
                "atypicalSymptoms",
                "不典型症状与并发症征兆",
                DictFieldSchemas.multiSelectField(
                        MetaDataCodeEnum.DISEASE_DIABETES_ATYPICAL_SYMPTOMS,
                        "不典型症状与并发症征兆",
                        "diabetesAtypicalSymptoms"),
                70);
        log.warn("Ensured diabetes disease-archive dict extensions");
    }

    private void seedDiabetesTypeOptions() {
        seedOptionIfAbsent("diabetesType", "TYPE_1", "1型糖尿病", 100);
        seedOptionIfAbsent("diabetesType", "TYPE_2", "2型糖尿病", 90);
        seedOptionIfAbsent("diabetesType", "GESTATIONAL", "妊娠期糖尿病", 80);
        seedOptionIfAbsent("diabetesType", "SPECIAL", "特殊类型糖尿病", 70);
    }

    private void seedDiabetesTypicalSymptomOptions() {
        seedOptionIfAbsent("diabetesTypicalSymptoms", "POLYURIA", "多尿", 100);
        seedOptionIfAbsent("diabetesTypicalSymptoms", "POLYDIPSIA", "多饮", 90);
        seedOptionIfAbsent("diabetesTypicalSymptoms", "POLYPHAGIA", "多食", 80);
        seedOptionIfAbsent("diabetesTypicalSymptoms", "WEIGHT_LOSS", "体重下降", 70);
    }

    private void seedDiabetesAtypicalSymptomOptions() {
        seedOptionIfAbsent("diabetesAtypicalSymptoms", "FATIGUE", "乏力、疲劳感", 100);
        seedOptionIfAbsent("diabetesAtypicalSymptoms", "BLURRED_VISION", "视力模糊", 90);
        seedOptionIfAbsent("diabetesAtypicalSymptoms", "SKIN_ITCHING", "皮肤或外阴瘙痒", 80);
        seedOptionIfAbsent("diabetesAtypicalSymptoms", "SLOW_HEALING", "伤口愈合缓慢", 70);
        seedOptionIfAbsent("diabetesAtypicalSymptoms", "RECURRENT_INFECTION", "反复感染", 60);
        seedOptionIfAbsent("diabetesAtypicalSymptoms", "NUMBNESS_TINGLING", "手脚麻木或刺痛感", 50);
        seedOptionIfAbsent(
                "diabetesAtypicalSymptoms", "HYPOGLYCEMIA_SIGNS", "餐前饥饿感、心慌、出汗", 40);
    }

    private void seedPresentIllnessOptions() {
        seedOption("presentIllness", "NONE", "无", 260, null);
        seedOption("presentIllness", "HYPERTENSION", "高血压", 250, null);
        seedOption("presentIllness", "CHD", "冠心病", 245, null);
        seedOption("presentIllness", "STROKE", "脑卒中", 240, null);
        seedOption("presentIllness", "HEART_FAILURE", "心力衰竭", 235, null);
        seedOption("presentIllness", "ATRIAL_FIBRILLATION", "心房颤动", 230, null);
        seedOption("presentIllness", "DIABETES", "2型糖尿病", 225, null);
        seedOption("presentIllness", "HYPERLIPIDEMIA", "高脂血症", 220, null);
        seedOption("presentIllness", "GOUT", "高尿酸血症/痛风", 215, null);
        seedOption("presentIllness", "COPD", "慢性阻塞性肺疾病", 210, null);
        seedOption("presentIllness", "ASTHMA", "支气管哮喘", 205, null);
        seedOption("presentIllness", "CKD", "慢性肾脏病", 200, null);
        seedOption("presentIllness", "CHRONIC_HEPATITIS", "慢性肝炎", 195, null);
        seedOption("presentIllness", "CIRRHOSIS", "肝硬化", 190, null);
        seedOption("presentIllness", "NAFLD", "脂肪肝", 185, null);
        seedOption("presentIllness", "CHRONIC_GI_DISEASE", "慢性胃炎/消化性溃疡", 180, null);
        seedOption("presentIllness", "OSTEOPOROSIS", "骨质疏松症", 175, null);
        seedOption("presentIllness", "RHEUMATOID_ARTHRITIS", "类风湿关节炎", 170, null);
        seedOption("presentIllness", "OSTEOARTHRITIS", "骨关节炎", 165, null);
        seedOption("presentIllness", "ALZHEIMERS", "阿尔茨海默病", 160, null);
        seedOption("presentIllness", "PARKINSONS", "帕金森病", 155, null);
        seedOption("presentIllness", "DEPRESSION", "抑郁症", 150, null);
        seedOption("presentIllness", "ANXIETY", "焦虑症", 145, null);
        seedOption("presentIllness", "MALIGNANT_TUMOR", "恶性肿瘤", 140, null);
        seedOption("presentIllness", "BPH", "良性前列腺增生", 135, null);
        seedOption("presentIllness", "THYROID_DISORDER", "甲状腺功能减退/亢进", 130, null);
    }

    private void seedDisease(String code, String desc, int sort) {
        insert(DictTypeEnum.DISEASE.name(), "0", code, desc, "{}", sort);
    }

    private void seedBasicField(String code, String desc, String content, int sort) {
        insert(DictTypeEnum.FIELD.name(), "basic_archive", code, desc, content, sort);
    }

    private void seedDiseaseField(String parent, String code, String desc, String content, int sort) {
        insert(DictTypeEnum.DISEASE_FIELD.name(), parent, code, desc, content, sort);
    }

    private void seedDiseaseFieldIfAbsent(
            String parent, String code, String desc, String content, int sort) {
        if (hasDict(DictTypeEnum.DISEASE_FIELD.name(), parent, code)) {
            return;
        }
        seedDiseaseField(parent, code, desc, content, sort);
    }

    private void seedOption(String parent, String code, String desc, int sort, String content) {
        insert(DictTypeEnum.OPTION.name(), parent, code, desc, content == null ? "{}" : content, sort);
    }

    private void seedOptionIfAbsent(String parent, String code, String desc, int sort) {
        if (hasDict(DictTypeEnum.OPTION.name(), parent, code)) {
            return;
        }
        seedOption(parent, code, desc, sort, null);
    }

    private boolean hasDict(String dictType, String parentCode, String dictCode) {
        List<SysDict> rows =
                sysDictMapper.listByTypeAndParent(DictService.PLATFORM_TENANT, dictType, parentCode);
        return rows.stream().anyMatch(r -> dictCode.equals(r.getDictCode()));
    }

    private void insert(String type, String parent, String code, String desc, String content, int sort) {
        SysDict row = new SysDict();
        row.setDictType(type);
        row.setParentCode(parent);
        row.setDictCode(code);
        row.setDictCodeDesc(desc);
        row.setContent(content);
        row.setSortOrder(sort);
        row.setTenantId(DictService.PLATFORM_TENANT);
        EntityMeta.onCreate(row);
        sysDictMapper.insert(row);
    }
}
