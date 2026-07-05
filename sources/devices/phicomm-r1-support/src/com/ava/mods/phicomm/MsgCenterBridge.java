package com.ava.mods.phicomm;

import android.content.Context;
import android.os.Parcelable;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * msgcenter IPC via reflection — {@code MessageDispatchManager} lives in Phicomm framework.
 */
final class MsgCenterBridge {
    private static final String TAG = "PhicommMsgCenter";
    private static final String MSG_CENTER = "msgcenter";
    private static final String SEND_MESSAGE = "sendMessage";
    private static final String REGISTER = "registerMessageReceiver";
    private static final String UNREGISTER = "unregisterMessageReceiver";

    interface MessageCallback {
        void onMessage(int what, int arg1, int arg2, Object payload);
    }

    private final Object messageManager;
    private final Class<?> receiverInterface;
    private final List<Registration> registrations = new ArrayList<Registration>();

    private static final class Registration {
        final Object proxy;
        final int flags;

        Registration(Object proxy, int flags) {
            this.proxy = proxy;
            this.flags = flags;
        }
    }

    MsgCenterBridge(Context context) {
        Object service = context.getApplicationContext().getSystemService(MSG_CENTER);
        Class<?> receiver = null;
        if (service != null && hasSendMessage(service)) {
            messageManager = service;
            receiver = loadReceiverInterface(service.getClass());
        } else {
            messageManager = null;
            Log.w(TAG, "msgcenter service unavailable");
        }
        receiverInterface = receiver;
    }

    boolean isAvailable() {
        return messageManager != null;
    }

    void sendMessage(int what, int arg1, int arg2, Parcelable data) {
        if (messageManager == null) {
            return;
        }
        try {
            Log.d(TAG, "sendMessage what=" + what + " arg1=" + arg1 + " arg2=" + arg2);
            messageManager.getClass()
                .getMethod(SEND_MESSAGE, int.class, int.class, int.class, Parcelable.class)
                .invoke(messageManager, what, arg1, arg2, data);
        } catch (Throwable t) {
            Log.w(TAG, "sendMessage failed what=" + what + " arg1=" + arg1, t);
        }
    }

    boolean registerMessageReceiver(int flags, final MessageCallback callback) {
        if (messageManager == null || receiverInterface == null || callback == null) {
            return false;
        }
        try {
            Object proxy = Proxy.newProxyInstance(
                receiverInterface.getClassLoader(),
                new Class<?>[] { receiverInterface },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("notifyMsg".equals(method.getName()) && args != null && args.length >= 4) {
                            callback.onMessage(
                                ((Integer) args[0]).intValue(),
                                ((Integer) args[1]).intValue(),
                                ((Integer) args[2]).intValue(),
                                args[3]
                            );
                        }
                        return null;
                    }
                }
            );
            messageManager.getClass()
                .getMethod(REGISTER, receiverInterface, int.class)
                .invoke(messageManager, proxy, flags);
            registrations.add(new Registration(proxy, flags));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "registerMessageReceiver failed flags=" + flags, t);
            return false;
        }
    }

    void unregisterAll() {
        if (messageManager == null || receiverInterface == null) {
            registrations.clear();
            return;
        }
        for (Registration registration : registrations) {
            try {
                messageManager.getClass()
                    .getMethod(UNREGISTER, receiverInterface, int.class)
                    .invoke(messageManager, registration.proxy, registration.flags);
            } catch (Throwable ignored) {
                try {
                    messageManager.getClass()
                        .getMethod(UNREGISTER, receiverInterface)
                        .invoke(messageManager, registration.proxy);
                } catch (Throwable t) {
                    Log.d(TAG, "unregister failed", t);
                }
            }
        }
        registrations.clear();
    }

    private static Class<?> loadReceiverInterface(Class<?> managerClass) {
        for (Class<?> inner : managerClass.getDeclaredClasses()) {
            if ("MessageReceiver".equals(inner.getSimpleName())) {
                return inner;
            }
        }
        try {
            return Class.forName("android.os.MessageDispatchManager$MessageReceiver");
        } catch (Throwable t) {
            Log.w(TAG, "MessageReceiver interface not found", t);
            return null;
        }
    }

    private static boolean hasSendMessage(Object service) {
        try {
            service.getClass().getMethod(SEND_MESSAGE, int.class, int.class, int.class, Parcelable.class);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
