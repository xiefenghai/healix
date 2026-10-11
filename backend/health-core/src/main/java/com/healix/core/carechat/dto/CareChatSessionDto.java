package com.healix.core.carechat.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** B 端打开沟通 Tab：线程 + 最近消息 + 可否发送。 */
@Getter
@Setter
public class CareChatSessionDto {
    private CareChatThreadDto thread;
    private List<CareChatMessageDto> messages = new ArrayList<>();
    private boolean patientLinked;
    private boolean canSend;
    private String blockReason;
}
