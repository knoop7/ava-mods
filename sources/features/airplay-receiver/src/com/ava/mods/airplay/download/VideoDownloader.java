package com.ava.mods.airplay.download;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AirPlay video download via system {@link DownloadManager}.
 * <p>
 * System download notification shows title / progress / cancel. Overlay download
 * button stays in sync: spinner while active, second tap cancels (and removes
 * the DownloadManager job + notification).
 */
public final class VideoDownloader {

    private static final String TAG = "VideoDownloader";
    private static final long POLL_MS = 500L;

    public interface ProgressListener {
        /** {@code null} idle, {@code -1} indeterminate, else 0..100. */
        void onProgress(Integer progress);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DownloadManager downloadManager;

    private volatile long downloadId = -1L;
    private volatile Integer progressValue;
    private ProgressListener progressListener;
    private BroadcastReceiver completeReceiver;
    private String activeFileName;
    /** Ignore completion broadcast after local cancel. */
    private volatile boolean suppressComplete;

    private final Runnable pollProgress = new Runnable() {
        @Override
        public void run() {
            if (downloadId < 0L) return;
            queryAndPublishProgress();
            if (downloadId >= 0L) {
                mainHandler.postDelayed(this, POLL_MS);
            }
        }
    };

    public VideoDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.downloadManager = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    public Integer getProgress() {
        return progressValue;
    }

    public boolean isActive() {
        return downloadId >= 0L;
    }

    public void setProgressListener(ProgressListener listener) {
        this.progressListener = listener;
    }

    public void start(final String location) {
        start(location, null);
    }

    public void start(final String location, final String titleHint) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                startInternal(location, titleHint);
            }
        });
    }

    private void startInternal(String location, String titleHint) {
        if (downloadManager == null) {
            toast("下载不可用");
            return;
        }
        if (downloadId >= 0L) return;
        if (location == null || location.trim().isEmpty()) {
            toast("没有可下载的地址");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = guessFileName(location, stamp);
        activeFileName = fileName;
        suppressComplete = false;

        String title = (titleHint != null && !titleHint.trim().isEmpty())
                ? ("AirPlay · " + titleHint.trim())
                : "AirPlay 视频";

        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(location));
            request.setTitle(title);
            request.setDescription(fileName);
            request.setMimeType(guessMime(location, fileName));
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                request.setRequiresCharging(false);
                request.setRequiresDeviceIdle(false);
            }

            downloadId = downloadManager.enqueue(request);
            setProgress(-1);
            registerCompleteReceiver();
            mainHandler.removeCallbacks(pollProgress);
            mainHandler.postDelayed(pollProgress, POLL_MS);
            toast("已开始下载");
            Log.i(TAG, "enqueued id=" + downloadId + " file=" + fileName + " url=" + location);
        } catch (Throwable t) {
            Log.w(TAG, "enqueue failed", t);
            resetState();
            toast("无法开始下载");
        }
    }

    public void cancel() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                cancelInternal();
            }
        });
    }

    private void cancelInternal() {
        long id = downloadId;
        if (id < 0L) {
            if (progressValue != null) resetState();
            return;
        }
        suppressComplete = true;
        unregisterCompleteReceiver();
        mainHandler.removeCallbacks(pollProgress);
        try {
            downloadManager.remove(id);
        } catch (Throwable t) {
            Log.w(TAG, "remove failed", t);
        }
        downloadId = -1L;
        activeFileName = null;
        setProgress(null);
        toast("已取消下载");
    }

    private void registerCompleteReceiver() {
        unregisterCompleteReceiver();
        completeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || suppressComplete) return;
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id < 0L || id != downloadId) return;
                handleFinished(id);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(completeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(completeReceiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "register complete receiver failed", t);
        }
    }

    private void unregisterCompleteReceiver() {
        BroadcastReceiver r = completeReceiver;
        completeReceiver = null;
        if (r == null) return;
        try {
            context.unregisterReceiver(r);
        } catch (Throwable ignored) {}
    }

    private void handleFinished(long id) {
        if (downloadId != id) return;
        if (suppressComplete) {
            resetState();
            return;
        }
        int status = queryStatus(id);
        String name = activeFileName != null ? activeFileName : "视频";
        resetState();
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            toast("已保存到「下载」：" + name);
        } else {
            // Notification cancel / network failure both land here.
            toast("下载已结束");
            Log.i(TAG, "finished id=" + id + " status=" + status);
        }
    }

    private void queryAndPublishProgress() {
        long id = downloadId;
        if (id < 0L || downloadManager == null) return;
        Cursor c = null;
        try {
            c = downloadManager.query(new DownloadManager.Query().setFilterById(id));
            if (c == null || !c.moveToFirst()) {
                // Job removed from notification cancel — sync overlay.
                if (!suppressComplete) {
                    resetState();
                    toast("已取消下载");
                } else {
                    resetState();
                }
                return;
            }
            int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL
                    || status == DownloadManager.STATUS_FAILED) {
                // Prefer broadcast; if it was missed, finish here.
                handleFinished(id);
                return;
            }
            long soFar = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = c.getLong(c.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            if (total > 0L) {
                int pct = (int) Math.min(100L, Math.max(0L, (soFar * 100L) / total));
                setProgress(pct);
            } else {
                setProgress(-1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "query progress failed", t);
        } finally {
            if (c != null) c.close();
        }
    }

    private int queryStatus(long id) {
        Cursor c = null;
        try {
            c = downloadManager.query(new DownloadManager.Query().setFilterById(id));
            if (c != null && c.moveToFirst()) {
                return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private void resetState() {
        mainHandler.removeCallbacks(pollProgress);
        unregisterCompleteReceiver();
        downloadId = -1L;
        activeFileName = null;
        suppressComplete = false;
        setProgress(null);
    }

    private void setProgress(final Integer p) {
        progressValue = p;
        final ProgressListener l = progressListener;
        if (l != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    l.onProgress(p);
                }
            });
        }
    }

    private void toast(final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String guessFileName(String location, String stamp) {
        try {
            Uri uri = Uri.parse(location);
            String last = uri.getLastPathSegment();
            if (last != null) {
                String clean = last;
                int q = clean.indexOf('?');
                if (q >= 0) clean = clean.substring(0, q);
                int dot = clean.lastIndexOf('.');
                if (dot > 0 && dot < clean.length() - 1) {
                    String ext = clean.substring(dot);
                    if (ext.length() >= 2 && ext.length() <= 5) {
                        return "AirPlay_" + stamp + ext;
                    }
                }
            }
            String lower = location.toLowerCase(Locale.US);
            if (lower.contains(".m3u8")) return "AirPlay_" + stamp + ".m3u8";
            if (lower.contains(".ts")) return "AirPlay_" + stamp + ".ts";
            if (lower.contains(".mov")) return "AirPlay_" + stamp + ".mov";
            if (lower.contains(".mkv")) return "AirPlay_" + stamp + ".mkv";
            if (lower.contains(".webm")) return "AirPlay_" + stamp + ".webm";
        } catch (Throwable ignored) {}
        return "AirPlay_" + stamp + ".mp4";
    }

    private static String guessMime(String location, String fileName) {
        String s = (fileName + " " + location).toLowerCase(Locale.US);
        if (s.contains(".m3u8")) return "application/vnd.apple.mpegurl";
        if (s.contains(".ts")) return "video/mp2t";
        if (s.contains(".mov")) return "video/quicktime";
        if (s.contains(".mkv")) return "video/x-matroska";
        if (s.contains(".webm")) return "video/webm";
        return "video/mp4";
    }
}
