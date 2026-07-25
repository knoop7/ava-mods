package com.ava.mods.biometric;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full biometric gate for HA — mirrors the system enroll → verify flow.
 *
 * Third-party apps cannot write fingerprint templates into the TEE
 * ({@code MANAGE_FINGERPRINT} is system-only). This mod therefore:
 * <ol>
 *   <li>Shows a CinemaOverlay-style full-screen wizard</li>
 *   <li>Launches the real Settings enroll activities
 *       ({@code BiometricEnrollActivity} / {@code FingerprintEnrollIntroduction})</li>
 *   <li>On return to Ava, re-checks enrollment and continues to authenticate</li>
 * </ol>
 */
public class BiometricAuthManager {

    private static final String TAG = "BiometricAuth";

    private static final String ENTITY_AUTHENTICATED = "authenticated";
    private static final String ENTITY_AVAILABLE = "available";
    private static final String ENTITY_PROMPT_ACTIVE = "prompt_active";

    private static final String RESULT_IDLE = "idle";
    private static final String RESULT_SUCCESS = "success";
    private static final String RESULT_FAILED = "failed";
    private static final String RESULT_CANCELLED = "cancelled";
    private static final String RESULT_UNAVAILABLE = "unavailable";
    private static final String RESULT_ERROR = "error";
    private static final String RESULT_BUSY = "busy";

    private static final String DEFAULT_TITLE = "Verify fingerprint";
    private static final String DEFAULT_SUBTITLE = "Confirm to continue secure action";
    private static final String DEFAULT_NEGATIVE = "Cancel";
    private static final int DEFAULT_HOLD_SECONDS = 3;

    private static volatile BiometricAuthManager instance;

    private final Context context;
    private final Handler mainHandler;
    private final Executor mainExecutor;
    private final Map<String, CopyOnWriteArrayList<Object>> stateListeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<Object>>();

    private final AtomicBoolean promptActive = new AtomicBoolean(false);
    private final AtomicBoolean authenticated = new AtomicBoolean(false);
    private final AtomicBoolean awaitingEnrollReturn = new AtomicBoolean(false);
    private final AtomicBoolean continueAuthAfterEnroll = new AtomicBoolean(false);

    private volatile String lastResult = RESULT_IDLE;
    private volatile String promptTitle = DEFAULT_TITLE;
    private volatile String promptSubtitle = DEFAULT_SUBTITLE;
    private volatile String negativeButtonText = DEFAULT_NEGATIVE;
    private volatile int successHoldSeconds = DEFAULT_HOLD_SECONDS;

    private CancellationSignal cancellationSignal;
    private Runnable clearAuthenticatedRunnable;
    private View overlayRoot;
    private TextView overlayStatus;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private boolean lifecycleRegistered;

