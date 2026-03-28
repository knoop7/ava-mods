package com.ava.mods.mimiclaw.cron;

import android.content.Context;
import android.util.Log;
import com.ava.mods.mimiclaw.bus.MessageBus;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CronService {
    private static final String TAG = "CronService";
    private static final String CRON_FILE = "cron.json";
    private static final int MAX_JOBS = 16;
    private static final long CHECK_INTERVAL_MS = 60000;
    public static final String BUILTIN_HEARTBEAT_NAME = "__builtin_heartbeat__";
    
    public static final int KIND_EVERY = 1;
    public static final int KIND_AT = 2;
    
    public static class CronJob {
        public String id;
        public String name;
        public String title;       // Short title (max 15 chars)
        public String description; // Short description (max 15 chars)
        public int kind;
        public long intervalS;
        public long atEpoch;
        public String message;
        public String channel;
        public String chatId;
        public boolean enabled;
        public boolean deleteAfterRun;
        public long lastRun;
        public long nextRun;
    }
    
    private final Context context;
    private final MessageBus messageBus;
    private final List<CronJob> jobs = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private final Random random = new Random();
    
    public CronService(Context context) {
        this.context = context;
        this.messageBus = MessageBus.getInstance();
        loadJobs();
    }
    
    public void start() {
        if (scheduler != null) {
            return;
        }
        
        long now = System.currentTimeMillis() / 1000;
        for (CronJob job : jobs) {
            if (job.enabled && job.nextRun <= 0) {
                if (job.kind == KIND_EVERY) {
                    job.nextRun = now + job.intervalS;
                } else if (job.kind == KIND_AT && job.atEpoch > now) {
                    job.nextRun = job.atEpoch;
                }
            }
        }
        
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::processDueJobs,
            0, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        Log.d(TAG, "Cron service started with " + jobs.size() + " jobs");
    }
    
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
            Log.d(TAG, "Cron service stopped");
        }
    }
    
    public String addJob(CronJob job) {
        return addJobInternal(job, false);
    }
    
    public String addJobImmediate(CronJob job) {
        return addJobInternal(job, true);
    }
    
    private String addJobInternal(CronJob job, boolean runImmediately) {
        if (jobs.size() >= MAX_JOBS) {
            return null;
        }
        
        job.id = generateId();
        job.enabled = true;
        job.lastRun = 0;
        
        long now = System.currentTimeMillis() / 1000;
        if (job.kind == KIND_EVERY) {
            // Run immediately on cold start, or wait for interval
            job.nextRun = runImmediately ? now : (now + job.intervalS);
        } else if (job.kind == KIND_AT) {
            if (job.atEpoch > now) {
                job.nextRun = job.atEpoch;
            } else {
                job.nextRun = 0;
                job.enabled = false;
            }
        }
        
        if (job.channel == null || job.channel.isEmpty()) {
            job.channel = "system";
        }
        if (job.chatId == null || job.chatId.isEmpty()) {
            job.chatId = "cron";
        }
        
        jobs.add(job);
        saveJobs();
        
        Log.d(TAG, "Added cron job: " + job.name + " (" + job.id + ")" + (runImmediately ? " [immediate]" : ""));
        return job.id;
    }
    
    public boolean removeJob(String jobId) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).id.equals(jobId)) {
                jobs.remove(i);
                saveJobs();
                Log.d(TAG, "Removed cron job: " + jobId);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean setJobEnabled(String jobId, boolean enabled) {
        for (CronJob job : jobs) {
            if (!safeEquals(job.id, jobId)) {
                continue;
            }
            job.enabled = enabled;
            long now = System.currentTimeMillis() / 1000;
            if (enabled) {
                if (job.kind == KIND_EVERY) {
                    job.nextRun = now + Math.max(1, job.intervalS);
                } else if (job.kind == KIND_AT && job.atEpoch > now) {
                    job.nextRun = job.atEpoch;
                } else {
                    job.nextRun = 0;
                    job.enabled = false;
                }
            } else {
                job.nextRun = 0;
            }
            saveJobs();
            return true;
        }
        return false;
    }
    
    public List<CronJob> listJobs() {
        return new ArrayList<>(jobs);
    }

    public synchronized void ensureBuiltinHeartbeatJob(String channel, String chatId, String message, long intervalS) {
        CronJob existing = findJobByName(BUILTIN_HEARTBEAT_NAME);
        String defaultTitle = "Heartbeat";
        String defaultDesc = "Every " + (intervalS >= 60 ? (intervalS / 60) + "m" : intervalS + "s");
        if (existing == null) {
            CronJob job = new CronJob();
            job.name = BUILTIN_HEARTBEAT_NAME;
            job.title = defaultTitle;
            job.description = defaultDesc;
            job.kind = KIND_EVERY;
            job.intervalS = intervalS;
            job.message = message;
            job.channel = channel;
            job.chatId = chatId;
            job.deleteAfterRun = false;
            addJobImmediate(job); // Run immediately on cold start
            return;
        }
        // Update title/description if empty
        boolean changed = false;
        if (existing.title == null || existing.title.isEmpty()) {
            existing.title = defaultTitle;
            changed = true;
        }
        if (existing.description == null || existing.description.isEmpty()) {
            existing.description = defaultDesc;
            changed = true;
        }
        if (existing.kind != KIND_EVERY) {
            existing.kind = KIND_EVERY;
            changed = true;
        }
        if (existing.intervalS != intervalS) {
            existing.intervalS = intervalS;
            changed = true;
        }
        if (!safeEquals(existing.message, message)) {
            existing.message = message;
            changed = true;
        }
        if (!safeEquals(existing.channel, channel)) {
            existing.channel = channel;
            changed = true;
        }
        if (!safeEquals(existing.chatId, chatId)) {
            existing.chatId = chatId;
            changed = true;
        }
        // On cold start, always run immediately if enabled
        long now = System.currentTimeMillis() / 1000;
        if (existing.enabled && (existing.nextRun <= now || changed)) {
            existing.nextRun = now; // Run immediately
            saveJobs();
        } else if (changed) {
            saveJobs();
        }
    }
    
    private void processDueJobs() {
        long now = System.currentTimeMillis() / 1000;
        boolean changed = false;
        
        for (int i = 0; i < jobs.size(); i++) {
            CronJob job = jobs.get(i);
            if (!job.enabled) continue;
            if (job.nextRun <= 0) continue;
            if (job.nextRun > now) continue;
            
            Log.d(TAG, "Cron job firing: " + job.name + " (" + job.id + ")");
            
            MessageBus.Message msg = new MessageBus.Message(
                job.channel, job.chatId, job.message
            );
            messageBus.pushInbound(msg);
            
            job.lastRun = now;
            
            if (job.kind == KIND_AT) {
                if (job.deleteAfterRun) {
                    jobs.remove(i);
                    i--;
                } else {
                    job.enabled = false;
                    job.nextRun = 0;
                }
            } else {
                job.nextRun = now + job.intervalS;
            }
            
            changed = true;
        }
        
        if (changed) {
            saveJobs();
        }
    }
    
    private String generateId() {
        return String.format("%08x", random.nextInt());
    }

    private CronJob findJobByName(String name) {
        for (CronJob job : jobs) {
            if (safeEquals(job.name, name)) {
                return job;
            }
        }
        return null;
    }

    private boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
    
    private void loadJobs() {
        File file = new File(context.getFilesDir(), CRON_FILE);
        if (!file.exists()) {
            return;
        }
        
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            
            JSONObject root = new JSONObject(sb.toString());
            JSONArray jobsArr = root.optJSONArray("jobs");
            if (jobsArr == null) return;
            
            for (int i = 0; i < jobsArr.length(); i++) {
                JSONObject obj = jobsArr.getJSONObject(i);
                CronJob job = new CronJob();
                job.id = obj.optString("id");
                job.name = obj.optString("name");
                job.title = obj.optString("title", "");
                job.description = obj.optString("description", "");
                job.message = obj.optString("message");
                job.channel = obj.optString("channel", "system");
                job.chatId = obj.optString("chat_id", "cron");
                job.enabled = obj.optBoolean("enabled", true);
                job.deleteAfterRun = obj.optBoolean("delete_after_run", false);
                job.lastRun = obj.optLong("last_run", 0);
                job.nextRun = obj.optLong("next_run", 0);
                
                String kindStr = obj.optString("kind");
                if ("every".equals(kindStr)) {
                    job.kind = KIND_EVERY;
                    job.intervalS = obj.optLong("interval_s", 0);
                } else if ("at".equals(kindStr)) {
                    job.kind = KIND_AT;
                    job.atEpoch = obj.optLong("at_epoch", 0);
                }
                
                jobs.add(job);
            }
            
            Log.d(TAG, "Loaded " + jobs.size() + " cron jobs");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cron jobs", e);
        }
    }
    
    private void saveJobs() {
        try {
            JSONObject root = new JSONObject();
            JSONArray jobsArr = new JSONArray();
            
            for (CronJob job : jobs) {
                JSONObject obj = new JSONObject();
                obj.put("id", job.id);
                obj.put("name", job.name);
                obj.put("title", job.title != null ? job.title : "");
                obj.put("description", job.description != null ? job.description : "");
                obj.put("enabled", job.enabled);
                obj.put("kind", job.kind == KIND_EVERY ? "every" : "at");
                
                if (job.kind == KIND_EVERY) {
                    obj.put("interval_s", job.intervalS);
                } else {
                    obj.put("at_epoch", job.atEpoch);
                }
                
                obj.put("message", job.message);
                obj.put("channel", job.channel);
                obj.put("chat_id", job.chatId);
                obj.put("last_run", job.lastRun);
                obj.put("next_run", job.nextRun);
                obj.put("delete_after_run", job.deleteAfterRun);
                
                jobsArr.put(obj);
            }
            
            root.put("jobs", jobsArr);
            
            File file = new File(context.getFilesDir(), CRON_FILE);
            FileWriter writer = new FileWriter(file);
            writer.write(root.toString(2));
            writer.close();
            
            Log.d(TAG, "Saved " + jobs.size() + " cron jobs");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to save cron jobs", e);
        }
    }
}
