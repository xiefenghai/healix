package com.healix.core.adherence.dto;

import lombok.Data;

@Data
public class AdherenceNudgeResultDto {
    private boolean sent;
    /** OK / NO_LINKED_ACCOUNT / NOTHING_PENDING / FAILED */
    private String reason;
    private String message;
    private int recipientCount;
    private String linkPath;
}
