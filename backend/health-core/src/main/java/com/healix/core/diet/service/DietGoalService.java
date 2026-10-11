package com.healix.core.diet.service;

import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.common.domain.EntityMeta;
import com.healix.core.diet.mapper.DietGoalMapper;
import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.dto.DietGoalDto;
import com.healix.core.diet.dto.DietGoalStatusDto;
import com.healix.core.diet.enums.ActivityLevelEnum;
import com.healix.core.diet.enums.DietGoalTypeEnum;
import com.healix.core.diet.support.DietContextLoader;
import com.healix.core.diet.support.DietGoalJson;
import com.healix.core.diet.support.EnergyTargetCalculator;
import com.healix.core.diet.support.EnergyTargetCalculator.MacroRatio;
import com.healix.core.diet.support.EnergyTargetCalculator.Result;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.vitals.service.VitalService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 热量目标：患者自助计算（AUTO）或医护设定（STAFF）。
 *
 * <p>STAFF 目标生效期间患者不可修改，需医护撤销后才回到自助模式。
 */
@Service
@RequiredArgsConstructor
public class DietGoalService {

    public static final String SOURCE_AUTO = "AUTO";
    public static final String SOURCE_STAFF = "STAFF";

    private static final int MIN_AGE = 18;

    private final DietGoalMapper goalMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final VitalRecordMapper vitalRecordMapper;
    private final VitalService vitalService;
    private final DietContextLoader contextLoader;

    /**
     * 目标设定请求；身高体重为空时取最近一次体征记录。
     *
     * @param energyKcal 仅医护可用：直接指定每日热量（覆盖公式结果）
     */
    public record GoalRequest(
            String goalType,
            Double rateKgPerWeek,
            String activityLevel,
            Double heightCm,
            Double weightKg,
            Integer energyKcal,
            String note) {}

    public DietGoal current(String tenantId, String peopleId) {
        return goalMapper.findCurrent(tenantId, peopleId);
    }

    public DietGoalStatusDto status(String tenantId, String peopleId, boolean patientView) {
        PeopleProfile profile = requireProfile(tenantId, peopleId);
        DietGoal goal = current(tenantId, peopleId);
        Integer age = ageOf(profile.getBirthday());
        Double height = latestMetric(tenantId, peopleId, MetricTypeEnum.HEIGHT);
        Double weight = latestMetric(tenantId, peopleId, MetricTypeEnum.WEIGHT);
        CarePlanTemplateKeyEnum disease = contextLoader.load(tenantId, peopleId).diseaseKey();
        List<String> missing = new ArrayList<>();
        if (!isGenderKnown(profile.getGender())) {
            missing.add("GENDER");
        }
        if (age == null) {
            missing.add("BIRTHDAY");
        }
        if (height == null) {
            missing.add("HEIGHT");
        }
        if (weight == null) {
            missing.add("WEIGHT");
        }
        boolean editable = !patientView || goal == null || !SOURCE_STAFF.equals(goal.getSource());
        return new DietGoalStatusDto(
                DietGoalJson.toDto(goal),
                profile.getGender(),
                age,
                height,
                weight,
                disease.name(),
                DietGoalJson.diseaseLabel(disease.name()),
                missing,
                editable);
    }

    public DietGoalDto preview(String tenantId, String peopleId, GoalRequest req, boolean staff) {
        return DietGoalJson.toDto(build(tenantId, peopleId, req, staff));
    }

    @Transactional
    public DietGoalDto saveByPatient(String tenantId, String peopleId, GoalRequest req) {
        DietGoal existing = current(tenantId, peopleId);
        if (existing != null && SOURCE_STAFF.equals(existing.getSource())) {
            throw new BusinessException(403, "当前目标由医护设定，如需调整请联系你的医护团队");
        }
        DietGoal goal = build(tenantId, peopleId, req, false);
        syncVitals(tenantId, peopleId, req);
        return DietGoalJson.toDto(replace(existing, goal));
    }

