package com.healix.agent.extract;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatExtractOption {
    private String value;
    private String label;

    public ChatExtractOption() {}

    public ChatExtractOption(String value, String label) {
        this.value = value;
        this.label = label;
    }
}
