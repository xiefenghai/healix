package com.healix.core.carechat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CareChatSendRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;

    @Size(max = 64)
    private String clientMsgId;
}