    @Transactional
    public DietGoalDto saveByStaff(String tenantId, String peopleId, String staffId, GoalRequest req) {
        DietGoal goal = build(tenantId, peopleId, req, true);
        goal.setSource(SOURCE_STAFF);
        goal.setSetByStaffId(staffId);
        return DietGoalJson.toDto(replace(current(tenantId, peopleId), goal));
    }

    /** 撤销医护设定：按原参数回到公式计算的 AUTO 目标；档案不全时直接清空目标。 */
    @Transactional
    public DietGoalDto clearOverride(String tenantId, String peopleId) {
        DietGoal existing = current(tenantId, peopleId);
        if (existing == null || !SOURCE_STAFF.equals(existing.getSource())) {
            return DietGoalJson.toDto(existing);
        }
        GoalRequest req = new GoalRequest(
                existing.getGoalType(),
                existing.getRateKgPerWeek(),
                existing.getActivityLevel(),
                existing.getBasisHeightCm(),
                existing.getBasisWeightKg(),
                null,
                null);
        DietGoal auto;
        try {
            auto = build(tenantId, peopleId, req, false);
        } catch (BusinessException e) {
            goalMapper.softDelete(existing.getId());
            return null;
        }
        return DietGoalJson.toDto(replace(existing, auto));
    }

    private DietGoal replace(DietGoal existing, DietGoal next) {
        if (existing != null) {
            goalMapper.softDelete(existing.getId());
        }
        EntityMeta.onCreate(next);
        goalMapper.insert(next);
        return next;
    }

    private DietGoal build(String tenantId, String peopleId, GoalRequest req, boolean staff) {
        PeopleProfile profile = requireProfile(tenantId, peopleId);
        DietGoalTypeEnum goalType = DietGoalTypeEnum.parse(req.goalType());
        ActivityLevelEnum activity = ActivityLevelEnum.parse(req.activityLevel());
        double rate = validateRate(goalType, req.rateKgPerWeek());

        Double height = req.heightCm() != null ? req.heightCm() : latestMetric(tenantId, peopleId, MetricTypeEnum.HEIGHT);
        Double weight = req.weightKg() != null ? req.weightKg() : latestMetric(tenantId, peopleId, MetricTypeEnum.WEIGHT);
        Integer age = ageOf(profile.getBirthday());
        Integer customKcal = staff ? req.energyKcal() : null;
        if (customKcal != null && (customKcal < 800 || customKcal > 5000)) {
            throw new BusinessException(400, "每日热量需在 800–5000 kcal 之间");
        }

        if (customKcal == null) {
            if (!isGenderKnown(profile.getGender())) {
                throw new BusinessException(400, "请先在个人资料中完善性别");
            }
            if (age == null) {
                throw new BusinessException(400, "请先在个人资料中完善出生日期");
            }
            if (age < MIN_AGE) {
                throw new BusinessException(400, "未成年人的热量目标请由医护设定");
            }
            if (height == null || weight == null) {
                throw new BusinessException(400, "请填写身高和体重");
            }
        }
        if (height != null && (height < 100 || height > 230)) {
            throw new BusinessException(400, "身高需在 100–230 cm 之间");
        }
        if (weight != null && (weight < 30 || weight > 250)) {
            throw new BusinessException(400, "体重需在 30–250 kg 之间");
        }

        CarePlanTemplateKeyEnum disease = contextLoader.load(tenantId, peopleId).diseaseKey();
        DietGoal g = new DietGoal();
        g.setTenantId(tenantId);
        g.setPeopleId(peopleId);
        g.setSource(SOURCE_AUTO);
        g.setGoalType(goalType.name());
        g.setRateKgPerWeek(rate);
        g.setActivityLevel(activity.name());
        g.setDiseaseKey(disease.name());
        g.setBasisWeightKg(weight);
        g.setBasisHeightCm(height);
        g.setBasisAge(age);
        g.setBasisGender(profile.getGender());
        g.setFiberG(EnergyTargetCalculator.FIBER_TARGET_G);
        g.setSodiumMg(EnergyTargetCalculator.SODIUM_LIMIT_MG);
        g.setMealSplitJson(JsonUtils.toJson(EnergyTargetCalculator.defaultMealSplit()));
        g.setNote(StringUtils.hasText(req.note()) ? req.note().trim() : null);

        boolean canCompute = isGenderKnown(profile.getGender()) && age != null && height != null && weight != null;
        if (canCompute) {
            Result r = EnergyTargetCalculator.calculate(new EnergyTargetCalculator.Input(
                    profile.getGender(), age, height, weight, activity, goalType, rate, disease));
            g.setBmrKcal(r.bmrKcal());
            g.setTdeeKcal(r.tdeeKcal());
            g.setEnergyKcal(r.energyKcal());
            g.setCarbG(r.carbG());
            g.setProteinG(r.proteinG());
            g.setFatG(r.fatG());
            g.setFloorApplied(r.floorApplied() ? 1 : 0);
        }
        if (customKcal != null) {
            MacroRatio ratio = EnergyTargetCalculator.macroRatio(disease);
            g.setEnergyKcal(customKcal);
            g.setCarbG((int) Math.round(customKcal * ratio.carb() / 4));
            g.setProteinG((int) Math.round(customKcal * ratio.protein() / 4));
            g.setFatG((int) Math.round(customKcal * ratio.fat() / 9));
            g.setFloorApplied(0);
        }
        return g;
    }

