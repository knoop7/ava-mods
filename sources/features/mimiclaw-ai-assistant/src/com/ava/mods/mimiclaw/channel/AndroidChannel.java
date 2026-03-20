package com.ava.mods.mimiclaw.channel;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class AndroidChannel implements Channel {
    private static final String TAG = "AndroidChannel";
    public static final String NAME = "android";
    
    private MessageListener listener;
    private static final class PendingResponse {
        final String chatId;
        final String content;

        PendingResponse(String chatId, String content) {
            this.chatId = chatId;
            this.content = content;
        }
    }

    private final List<PendingResponse> pendingResponses = new ArrayList<>();
    private String lastResponse = "";
    
    @Override
    public String getName() {
        return NAME;
    }
    
    @Override
    public void sendMessage(String chatId, String content) {
        lastResponse = content;
        synchronized (pendingResponses) {
            pendingResponses.add(new PendingResponse(chatId, content));
            pendingResponses.notifyAll();
        }
        Log.d(TAG, "Response queued for " + chatId + ": " + content.length() + " bytes");
    }
    
    @Override
    public void setMessageListener(MessageListener listener) {
        this.listener = listener;
    }
    
    public void injectMessage(String chatId, String content) {
        injectMessage(chatId, content, NAME);
    }

    public void injectMessage(String chatId, String content, String channelOverride) {
        if (listener != null) {
            listener.onMessage(chatId, content, channelOverride);
        }
    }
    
    public String getLastResponse() {
        return lastResponse;
    }
    
    public String pollResponse() {
        synchronized (pendingResponses) {
            if (pendingResponses.isEmpty()) {
                return null;
            }
            return pendingResponses.remove(0).content;
        }
    }

    public void clearResponsesForChat(String chatId) {
        synchronized (pendingResponses) {
            pendingResponses.removeIf(item -> chatId.equals(item.chatId));
        }
    }

    public String waitForResponse(String chatId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (pendingResponses) {
            while (true) {
                for (int i = 0; i < pendingResponses.size(); i++) {
                    PendingResponse item = pendingResponses.get(i);
                    if (chatId.equals(item.chatId)) {
                        pendingResponses.remove(i);
                        return item.content;
                    }
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                pendingResponses.wait(remaining);
            }
        }
    }
    
    public boolean hasResponse() {
        synchronized (pendingResponses) {
            return !pendingResponses.isEmpty();
        }
    }
}
