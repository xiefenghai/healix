package com.healix.agent.safety.chain;

import com.healix.common.constant.HealthConstants;
import com.healix.core.vitals.service.VitalService;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExerciseGlucoseSafetyHandler extends SafetyHandler {

    private static final String[] EXERCISE_KEYWORDS = {
        "剧烈运动", "高强度", "跑步", "HIIT", "健身", "exercise", "workout", "running"
    };

    private final VitalService vitalService;

    @Override
    protected void doHandle(SafetyContext context) {
        String reply = context.getReply();
        if (reply == null || !containsExerciseAdvice(reply)) {
            return;
        }
        if (context.getTenantId() == null || context.getPatientId() == null) {
            return;
        }
        Optional<Double> avg =
                vitalService.averageGlucoseLastDays(context.getTenantId(), context.getPatientId(), 7);
        if (avg.isPresent() && avg.get() > HealthConstants.GLUCOSE_EXERCISE_BLOCK_THRESHOLD) {
            log.warn(
                    "Safety block: patientId={} glucoseAvg={} exceeds threshold",
                    context.getPatientId(),
                    avg.get());
            context.setReply("当前指标异常，请勿剧烈运动，建议就医。");
        }
    }

    private boolean containsExerciseAdvice(String reply) {
        String lower = reply.toLowerCase(Locale.ROOT);
        for (String kw : EXERCISE_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
