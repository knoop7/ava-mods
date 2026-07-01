package com.ava.mods.hasttengine;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ModelDownloader {
    private static final String TAG = "HaSttModelDl";

    private static final String MODEL_URL =
            "https://www.modelscope.cn/models/xiaowangge/sherpa-onnx-sense-voice-small/resolve/master/model_q8.onnx";
    private static final String TOKENS_URL =
            "https://www.modelscope.cn/models/xiaowangge/sherpa-onnx-sense-voice-small/resolve/master/tokens.txt";

    interface ProgressListener {
        void onStatus(String status);

        void onProgress(int percent);

        void onFinished(boolean success, String message);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private volatile ProgressListener listener;
    private volatile HttpURLConnection activeConnection;

    ModelDownloader(Context context) {
        this.context = context.getApplicationContext();
    }

    void setListener(ProgressListener listener) {
        this.listener = listener;
    }

    boolean isRunning() {
        return running.get();
    }

    void cancelDownload() {
        cancelRequested.set(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    boolean downloadAsync() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "Download already in progress");
            return false;
        }
        cancelRequested.set(false);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    downloadBlocking();
                } finally {
                    running.set(false);
                    activeConnection = null;
                }
            }
        });
        return true;
    }

    private void downloadBlocking() {
        notifyStatus("downloading");
        try {
            File modelTemp = new File(ModelStore.rootDir(context), "model.int8.onnx.part");
            File tokensTemp = new File(ModelStore.rootDir(context), "tokens.txt.part");
            deleteQuietly(modelTemp);
            deleteQuietly(tokensTemp);

            notifyProgress(0);
            downloadFile(MODEL_URL, modelTemp, 0, 95);
            if (cancelRequested.get()) {
                throw new DownloadCancelledException("Download paused");
            }
            downloadFile(TOKENS_URL, tokensTemp, 95, 99);
            if (cancelRequested.get()) {
                throw new DownloadCancelledException("Download paused");
            }

            if (!modelTemp.renameTo(ModelStore.modelFile(context))) {
                throw new IllegalStateException("Failed to finalize model file");
            }
            if (!tokensTemp.renameTo(ModelStore.tokensFile(context))) {
                throw new IllegalStateException("Failed to finalize tokens file");
            }
            if (!ModelStore.markerFile(context).createNewFile() && !ModelStore.markerFile(context).exists()) {
                throw new IllegalStateException("Failed to write download marker");
            }

            notifyProgress(100);
            notifyStatus("ready");
            notifyFinished(true, "Model downloaded");
            Log.i(TAG, "Model ready at " + ModelStore.displayPath(context));
        } catch (DownloadCancelledException e) {
            Log.i(TAG, "Model download paused");
            notifyStatus("paused");
            notifyFinished(false, "Download paused");
        } catch (Exception e) {
            if (cancelRequested.get()) {
                Log.i(TAG, "Model download paused");
                notifyStatus("paused");
                notifyFinished(false, "Download paused");
                return;
            }
            Log.e(TAG, "Model download failed", e);
            ModelStore.clear(context);
            notifyStatus("error");
            notifyFinished(false, e.getMessage() == null ? "Download failed" : e.getMessage());
        }
    }

    private void downloadFile(String urlString, File destination, int progressStart, int progressEnd)
            throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            activeConnection = connection;
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(120000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Ava-HaSttEngine/1.0");
            connection.connect();

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " for " + urlString);
            }

            long total = connection.getContentLengthLong();
            long downloaded = 0L;
            byte[] buffer = new byte[8192];

            InputStream input = new BufferedInputStream(connection.getInputStream());
            FileOutputStream output = new FileOutputStream(destination);
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (cancelRequested.get()) {
                        throw new DownloadCancelledException("Download paused");
                    }
                    output.write(buffer, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        int filePercent = (int) Math.min(100, (downloaded * 100L) / total);
                        int mapped = progressStart + ((progressEnd - progressStart) * filePercent) / 100;
                        notifyProgress(mapped);
                    } else if (downloaded % (512 * 1024) < buffer.length) {
                        int pulse = progressStart + (int) ((downloaded % 5_000_000L) * (progressEnd - progressStart) / 5_000_000L);
                        notifyProgress(Math.max(progressStart + 1, pulse));
                    }
                }
                output.getFD().sync();
            } finally {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }

            if (destination.length() <= 0L) {
                throw new IllegalStateException("Downloaded file is empty: " + destination.getName());
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (activeConnection == connection) {
                activeConnection = null;
            }
        }
    }

    private void notifyStatus(String status) {
        ProgressListener current = listener;
        if (current != null) {
            current.onStatus(status);
        }
    }

    private void notifyProgress(int percent) {
        ProgressListener current = listener;
        if (current != null) {
            current.onProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private void notifyFinished(boolean success, String message) {
        ProgressListener current = listener;
        if (current != null) {
            current.onFinished(success, message);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private static final class DownloadCancelledException extends Exception {
        DownloadCancelledException(String message) {
            super(message);
        }
    }
}
