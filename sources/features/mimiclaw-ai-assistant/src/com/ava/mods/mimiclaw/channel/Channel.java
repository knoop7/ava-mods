package com.ava.mods.mimiclaw.channel;

public interface Channel {
    String getName();
    void sendMessage(String chatId, String content);
    void setMessageListener(MessageListener listener);
    
    interface MessageListener {
        void onMessage(String chatId, String content);
        default void onMessage(String chatId, String content, String channelOverride) {
            onMessage(chatId, content);
        }
    }
}
