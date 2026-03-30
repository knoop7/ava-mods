package com.ava.mods.mimiclaw.task;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class TaskTree {
    private static final String TAG = "TaskTree";
    private static final String TASKS_FILE = "tasktree.json";
    
    private final File tasksFile;
    private JSONObject root;
    
    public TaskTree(Context context) {
        File dataDir = new File(context.getFilesDir(), "tasktree");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        this.tasksFile = new File(dataDir, TASKS_FILE);
        load();
    }
    
    private void load() {
        if (!tasksFile.exists()) {
            root = new JSONObject();
            try {
                root.put("todos", new JSONArray());
            } catch (Exception e) {
                Log.e(TAG, "Failed to init root", e);
            }
            return;
        }
        try (FileReader reader = new FileReader(tasksFile)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int len;
            while ((len = reader.read(buf)) > 0) {
                sb.append(buf, 0, len);
            }
            root = new JSONObject(sb.toString());
            if (!root.has("todos")) {
                root.put("todos", new JSONArray());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load tasks", e);
            root = new JSONObject();
            try {
                root.put("todos", new JSONArray());
            } catch (Exception ex) {
                Log.e(TAG, "Failed to init root", ex);
            }
        }
    }
    
    private void save() {
        try (FileWriter writer = new FileWriter(tasksFile)) {
            writer.write(root.toString(2));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save tasks", e);
        }
    }
    
    public String createTasks(JSONArray todos) {
        try {
            JSONArray normalized = new JSONArray();
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                JSONObject normalizedTask = new JSONObject();
                normalizedTask.put("id", task.optString("id", "task_" + System.currentTimeMillis() + "_" + i));
                normalizedTask.put("content", task.optString("content", ""));
                normalizedTask.put("activeForm", task.optString("activeForm", task.optString("content", "")));
                normalizedTask.put("status", "pending");
                normalizedTask.put("priority", task.optString("priority", "medium"));
                normalizedTask.put("createdAt", System.currentTimeMillis());
                normalized.put(normalizedTask);
            }
            root.put("todos", normalized);
            save();
            return "Created " + normalized.length() + " tasks";
        } catch (Exception e) {
            Log.e(TAG, "Failed to create tasks", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String createTasksFromJson(String jsonStr) {
        try {
            JSONObject input = new JSONObject(jsonStr);
            JSONArray todos = input.optJSONArray("todos");
            if (todos == null || todos.length() == 0) {
                return "Error: No tasks provided";
            }
            return createTasks(todos);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse tasks JSON", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public JSONObject getNextTask() {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null) return null;
            
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                if ("pending".equals(task.optString("status"))) {
                    return task;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get next task", e);
        }
        return null;
    }
    
    public JSONObject getCurrentTask() {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null) return null;
            
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                if ("in_progress".equals(task.optString("status"))) {
                    return task;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get current task", e);
        }
        return null;
    }
    
    public String startTask(String taskId) {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null) return "Error: No tasks found";
            
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                if (taskId.equals(task.optString("id"))) {
                    if (!"pending".equals(task.optString("status"))) {
                        return "Error: Task is not pending";
                    }
                    task.put("status", "in_progress");
                    task.put("startedAt", System.currentTimeMillis());
                    save();
                    return "Started task: " + task.optString("content");
                }
            }
            return "Error: Task not found";
        } catch (Exception e) {
            Log.e(TAG, "Failed to start task", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String completeTask(String taskId) {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null) return "Error: No tasks found";
            
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                if (taskId.equals(task.optString("id"))) {
                    String currentStatus = task.optString("status");
                    if (!"in_progress".equals(currentStatus) && !"pending".equals(currentStatus)) {
                        return "Error: Task cannot be completed (status: " + currentStatus + ")";
                    }
                    task.put("status", "completed");
                    task.put("completedAt", System.currentTimeMillis());
                    save();
                    
                    JSONObject nextTask = getNextTask();
                    if (nextTask != null) {
                        return "Completed: " + task.optString("content") + "\nNext task: " + nextTask.optString("content");
                    }
                    return "Completed: " + task.optString("content") + "\nAll tasks done!";
                }
            }
            return "Error: Task not found";
        } catch (Exception e) {
            Log.e(TAG, "Failed to complete task", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String failTask(String taskId, String reason) {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null) return "Error: No tasks found";
            
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                if (taskId.equals(task.optString("id"))) {
                    task.put("status", "failed");
                    task.put("failedAt", System.currentTimeMillis());
                    task.put("failReason", reason != null ? reason : "Unknown");
                    save();
                    return "Failed: " + task.optString("content") + " - " + reason;
                }
            }
            return "Error: Task not found";
        } catch (Exception e) {
            Log.e(TAG, "Failed to mark task as failed", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String listTasks() {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null || todos.length() == 0) {
                return "[TASK_TREE_EMPTY]";
            }
            
            JSONObject result = new JSONObject();
            result.put("type", "task_tree");
            result.put("total", todos.length());
            result.put("pending", countByStatus("pending"));
            result.put("in_progress", countByStatus("in_progress"));
            result.put("completed", countByStatus("completed"));
            result.put("failed", countByStatus("failed"));
            result.put("todos", todos);
            
            return "[TASK_TREE:" + result.toString() + "]";
        } catch (Exception e) {
            Log.e(TAG, "Failed to list tasks", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String getStatusSummary() {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null || todos.length() == 0) {
                return "No tasks";
            }
            
            JSONObject summary = new JSONObject();
            summary.put("total", todos.length());
            summary.put("pending", countByStatus("pending"));
            summary.put("in_progress", countByStatus("in_progress"));
            summary.put("completed", countByStatus("completed"));
            summary.put("failed", countByStatus("failed"));
            
            JSONObject current = getCurrentTask();
            if (current != null) {
                summary.put("currentTask", current.optString("content"));
                summary.put("currentTaskId", current.optString("id"));
            }
            
            JSONObject next = getNextTask();
            if (next != null) {
                summary.put("nextTask", next.optString("content"));
                summary.put("nextTaskId", next.optString("id"));
            }
            
            return summary.toString(2);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get status summary", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String clearTasks() {
        try {
            root.put("todos", new JSONArray());
            save();
            return "All tasks cleared";
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear tasks", e);
            return "Error: " + e.getMessage();
        }
    }
    
    public String autoProgress() {
        try {
            JSONObject current = getCurrentTask();
            if (current != null) {
                return "Current task in progress: " + current.optString("content") + 
                       "\nUse task_complete to finish it first.";
            }
            
            JSONObject next = getNextTask();
            if (next == null) {
                return "No pending tasks. All done!";
            }
            
            String result = startTask(next.optString("id"));
            if (result.startsWith("Started")) {
                return "Auto-started task: " + next.optString("content") + 
                       "\nExecute the task and call task_complete when done.";
            }
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Failed to auto progress", e);
            return "Error: " + e.getMessage();
        }
    }
    
    private int countByStatus(String status) {
        try {
            JSONArray todos = root.optJSONArray("todos");
            if (todos == null) return 0;
            
            int count = 0;
            for (int i = 0; i < todos.length(); i++) {
                JSONObject task = todos.getJSONObject(i);
                if (status.equals(task.optString("status"))) {
                    count++;
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private String getStatusIcon(String status) {
        switch (status) {
            case "pending": return "⏳";
            case "in_progress": return "🔄";
            case "completed": return "✅";
            case "failed": return "❌";
            default: return "❓";
        }
    }
    
    public JSONArray getTasksJson() {
        return root.optJSONArray("todos");
    }
}
