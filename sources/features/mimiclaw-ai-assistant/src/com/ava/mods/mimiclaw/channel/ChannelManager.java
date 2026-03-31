package com.ava.mods.mimiclaw.channel;

import android.util.Log;
import com.ava.mods.mimiclaw.bus.MessageBus;
import java.util.HashMap;
import java.util.Map;

public class ChannelManager {
    private static final String TAG = "ChannelManager";
    private static volatile ChannelManager instance;
    
    private final Map<String, Channel> channels = new HashMap<>();
    private final MessageBus messageBus;
    private Thread dispatcherThread;
    private volatile boolean running = true;
    
    private ChannelManager() {
        this.messageBus = MessageBus.getInstance();
    }
    
    public static ChannelManager getInstance() {
        if (instance == null) {
            synchronized (ChannelManager.class) {
                if (instance == null) {
                    instance = new ChannelManager();
                }
            }
        }
        return instance;
    }
    
    public void registerChannel(Channel channel) {
        channels.put(channel.getName(), channel);
        
        channel.setMessageListener(new Channel.MessageListener() {
            @Override
            public void onMessage(String chatId, String content) {
                onMessage(chatId, content, channel.getName());
            }
            @Override
            public void onMessage(String chatId, String content, String channelOverride) {
                String effectiveChannel = (channelOverride != null && !channelOverride.isEmpty()) 
                    ? channelOverride : channel.getName();
                MessageBus.Message msg = new MessageBus.Message(effectiveChannel, chatId, content);
                messageBus.pushInbound(msg);
                Log.d(TAG, "Inbound from " + effectiveChannel + ":" + chatId);
            }
        });
        
        Log.d(TAG, "Registered channel: " + channel.getName());
    }
    
    public void startOutboundDispatcher() {
        if (dispatcherThread != null && dispatcherThread.isAlive()) {
            return;
        }
        
        running = true;
        dispatcherThread = new Thread(() -> {
            Log.d(TAG, "Outbound dispatcher started");
            
            while (running) {
                try {
                    MessageBus.Message msg = messageBus.popOutbound(1000);
                    if (msg == null) {
                        continue;
                    }
                    
                    Channel channel = channels.get(msg.channel);
                    if (channel == null && "webconsole".equals(msg.channel)) {
                        channel = channels.get(AndroidChannel.NAME);
                    }
                    if (channel != null) {
                        try {
                            channel.sendMessage(msg.chatId, msg.content);
                            Log.d(TAG, "Dispatched to " + msg.channel + ":" + msg.chatId);
                        } catch (Exception sendError) {
                            Log.e(TAG, "Dispatch failed to " + msg.channel + ":" + msg.chatId, sendError);
                            Channel androidChannel = channels.get(AndroidChannel.NAME);
                            if (androidChannel != null && !AndroidChannel.NAME.equals(msg.channel)) {
                                try {
                                    androidChannel.sendMessage(
                                        "default",
                                        "Channel dispatch error [" + msg.channel + "]: " + sendError.getMessage()
                                    );
                                } catch (Exception ignored) {
                                    Log.w(TAG, "Failed to forward dispatch error to android channel", ignored);
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "Unknown channel: " + msg.channel);
                    }
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Dispatcher error", e);
                }
            }
            
            Log.d(TAG, "Outbound dispatcher stopped");
        }, "ChannelDispatcher");
        
        dispatcherThread.start();
    }
    
    public void stop() {
        running = false;
        if (dispatcherThread != null) {
            dispatcherThread.interrupt();
            dispatcherThread = null;
        }
    }
    
    public Channel getChannel(String name) {
        return channels.get(name);
    }
}