    private static double validateRate(DietGoalTypeEnum type, Double rate) {
        if (type == DietGoalTypeEnum.MAINTAIN) {
            return 0;
        }
        double r = rate == null ? 0.5 : rate;
        double max = type == DietGoalTypeEnum.LOSE ? 1.0 : 0.5;
        if (r < 0.1 || r > max) {
            throw new BusinessException(400, String.format("每周%s速度需在 0.1–%.1f kg 之间", type.label(), max));
        }
        return r;
    }

    /** 患者填写的身高体重与最近记录不同才写入体征，避免重复数据。 */
    private void syncVitals(String tenantId, String peopleId, GoalRequest req) {
        writeIfChanged(tenantId, peopleId, MetricTypeEnum.HEIGHT, req.heightCm(), "cm");
        writeIfChanged(tenantId, peopleId, MetricTypeEnum.WEIGHT, req.weightKg(), "kg");
    }

    private void writeIfChanged(String tenantId, String peopleId, MetricTypeEnum type, Double value, String unit) {
        if (value == null) {
            return;
        }
        Double latest = latestMetric(tenantId, peopleId, type);
        if (latest != null && Math.abs(latest - value) < 0.05) {
            return;
        }
        vitalService.record(peopleId, type.name(), BigDecimal.valueOf(value), unit, null, VitalSourceEnum.SELF.name());
    }

    private Double latestMetric(String tenantId, String peopleId, MetricTypeEnum type) {
        List<VitalRecord> rows = vitalRecordMapper.listByPeople(tenantId, peopleId, type.name(), null, null, 1);
        if (rows == null || rows.isEmpty() || rows.get(0).getValue() == null) {
            return null;
        }
        return rows.get(0).getValue().doubleValue();
    }

    private PeopleProfile requireProfile(String tenantId, String peopleId) {
        PeopleProfile p = peopleProfileMapper.findById(peopleId);
        if (p == null || !tenantId.equals(p.getTenantId())) {
            throw new BusinessException(404, "患者不存在");
        }
        return p;
    }

    private static boolean isGenderKnown(String gender) {
        return "MALE".equalsIgnoreCase(gender) || "FEMALE".equalsIgnoreCase(gender);
    }

    private static Integer ageOf(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        return Period.between(birthday, LocalDate.now()).getYears();
    }
}
