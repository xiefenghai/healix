package com.healix.agent.extract;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatExtractIgnored {
    private String quote;
    private String reason;

    public ChatExtractIgnored() {}

    public ChatExtractIgnored(String quote, String reason) {
        this.quote = quote;
        this.reason = reason;
    }
}