    private BiometricAuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.mainExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    command.run();
                } else {
                    mainHandler.post(command);
                }
            }
        };
        ensureLifecycleCallbacks();
        refreshAvailability();
    }

    public static BiometricAuthManager getInstance(Context context) {
        if (instance == null) {
            synchronized (BiometricAuthManager.class) {
                if (instance == null) {
                    instance = new BiometricAuthManager(context);
                }
            }
        }
        return instance;
    }

    public void applyConfig(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        switch (key) {
            case "prompt_title":
                promptTitle = value.trim().isEmpty() ? DEFAULT_TITLE : value.trim();
                break;
            case "prompt_subtitle":
                promptSubtitle = value.trim().isEmpty() ? DEFAULT_SUBTITLE : value.trim();
                break;
            case "negative_button":
                negativeButtonText = value.trim().isEmpty() ? DEFAULT_NEGATIVE : value.trim();
                break;
            case "success_hold_seconds":
                successHoldSeconds = Math.max(1, Math.min(60, parseInt(value, DEFAULT_HOLD_SECONDS)));
                break;
            default:
                break;
        }
    }

    public void requestAuthentication() {
        mainExecutor.execute(new Runnable() {
            @Override
            public void run() {
                beginFlow(/* wantAuth */ true);
            }
        });
    }

    public void enrollFingerprint() {
        mainExecutor.execute(new Runnable() {
            @Override
            public void run() {
                beginFlow(/* wantAuth */ false);
            }
        });
    }

    public void cancelAuthentication() {
        mainExecutor.execute(new Runnable() {
            @Override
            public void run() {
                cancelInternal(RESULT_CANCELLED);
            }
        });
    }

    public boolean isAuthenticated() {
        return authenticated.get();
    }

    public boolean isAvailable() {
        return checkBiometricAvailable();
    }

    public boolean isPromptActive() {
        return promptActive.get() || awaitingEnrollReturn.get();
    }

    public boolean registerStateListener(String entityId, Object callback) {
        if (entityId == null || entityId.trim().isEmpty() || callback == null) {
            return false;
        }
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<Object>();
            stateListeners.put(entityId, listeners);
        }
        if (!listeners.contains(callback)) {
            listeners.add(callback);
        }
        pushCurrentState(entityId, callback);
        return true;
    }

    public void onDestroy() {
        cancelInternal(RESULT_IDLE);
        unregisterLifecycleCallbacks();
        stateListeners.clear();
        synchronized (BiometricAuthManager.class) {
            instance = null;
        }
    }

    // -------------------------------------------------------------------------
    // Flow
    // -------------------------------------------------------------------------

    private void beginFlow(boolean wantAuth) {
        if (promptActive.get() || awaitingEnrollReturn.get()) {
            setLastResult(RESULT_BUSY);
            Log.w(TAG, "Flow already active");
            return;
        }
        promptActive.set(true);
        notifyStateListeners(ENTITY_PROMPT_ACTIVE, Boolean.TRUE);
        continueAuthAfterEnroll.set(wantAuth);

        if (!hasBiometricPermission()) {
            showMessageOverlay(
                    "Permission missing",
                    "Host app needs USE_BIOMETRIC. Rebuild Ava with that permission.",
                    false);
            finishPrompt(RESULT_UNAVAILABLE, false);
            return;
        }

        if (!hasFingerprintHardware()) {
            showMessageOverlay(
                    "No fingerprint sensor",
                    "This device has no fingerprint hardware.",
                    false);
            finishPrompt(RESULT_UNAVAILABLE, false);
            return;
        }

        if (!isFingerprintEnrolled()) {
            Log.i(TAG, "Not enrolled — opening system enroll flow (TEE registration)");
            showEnrollWizardAndLaunch(wantAuth);
            return;
        }

        if (!wantAuth) {
            // Explicit enroll while already enrolled → open manage / add another.
            showEnrollWizardAndLaunch(false);
            return;
        }

        startVerifyAfterReady();
    }

    private void startVerifyAfterReady() {
        bringAppToForegroundThen(new Runnable() {
            @Override
            public void run() {
                if (!promptActive.get()) {
                    return;
                }
                Activity activity = findResumedActivity();
                Log.i(TAG, "Verify activity=" + (activity != null ? activity.getClass().getName() : "null"));

                boolean started = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activity != null) {
                    started = startFrameworkBiometricPrompt(activity);
                }
                if (!started) {
                    started = startOverlayFingerprint();
                }
                if (!started) {
                    finishPrompt(RESULT_ERROR, false);
                }
            }
        });
    }

    private void showEnrollWizardAndLaunch(final boolean wantAuthAfter) {
        continueAuthAfterEnroll.set(wantAuthAfter);
        showEnrollWizardUi();
        // Auto-jump into the real system enroll (same as Settings → Fingerprint).
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!promptActive.get() && !awaitingEnrollReturn.get()) {
                    return;
                }
                launchSystemFingerprintEnroll();
            }
        }, 400);
    }

    private void launchSystemFingerprintEnroll() {
        awaitingEnrollReturn.set(true);
        notifyStateListeners(ENTITY_PROMPT_ACTIVE, Boolean.TRUE);
        updateOverlayStatus("Opening system fingerprint enrollment…\nConfirm your lock screen, then add a print.");

        if (!launchFingerprintEnrollment()) {
            awaitingEnrollReturn.set(false);
            updateOverlayStatus("Could not open system enrollment. Open Settings → Fingerprint manually.");
            finishPrompt(RESULT_ERROR, false);
            return;
        }
        Log.i(TAG, "Awaiting return from system enroll");
    }

    private void onHostActivityResumed() {
        if (!awaitingEnrollReturn.get()) {
            return;
        }
        // Small delay so Settings finishes writing enrollment state.
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!awaitingEnrollReturn.compareAndSet(true, false)) {
                    return;
                }
                boolean enrolled = isFingerprintEnrolled();
                refreshAvailability();
                Log.i(TAG, "Returned from enroll UI, enrolled=" + enrolled
                        + " continueAuth=" + continueAuthAfterEnroll.get());

                if (enrolled) {
                    hideOverlay();
                    if (continueAuthAfterEnroll.get()) {
                        // Keep promptActive true and go verify.
                        promptActive.set(true);
                        notifyStateListeners(ENTITY_PROMPT_ACTIVE, Boolean.TRUE);
                        startVerifyAfterReady();
                    } else {
                        finishPrompt(RESULT_SUCCESS, false);
                        // Success here means enroll completed; no auth pulse.
                        setLastResult("enrolled");
                    }
                } else {
                    updateOverlayStatus("Fingerprint not enrolled yet.\nTap Retry to open enrollment again.");
                    // Stay on wizard; user can retry or cancel. Release busy lock partially.
                    promptActive.set(true);
                    showEnrollWizardUi();
                    updateOverlayStatus("Fingerprint not enrolled yet.\nTap Start enrollment to try again.");
                }
            }
        }, 500);
    }

    // -------------------------------------------------------------------------
    // System enroll intents (real TEE registration via Settings)
    // -------------------------------------------------------------------------

    private boolean launchFingerprintEnrollment() {
        PackageManager pm = context.getPackageManager();
        Intent[] candidates = buildEnrollmentIntents();
        for (int i = 0; i < candidates.length; i++) {
            Intent intent = candidates[i];
            try {
                if (intent.getComponent() == null && intent.resolveActivity(pm) == null) {
                    continue;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "Started enroll: " + describeIntent(intent));
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "Enroll intent failed: " + describeIntent(intent), t);
            }
        }
        return false;
    }

    private Intent[] buildEnrollmentIntents() {
        ArrayList<Intent> list = new ArrayList<Intent>();

        // Explicit AOSP / Lineage components (confirmed on this device).
        list.add(explicit("com.android.settings",
                "com.android.settings.biometrics.fingerprint.FingerprintEnrollIntroduction"));
        list.add(explicit("com.android.settings",
                "com.android.settings.biometrics.BiometricEnrollActivity"));
        list.add(explicit("com.android.settings",
                "com.android.settings.Settings$FingerprintSettingsActivity"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            list.add(new Intent(Settings.ACTION_FINGERPRINT_ENROLL));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent biometric = new Intent(Settings.ACTION_BIOMETRIC_ENROLL);
            biometric.putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            list.add(biometric);
        }
        list.add(new Intent("android.settings.FINGERPRINT_SETTINGS"));
        list.add(new Intent("android.settings.FINGERPRINT_ENROLL"));
        list.add(new Intent("android.settings.FINGERPRINT_SETUP"));
        list.add(new Intent(Settings.ACTION_SECURITY_SETTINGS));
        return list.toArray(new Intent[0]);
    }

    private static Intent explicit(String pkg, String cls) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, cls));
        return i;
    }

    private static String describeIntent(Intent intent) {
        if (intent.getComponent() != null) {
            return intent.getComponent().flattenToShortString();
        }
        return String.valueOf(intent.getAction());
    }

    // -------------------------------------------------------------------------
    // Verify
    // -------------------------------------------------------------------------

    private void bringAppToForegroundThen(final Runnable next) {
        Activity existing = findResumedActivity();
        if (existing != null) {
            next.run();
            return;
        }
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(launch);
            }
        } catch (Throwable t) {
            Log.w(TAG, "bring to front failed", t);
        }
        mainHandler.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (!promptActive.get()) {
                    return;
                }
                if (findResumedActivity() != null || tries >= 12) {
                    next.run();
                    return;
                }
                tries++;
                mainHandler.postDelayed(this, 100);
            }
        }, 200);
    }

    private boolean startFrameworkBiometricPrompt(Activity activity) {
        try {
            cancellationSignal = new CancellationSignal();
            android.hardware.biometrics.BiometricPrompt.Builder builder =
                    new android.hardware.biometrics.BiometricPrompt.Builder(activity)
                            .setTitle(promptTitle)
                            .setSubtitle(promptSubtitle)
                            .setNegativeButton(
                                    negativeButtonText,
                                    mainExecutor,
                                    new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            cancelInternal(RESULT_CANCELLED);
                                        }
                                    });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            }
            builder.build().authenticate(
                    cancellationSignal,
                    mainExecutor,
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            finishPrompt(RESULT_SUCCESS, true);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            setLastResult(RESULT_FAILED);
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                    || errorCode == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                    || errorCode == 13) {
                                finishPrompt(RESULT_CANCELLED, false);
                            } else if (errorCode
                                    == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS) {
                                showEnrollWizardAndLaunch(true);
                            } else {
                                Log.w(TAG, "BiometricPrompt error " + errorCode + ": " + errString);
                                if (!startOverlayFingerprint()) {
                                    finishPrompt(RESULT_ERROR, false);
                                }
                            }
                        }
                    });
            Log.i(TAG, "BiometricPrompt started");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "BiometricPrompt failed", t);
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean startOverlayFingerprint() {
        try {
            FingerprintManager fm =
                    (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
            if (fm == null || !fm.isHardwareDetected() || !fm.hasEnrolledFingerprints()) {
                return false;
            }
            if (!hasBiometricPermission()) {
                return false;
            }
            showVerifyOverlay();
            cancellationSignal = new CancellationSignal();
            fm.authenticate(
                    null,
                    cancellationSignal,
                    0,
                    new FingerprintManager.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                FingerprintManager.AuthenticationResult result) {
                            finishPrompt(RESULT_SUCCESS, true);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            setLastResult(RESULT_FAILED);
                            updateOverlayStatus("Try again");
                        }

                        @Override
                        public void onAuthenticationError(int errorCode, CharSequence errString) {
                            if (errorCode == FingerprintManager.FINGERPRINT_ERROR_USER_CANCELED
                                    || errorCode == FingerprintManager.FINGERPRINT_ERROR_CANCELED) {
                                finishPrompt(RESULT_CANCELLED, false);
                            } else if (errorCode
                                    == FingerprintManager.FINGERPRINT_ERROR_NO_FINGERPRINTS) {
                                showEnrollWizardAndLaunch(true);
                            } else {
                                Log.w(TAG, "FingerprintManager error " + errorCode + ": " + errString);
                                finishPrompt(RESULT_ERROR, false);
                            }
                        }

                        @Override
                        public void onAuthenticationHelp(int helpCode, CharSequence helpString) {
                            if (helpString != null) {
                                updateOverlayStatus(helpString.toString());
                            }
                        }
                    },
                    mainHandler);
            Log.i(TAG, "Overlay FingerprintManager listening");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Overlay fingerprint failed", t);
            hideOverlay();
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Overlay UI (CinemaOverlay contract)
    // -------------------------------------------------------------------------

    private void showEnrollWizardUi() {
        hideOverlayViewsOnly();
        LinearLayout root = baseOverlayRoot();

        TextView title = titleView("Set up fingerprint");
        TextView body = bodyView(
                "No fingerprint is enrolled on this device.\n\n"
                        + "Next step opens the system enrollment screen "
                        + "(same as Settings → Fingerprint). "
                        + "The print is stored in the secure chip — Ava never sees the template.\n\n"
                        + "You may need to confirm your pattern / PIN first.");
        overlayStatus = statusView("Preparing system enrollment…");

        Button start = primaryButton("Start enrollment");
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSystemFingerprintEnroll();
            }
        });

        Button cancel = secondaryButton(negativeButtonText);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelInternal(RESULT_CANCELLED);
            }
        });

        root.addView(title);
        root.addView(body);
        root.addView(overlayStatus);
        root.addView(start);
        root.addView(spacer(24));
        root.addView(cancel);
        attachOverlay(root);
    }

    private void showVerifyOverlay() {
        hideOverlayViewsOnly();
        LinearLayout root = baseOverlayRoot();
        root.addView(titleView(promptTitle));
        root.addView(bodyView(promptSubtitle));
        overlayStatus = statusView("Touch the fingerprint sensor");
        root.addView(overlayStatus);
        Button cancel = secondaryButton(negativeButtonText);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelInternal(RESULT_CANCELLED);
            }
        });
        root.addView(spacer(24));
        root.addView(cancel);
        attachOverlay(root);
    }

    private void showMessageOverlay(String title, String body, boolean withCancel) {
        hideOverlayViewsOnly();
        LinearLayout root = baseOverlayRoot();
        root.addView(titleView(title));
        root.addView(bodyView(body));
        if (withCancel) {
            Button cancel = secondaryButton(negativeButtonText);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelInternal(RESULT_CANCELLED);
                }
            });
            root.addView(cancel);
        }
        attachOverlay(root);
    }

    private LinearLayout baseOverlayRoot() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(72, 72, 72, 72);
        root.setBackgroundColor(Color.argb(240, 12, 12, 16));
        root.setGravity(Gravity.CENTER);
        return root;
    }

    private TextView titleView(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(26f);
        t.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView bodyView(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(Color.LTGRAY);
        t.setTextSize(15f);
        t.setPadding(0, 28, 0, 28);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView statusView(String text) {
        TextView t = new TextView(context);
        t.setTag("status");
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 8, 0, 36);
        return t;
    }

    private Button primaryButton(String text) {
        Button b = new Button(context);
        b.setText(text);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(context);
        b.setText(text);
        return b;
    }

    private View spacer(int px) {
        View v = new View(context);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px));
        return v;
    }

    private void attachOverlay(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No SYSTEM_ALERT_WINDOW — continuing without overlay chrome");
            overlayRoot = null;
            return;
        }
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) {
                return;
            }
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    flags,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            wm.addView(root, params);
            overlayRoot = root;
        } catch (Throwable t) {
            Log.w(TAG, "attachOverlay failed", t);
            overlayRoot = null;
        }
    }

    private void updateOverlayStatus(final String message) {
        mainExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (overlayStatus != null) {
                    overlayStatus.setText(message);
                } else if (overlayRoot != null) {
                    View status = overlayRoot.findViewWithTag("status");
                    if (status instanceof TextView) {
                        ((TextView) status).setText(message);
                    }
                }
            }
        });
    }

    private void hideOverlayViewsOnly() {
        if (overlayRoot == null) {
            return;
        }
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && overlayRoot.isAttachedToWindow()) {
                wm.removeView(overlayRoot);
            }
        } catch (Throwable ignored) {
        }
        overlayRoot = null;
        overlayStatus = null;
    }

    private void hideOverlay() {
        hideOverlayViewsOnly();
    }

    // -------------------------------------------------------------------------
    // Lifecycle — detect return from Settings enroll
    // -------------------------------------------------------------------------

    private void ensureLifecycleCallbacks() {
        if (lifecycleRegistered) {
            return;
        }
        if (!(context instanceof Application)) {
            // Application context from getApplicationContext() is Application.
        }
        Application app = null;
        try {
            Context c = context;
            if (c instanceof Application) {
                app = (Application) c;
            } else if (c.getApplicationContext() instanceof Application) {
                app = (Application) c.getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        if (app == null) {
            Log.w(TAG, "No Application — enroll return auto-continue disabled");
            return;
        }
        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                if (activity != null
                        && activity.getPackageName().equals(context.getPackageName())) {
                    onHostActivityResumed();
                }
            }

            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        };
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks);
        lifecycleRegistered = true;
    }

    private void unregisterLifecycleCallbacks() {
        if (!lifecycleRegistered || lifecycleCallbacks == null) {
            return;
        }
        try {
            Application app = (Application) context.getApplicationContext();
            app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
        } catch (Throwable ignored) {
        }
        lifecycleRegistered = false;
        lifecycleCallbacks = null;
    }

    // -------------------------------------------------------------------------
    // Finish / cancel / state
    // -------------------------------------------------------------------------

    private void finishPrompt(String result, boolean success) {
        awaitingEnrollReturn.set(false);
        hideOverlay();
        cancellationSignal = null;
        boolean wasActive = promptActive.getAndSet(false);
        if (wasActive || isPromptActive()) {
            notifyStateListeners(ENTITY_PROMPT_ACTIVE, Boolean.FALSE);
        }
        setLastResult(result);
        if (success) {
            pulseAuthenticated();
        }
        refreshAvailability();
    }

    private void cancelInternal(String result) {
        awaitingEnrollReturn.set(false);
        continueAuthAfterEnroll.set(false);
        if (cancellationSignal != null) {
            try {
                if (!cancellationSignal.isCanceled()) {
                    cancellationSignal.cancel();
                }
            } catch (Throwable ignored) {
            }
            cancellationSignal = null;
        }
        hideOverlay();
        promptActive.set(false);
        notifyStateListeners(ENTITY_PROMPT_ACTIVE, Boolean.FALSE);
        if (result != null && !RESULT_IDLE.equals(result)) {
            setLastResult(result);
        }
        refreshAvailability();
    }

    private void pulseAuthenticated() {
        if (clearAuthenticatedRunnable != null) {
            mainHandler.removeCallbacks(clearAuthenticatedRunnable);
        }
        authenticated.set(true);
        notifyStateListeners(ENTITY_AUTHENTICATED, Boolean.TRUE);
        final int holdMs = Math.max(1, successHoldSeconds) * 1000;
        clearAuthenticatedRunnable = new Runnable() {
            @Override
            public void run() {
                authenticated.set(false);
                notifyStateListeners(ENTITY_AUTHENTICATED, Boolean.FALSE);
                clearAuthenticatedRunnable = null;
            }
        };
        mainHandler.postDelayed(clearAuthenticatedRunnable, holdMs);
        Log.i(TAG, "Authenticated pulse " + holdMs + "ms");
    }

    private void setLastResult(String result) {
        lastResult = result == null ? RESULT_IDLE : result;
        Log.i(TAG, "result=" + lastResult);
    }

    private void refreshAvailability() {
        notifyStateListeners(ENTITY_AVAILABLE, Boolean.valueOf(checkBiometricAvailable()));
    }

    private boolean hasBiometricPermission() {
        if (Build.VERSION.SDK_INT >= 28
                && context.checkSelfPermission("android.permission.USE_BIOMETRIC")
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(android.Manifest.permission.USE_FINGERPRINT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("deprecation")
    private boolean hasFingerprintHardware() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                FingerprintManager fm =
                        (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
                if (fm != null && fm.isHardwareDetected()) {
                    return true;
                }
            }
            PackageManager pm = context.getPackageManager();
            return pm != null && pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isFingerprintEnrolled() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.hardware.biometrics.BiometricManager bm =
                        context.getSystemService(android.hardware.biometrics.BiometricManager.class);
                if (bm != null) {
                    int status = bm.canAuthenticate(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                    | android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
                    if (status == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                        return true;
                    }
                    if (status == android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                        return false;
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.hardware.biometrics.BiometricManager bm =
                        context.getSystemService(android.hardware.biometrics.BiometricManager.class);
                if (bm != null) {
                    int status = bm.canAuthenticate();
                    if (status == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS) {
                        return true;
                    }
                    if (status == android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                        return false;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                FingerprintManager fm =
                        (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
                return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
            }
        } catch (Throwable t) {
            Log.w(TAG, "isFingerprintEnrolled failed", t);
        }
        return false;
    }

    private boolean checkBiometricAvailable() {
        return hasBiometricPermission() && hasFingerprintHardware() && isFingerprintEnrolled();
    }

    private Activity findResumedActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = at.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object map = f.get(thread);
            if (!(map instanceof java.util.Map)) {
                return null;
            }
            Activity fallback = null;
            for (Object record : ((java.util.Map<?, ?>) map).values()) {
                Class<?> recCl = record.getClass();
                java.lang.reflect.Field activityField = recCl.getDeclaredField("activity");
                activityField.setAccessible(true);
                Object a = activityField.get(record);
                if (!(a instanceof Activity)) {
                    continue;
                }
                Activity act = (Activity) a;
                if (act.isFinishing()) {
                    continue;
                }
                java.lang.reflect.Field paused = recCl.getDeclaredField("paused");
                paused.setAccessible(true);
                if (!paused.getBoolean(record)) {
                    return act;
                }
                if (fallback == null) {
                    fallback = act;
                }
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void pushCurrentState(String entityId, Object callback) {
        if (ENTITY_AUTHENTICATED.equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(authenticated.get()));
        } else if (ENTITY_AVAILABLE.equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(checkBiometricAvailable()));
        } else if (ENTITY_PROMPT_ACTIVE.equals(entityId)) {
            notifySingleListener(callback, Boolean.valueOf(isPromptActive()));
        }
    }

    private void notifyStateListeners(String entityId, Object value) {
        CopyOnWriteArrayList<Object> listeners = stateListeners.get(entityId);
        if (listeners == null) {
            return;
        }
        for (Object callback : listeners) {
            notifySingleListener(callback, value);
        }
    }

    private void notifySingleListener(Object callback, Object value) {
        try {
            Method method;
            try {
                method = callback.getClass().getMethod("onStateChanged", Object.class);
            } catch (NoSuchMethodException e) {
                method = callback.getClass().getMethod("onState", Object.class);
            }
            method.invoke(callback, value);
        } catch (Exception e) {
            Log.w(TAG, "State callback failed", e);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
