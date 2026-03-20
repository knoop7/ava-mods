package com.ava.mods.mimiclaw.bus;

import android.util.Log;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MessageBus {
    private static final String TAG = "MessageBus";
    private static final int QUEUE_SIZE = 16;
    
    private static volatile MessageBus instance;
    private final BlockingQueue<Message> inboundQueue;
    private final BlockingQueue<Message> outboundQueue;
    
    public static class Message {
        public String channel;
        public String chatId;
        public String content;
        
        public Message(String channel, String chatId, String content) {
            this.channel = channel;
            this.chatId = chatId;
            this.content = content;
        }
    }
    
    private MessageBus() {
        this.inboundQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        this.outboundQueue = new LinkedBlockingQueue<>(QUEUE_SIZE);
    }
    
    public static MessageBus getInstance() {
        if (instance == null) {
            synchronized (MessageBus.class) {
                if (instance == null) {
                    instance = new MessageBus();
                }
            }
        }
        return instance;
    }
    
    public boolean pushInbound(Message msg) {
        boolean result = inboundQueue.offer(msg);
        if (!result) {
            Log.w(TAG, "Inbound queue full, message dropped");
        }
        return result;
    }
    
    public Message popInbound(long timeoutMs) throws InterruptedException {
        if (timeoutMs == 0) {
            return inboundQueue.poll();
        }
        return inboundQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    public boolean pushOutbound(Message msg) {
        boolean result = outboundQueue.offer(msg);
        if (!result) {
            Log.w(TAG, "Outbound queue full, message dropped");
        }
        return result;
    }
    
    public Message popOutbound(long timeoutMs) throws InterruptedException {
        if (timeoutMs == 0) {
            return outboundQueue.poll();
        }
        return outboundQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    public int getInboundSize() {
        return inboundQueue.size();
    }
    
    public int getOutboundSize() {
        return outboundQueue.size();
    }

    public void clearInbound(String channel, String chatId) {
        inboundQueue.removeIf(msg -> sameTarget(msg, channel, chatId));
    }

    public void clearOutbound(String channel, String chatId) {
        outboundQueue.removeIf(msg -> sameTarget(msg, channel, chatId));
    }

    private boolean sameTarget(Message msg, String channel, String chatId) {
        if (msg == null) {
            return false;
        }
        boolean channelMatch = channel == null || channel.equals(msg.channel);
        boolean chatMatch = chatId == null || chatId.equals(msg.chatId);
        return channelMatch && chatMatch;
    }
}
