package com.healix.agent.safety.chain;

import com.healix.core.identity.service.IdentityService;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AllergenSafetyHandler extends SafetyHandler {

    private final IdentityService identityService;

    @Override
    protected void doHandle(SafetyContext context) {
        String reply = context.getReply();
        if (reply == null || context.getPatientId() == null) {
            return;
        }
        List<String> allergens = identityService.peopleAllergens(context.getPatientId());
        if (allergens.isEmpty()) {
            return;
        }
        String lower = reply.toLowerCase(Locale.ROOT);
        for (String allergen : allergens) {
            if (allergen != null && !allergen.isBlank() && lower.contains(allergen.toLowerCase(Locale.ROOT))) {
                log.warn(
                        "Safety block: allergen '{}' found in reply for patientId={}",
                        allergen,
                        context.getPatientId());
                context.setReply("检测到推荐内容可能含有您的过敏原（" + allergen + "），已拦截该建议。请更换食材或咨询医生。");
                return;
            }
        }
    }
}
