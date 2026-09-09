package com.healix.core.notify.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

@Getter
public class NotifyPublishResult {
    private final List<String> messageIds = new ArrayList<>();
    private final List<String> skippedExistingIds = new ArrayList<>();

    public void addCreated(String id) {
        messageIds.add(id);
    }

    public void addExisting(String id) {
        skippedExistingIds.add(id);
        messageIds.add(id);
    }
}
