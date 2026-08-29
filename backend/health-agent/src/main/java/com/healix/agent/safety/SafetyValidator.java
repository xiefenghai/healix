package com.healix.agent.safety;

import com.healix.agent.safety.chain.AllergenSafetyHandler;
import com.healix.agent.safety.chain.ExerciseGlucoseSafetyHandler;
import com.healix.agent.safety.chain.SafetyContext;
import com.healix.agent.safety.chain.SafetyHandler;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SafetyValidator {

    private final ExerciseGlucoseSafetyHandler exerciseGlucoseSafetyHandler;
    private final AllergenSafetyHandler allergenSafetyHandler;

    private SafetyHandler chainHead;

    @PostConstruct
    void buildChain() {
        List<SafetyHandler> handlers = new ArrayList<>();
        handlers.add(exerciseGlucoseSafetyHandler);
        handlers.add(allergenSafetyHandler);
        for (int i = 0; i < handlers.size() - 1; i++) {
            handlers.get(i).setNext(handlers.get(i + 1));
        }
        chainHead = handlers.get(0);
    }

    public String validate(String tenantId, String patientId, String draftReply) {
        return chainHead.handle(new SafetyContext(tenantId, patientId, draftReply));
    }
}
