package com.example.miniseckill.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Response for manual replay of dead or failed local messages.
 */
public class MessageReplayResponse {

    private int replayedCount;
    private int skippedCount;
    private List<String> replayedRequestIds = new ArrayList<>();
    private List<String> skippedRequestIds = new ArrayList<>();

    public int getReplayedCount() {
        return replayedCount;
    }

    public void setReplayedCount(int replayedCount) {
        this.replayedCount = replayedCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public void setSkippedCount(int skippedCount) {
        this.skippedCount = skippedCount;
    }

    public List<String> getReplayedRequestIds() {
        return replayedRequestIds;
    }

    public void setReplayedRequestIds(List<String> replayedRequestIds) {
        this.replayedRequestIds = replayedRequestIds;
    }

    public List<String> getSkippedRequestIds() {
        return skippedRequestIds;
    }

    public void setSkippedRequestIds(List<String> skippedRequestIds) {
        this.skippedRequestIds = skippedRequestIds;
    }

    public void addReplayed(String requestId) {
        replayedCount++;
        replayedRequestIds.add(requestId);
    }

    public void addSkipped(String requestId) {
        skippedCount++;
        skippedRequestIds.add(requestId);
    }
}
