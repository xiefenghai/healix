package com.healix.agent.react;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StaffCockpitAgentConfig {

    @Bean
    @ConditionalOnBean(name = "healixChatModel")
    public StaffCockpitAgent staffCockpitAgent(
            @Qualifier("healixChatModel") ChatLanguageModel chatLanguageModel,
            StaffAgentTools staffAgentTools) {
        return AiServices.builder(StaffCockpitAgent.class)
                .chatLanguageModel(chatLanguageModel)
                .tools(staffAgentTools)
                .build();
    }
}
