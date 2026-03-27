package com.ava.mods.a64;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.io.File;
import java.io.InputStream;

public class VolumeControlService extends Service {
    
    public static final String ACTION_VOLUME_UP = "com.ava.mods.a64.VOLUME_UP";
    public static final String ACTION_VOLUME_DOWN = "com.ava.mods.a64.VOLUME_DOWN";
    private static final int MAX_VOLUME = 15;
    private static final float INTERVAL_VALUE = 1.875f;
    
    private static VolumeControlService instance = null;
    
    public static void volumeUp(Context context) {
        if (!A64DeviceSupportManager.getInstance(context).isA64Device()) return;
        if (instance != null) {
            instance.adjustVolume(true);
            instance.showVolume();
        } else {
            Intent intent = new Intent(context, VolumeControlService.class);
            intent.setAction(ACTION_VOLUME_UP);
            context.startService(intent);
        }
    }
    
    public static void volumeDown(Context context) {
        if (!A64DeviceSupportManager.getInstance(context).isA64Device()) return;
        if (instance != null) {
            instance.adjustVolume(false);
            instance.showVolume();
        } else {
            Intent intent = new Intent(context, VolumeControlService.class);
            intent.setAction(ACTION_VOLUME_DOWN);
            context.startService(intent);
        }
    }
    
    private WindowManager windowManager;
    private AudioManager audioManager;
    private VolumeView volumeView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createVolumeView();
    }
    
    private void createVolumeView() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
        volumeView = new VolumeView(this);
        
        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.CENTER;
        
        windowManager.addView(volumeView, layoutParams);
        volumeView.setVisibility(View.GONE);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_VOLUME_UP.equals(action)) {
                adjustVolume(true);
                showVolume();
            } else if (ACTION_VOLUME_DOWN.equals(action)) {
                adjustVolume(false);
                showVolume();
            }
        }
        return START_NOT_STICKY;
    }
    
    void adjustVolume(boolean isUp) {
        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int newVolume;
        if (isUp) {
            newVolume = Math.min(currentVolume + (int) INTERVAL_VALUE, MAX_VOLUME);
        } else {
            newVolume = Math.max(currentVolume - (int) INTERVAL_VALUE, 1);
        }
        newVolume = Math.max(1, Math.min(newVolume, MAX_VOLUME));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0);
    }
    
    void showVolume() {
        int volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int level = Math.max(1, Math.min((int) (volume / INTERVAL_VALUE), 8));
        
        volumeView.setLevel(level);
        volumeView.setVisibility(View.VISIBLE);
        
        if (hideRunnable != null) {
            handler.removeCallbacks(hideRunnable);
        }
        hideRunnable = new Runnable() {
            @Override
            public void run() {
                volumeView.setVisibility(View.GONE);
            }
        };
        handler.postDelayed(hideRunnable, 2000);
    }
    
    @Override
    public void onDestroy() {
        if (hideRunnable != null) {
            handler.removeCallbacks(hideRunnable);
        }
        if (volumeView != null) {
            try {
                windowManager.removeView(volumeView);
            } catch (Exception e) {
                // ignore
            }
        }
        volumeView = null;
        instance = null;
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    class VolumeView extends View {
        
        private final int maxLevel = 8;
        private int level = 4;
        
        private final int circleColor = Color.argb(200, 255, 255, 255);
        private final int selectedColor = Color.argb(255, 236, 28, 36);
        private final int unselectedColor = Color.WHITE;
        
        private final Paint paintCircle;
        private final Paint paintSelected;
        private final Paint paintUnselected;
        
        private final float soundRadius;
        private final float soundBetween;
        private final float soundMarginBottom;
        private final float viewSize;
        
        private Bitmap bitmapLow;
        private Bitmap bitmapMedium;
        private Bitmap bitmapHigh;
        private final int[] selections = {0, 3, 6};
        
        public VolumeView(Context context) {
            super(context);
            
            paintCircle = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintCircle.setColor(circleColor);
            
            paintSelected = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintSelected.setColor(selectedColor);
            
            paintUnselected = new Paint(Paint.ANTI_ALIAS_FLAG);
            paintUnselected.setColor(unselectedColor);
            
            soundRadius = dpToPx(5f);
            soundBetween = dpToPx(10f);
            soundMarginBottom = dpToPx(90f);
            viewSize = dpToPx(300f);
            
            loadBitmaps(context);
        }
        
        private void loadBitmaps(Context context) {
            try {
                String modDir = context.getFilesDir() + "/mods/a64-device-support/res/drawable/";
                File lowFile = new File(modDir + "sound_low.png");
                File mediumFile = new File(modDir + "sound_medium.png");
                File highFile = new File(modDir + "sound_high.png");
                
                if (lowFile.exists()) {
                    bitmapLow = BitmapFactory.decodeFile(lowFile.getAbsolutePath());
                }
                if (mediumFile.exists()) {
                    bitmapMedium = BitmapFactory.decodeFile(mediumFile.getAbsolutePath());
                }
                if (highFile.exists()) {
                    bitmapHigh = BitmapFactory.decodeFile(highFile.getAbsolutePath());
                }
            } catch (Exception e) {
                // ignore
            }
        }
        
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension((int) viewSize, (int) viewSize);
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            int w = getWidth();
            int h = getHeight();
            int cx = w / 2;
            int cy = h / 2;
            int radius = (w > h ? h : w) / 2;
            int marginBottom = (int) dpToPx(23f);
            
            canvas.drawCircle(cx, cy, radius, paintCircle);
            drawSound(canvas, cx, h - marginBottom);
            drawImg(canvas, cx, cy - marginBottom);
        }
        
        private void drawSound(Canvas canvas, int x, int y) {
            int size = (int) (soundRadius * 8 + soundBetween * 7);
            int startX = x - size / 2;
            int startY = (int) (y - soundMarginBottom);
            
            for (int i = 1; i <= 8; i++) {
                Paint paint = (i <= level) ? paintSelected : paintUnselected;
                canvas.drawCircle(startX, startY, soundRadius, paint);
                startX += (int) (soundRadius + soundBetween);
            }
        }
        
        private void drawImg(Canvas canvas, int x, int y) {
            int idx = 0;
            for (int i = 0; i < selections.length; i++) {
                if (selections[i] <= level) {
                    idx = i;
                }
            }
            
            Bitmap bitmap = null;
            if (idx == 0) bitmap = bitmapLow;
            else if (idx == 1) bitmap = bitmapMedium;
            else if (idx == 2) bitmap = bitmapHigh;
            
            if (bitmap != null) {
                int bw = bitmap.getWidth();
                int bh = bitmap.getHeight();
                int l = x - bw / 2;
                int t = y - bh / 2;
                canvas.drawBitmap(bitmap, l, t, null);
            }
        }
        
        public void setLevel(int newLevel) {
            level = Math.max(1, Math.min(newLevel, maxLevel));
            invalidate();
        }
        
        private float dpToPx(float dp) {
            return dp * getResources().getDisplayMetrics().density;
        }
    }
}
