package com.ava.mods.mimiclaw.llm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class LlmProxy {
    private static final String TAG = "LlmProxy";
    private static final String DEFAULT_ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    
    private String apiKey = "";
    private String model = "claude-opus-4-5";
    private String provider = "anthropic";
    private int maxTokens = 4096;
    private String customApiUrl = "";
    private String customApiHost = "";
    private boolean useCustomApi = false;
    private long totalInputTokens = 0L;
    private long totalOutputTokens = 0L;
    private long totalTokens = 0L;
    
    public static class ToolCall {
        public String id;
        public String name;
        public String input;
        
        public ToolCall(String id, String name, String input) {
            this.id = id;
            this.name = name;
            this.input = input;
        }
    }
    
    public static class Response {
        public String text;
        public boolean toolUse;
        public List<ToolCall> calls = new ArrayList<>();
        public int inputTokens;
        public int outputTokens;
        public int totalTokens;
    }
    
    public void setApiKey(String key) {
        this.apiKey = sanitizeSingleLine(key);
    }
    
    public void setModel(String model) {
        String sanitized = sanitizeSingleLine(model);
        if (!sanitized.isEmpty()) {
            this.model = sanitized;
        }
    }
    
    public void setProvider(String provider) {
        this.provider = normalizeProvider(sanitizeSingleLine(provider));
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    public String getApiUrlForDebug() {
        return resolveApiUrl();
    }
    
    public String getCustomApiUrl() {
        return customApiUrl;
    }

    public boolean isUsingCustomApi() {
        return useCustomApi;
    }
    
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }
    
    public void setCustomApiUrl(String url) {
        String sanitized = sanitizeSingleLine(url);
        if (!sanitized.isEmpty()) {
            this.customApiUrl = sanitized;
            this.useCustomApi = true;
            try {
                java.net.URL parsed = new java.net.URL(this.customApiUrl);
                this.customApiHost = parsed.getHost();
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse custom API URL: " + this.customApiUrl);
                this.customApiHost = "";
            }
        } else {
            this.customApiUrl = "";
            this.customApiHost = "";
            this.useCustomApi = false;
        }
    }
    
    public Response chatWithTools(String systemPrompt, JSONArray messages, JSONArray tools) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("API key not set");
        }
        
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        
        if (isOpenAiProtocol()) {
            requestBody.put("max_completion_tokens", maxTokens);
            JSONArray openaiMessages = convertMessagesOpenAI(systemPrompt, messages);
            requestBody.put("messages", openaiMessages);
            
            if (tools != null && tools.length() > 0) {
                JSONArray openaiTools = convertToolsOpenAI(tools);
                requestBody.put("tools", openaiTools);
                requestBody.put("tool_choice", "auto");
            }
        } else {
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("system", systemPrompt);
            requestBody.put("messages", messages);
            
            if (tools != null && tools.length() > 0) {
                requestBody.put("tools", tools);
            }
        }
        
        String responseBody = makeHttpRequest(requestBody.toString());
        Response response = parseResponse(responseBody);
        synchronized (this) {
            totalInputTokens += Math.max(0, response.inputTokens);
            totalOutputTokens += Math.max(0, response.outputTokens);
            totalTokens += Math.max(0, response.totalTokens);
        }
        return response;
    }

    public synchronized long getTotalInputTokens() {
        return totalInputTokens;
    }

    public synchronized long getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public synchronized long getTotalTokens() {
        return totalTokens;
    }
    
    private String makeHttpRequest(String requestBody) throws Exception {
        String apiUrl = resolveApiUrl();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(90000);
            
            if (isOpenAiProtocol()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            } else {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
            }

            Log.d(TAG, "LLM request start provider=" + provider + ", model=" + model + ", url=" + apiUrl + ", maxTokens=" + maxTokens);
            
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody.getBytes("UTF-8"));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                errorReader.close();
                throw new Exception("API error " + responseCode + ": " + errorResponse.toString());
            }
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            Log.d(TAG, "LLM request success, bytes=" + response.length());
            return response.toString();
        } catch (SocketTimeoutException e) {
            Log.e(TAG, "LLM request timed out provider=" + provider + ", model=" + model + ", url=" + apiUrl, e);
            throw new Exception("LLM request timed out. provider=" + provider + ", model=" + model, e);
        } catch (Exception e) {
            Log.e(TAG, "LLM request failed provider=" + provider + ", model=" + model + ", url=" + apiUrl, e);
            throw e;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String resolveApiUrl() {
        if (!useCustomApi || customApiUrl.isEmpty()) {
            return isOpenAiProtocol() ? DEFAULT_OPENAI_URL : DEFAULT_ANTHROPIC_URL;
        }

        String normalized = customApiUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        if (normalized.endsWith("/chat/completions") || normalized.endsWith("/messages")) {
            return normalized;
        }

        if (normalized.endsWith("/v1")) {
            return appendProviderEndpoint(normalized);
        }

        if (normalized.contains("/v1/")) {
            return normalized;
        }

        return appendProviderEndpoint(normalized + "/v1");
    }

    private String appendProviderEndpoint(String baseUrl) {
        if (isOpenAiProtocol()) {
            return baseUrl + "/chat/completions";
        }
        return baseUrl + "/messages";
    }
    
    private Response parseResponse(String responseBody) throws Exception {
        Response resp = new Response();
        JSONObject root = new JSONObject(responseBody);
        
        if (isOpenAiProtocol()) {
            JSONArray choices = root.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject choice = choices.getJSONObject(0);
                String finishReason = choice.optString("finish_reason");
                resp.toolUse = "tool_calls".equals(finishReason);
                
                JSONObject message = choice.optJSONObject("message");
                if (message != null) {
                    resp.text = message.optString("content", "");
                    
                    JSONArray toolCalls = message.optJSONArray("tool_calls");
                    if (toolCalls != null) {
                        for (int i = 0; i < toolCalls.length(); i++) {
                            JSONObject tc = toolCalls.getJSONObject(i);
                            String id = tc.optString("id");
                            JSONObject func = tc.optJSONObject("function");
                            if (func != null) {
                                String name = func.optString("name");
                                String args = func.optString("arguments", "{}");
                                resp.calls.add(new ToolCall(id, name, args));
                            }
                        }
                        if (!resp.calls.isEmpty()) {
                            resp.toolUse = true;
                        }
                    }
                }
            }
        } else {
            String stopReason = root.optString("stop_reason");
            resp.toolUse = "tool_use".equals(stopReason);
            
            JSONArray content = root.optJSONArray("content");
            if (content != null) {
                StringBuilder textBuilder = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.getJSONObject(i);
                    String type = block.optString("type");
                    
                    if ("text".equals(type)) {
                        textBuilder.append(block.optString("text", ""));
                    } else if ("tool_use".equals(type)) {
                        String id = block.optString("id");
                        String name = block.optString("name");
                        JSONObject input = block.optJSONObject("input");
                        String inputStr = input != null ? input.toString() : "{}";
                        resp.calls.add(new ToolCall(id, name, inputStr));
                    }
                }
                resp.text = textBuilder.toString();
            }
        }

        parseUsage(root, resp);
        
        return resp;
    }

    private void parseUsage(JSONObject root, Response resp) {
        JSONObject usage = root.optJSONObject("usage");
        if (usage == null) {
            return;
        }

        if (isOpenAiProtocol()) {
            resp.inputTokens = firstPositive(
                usage.optInt("prompt_tokens", 0),
                usage.optInt("input_tokens", 0)
            );
            resp.outputTokens = firstPositive(
                usage.optInt("completion_tokens", 0),
                usage.optInt("output_tokens", 0)
            );
            resp.totalTokens = firstPositive(
                usage.optInt("total_tokens", 0),
                resp.inputTokens + resp.outputTokens
            );
            return;
        }

        resp.inputTokens = firstPositive(
            usage.optInt("input_tokens", 0),
            usage.optInt("prompt_tokens", 0)
        );
        resp.outputTokens = firstPositive(
            usage.optInt("output_tokens", 0),
            usage.optInt("completion_tokens", 0)
        );
        resp.totalTokens = firstPositive(
            usage.optInt("total_tokens", 0),
            resp.inputTokens + resp.outputTokens
        );
    }

    private int firstPositive(int... values) {
        for (int value : values) {
            if (value > 0) {
                return value;
            }
        }
        return 0;
    }

    private boolean isOpenAiProtocol() {
        return "openai".equals(provider);
    }

    private String normalizeProvider(String value) {
        if (value == null) {
            return "anthropic";
        }

        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return "anthropic";
        }

        if ("openai".equals(normalized) || "oai".equals(normalized) || "gpt".equals(normalized)) {
            return "openai";
        }

        if ("anthropic".equals(normalized) || "claude".equals(normalized) || "a".equals(normalized)) {
            return "anthropic";
        }

        return normalized;
    }

    private String sanitizeSingleLine(String value) {
        if (value == null) {
            return "";
        }

        String[] lines = value.replace('\r', '\n').split("\n");
        for (String line : lines) {
            String trimmed = line != null ? line.trim() : "";
            if (trimmed.startsWith("Use ")) {
                continue;
            }
            if (trimmed.startsWith("+")) {
                continue;
            }
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }
    
    private JSONArray convertMessagesOpenAI(String systemPrompt, JSONArray messages) throws Exception {
        JSONArray result = new JSONArray();
        
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            result.put(sys);
        }
        
        java.util.Set<String> validToolCallIds = new java.util.HashSet<>();
        
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.optString("role");
            Object content = msg.opt("content");
            
            if (content instanceof String) {
                String contentStr = (String) content;
                JSONObject m = new JSONObject();
                m.put("role", role);
                if ("user".equals(role) && contentStr.contains("<image_data>")) {
                    m.put("content", buildUserContentWithImages(contentStr));
                } else {
                    m.put("content", contentStr);
                }
                result.put(m);
                validToolCallIds.clear();
            } else if (content instanceof JSONArray) {
                JSONArray contentArray = (JSONArray) content;
                
                if ("assistant".equals(role)) {
                    JSONObject m = new JSONObject();
                    m.put("role", "assistant");
                    
                    StringBuilder textBuf = new StringBuilder();
                    JSONArray toolCalls = new JSONArray();
                    validToolCallIds.clear();
                    
                    for (int j = 0; j < contentArray.length(); j++) {
                        JSONObject block = contentArray.getJSONObject(j);
                        String type = block.optString("type");
                        
                        if ("text".equals(type)) {
                            textBuf.append(block.optString("text", ""));
                        } else if ("tool_use".equals(type)) {
                            String toolId = block.optString("id");
                            validToolCallIds.add(toolId);
                            
                            JSONObject tc = new JSONObject();
                            tc.put("id", toolId);
                            tc.put("type", "function");
                            
                            JSONObject func = new JSONObject();
                            func.put("name", block.optString("name"));
                            JSONObject input = block.optJSONObject("input");
                            func.put("arguments", input != null ? input.toString() : "{}");
                            tc.put("function", func);
                            
                            toolCalls.put(tc);
                        }
                    }
                    
                    m.put("content", textBuf.toString());
                    if (toolCalls.length() > 0) {
                        m.put("tool_calls", toolCalls);
                    }
                    result.put(m);
                    
                } else if ("user".equals(role)) {
                    for (int j = 0; j < contentArray.length(); j++) {
                        JSONObject block = contentArray.getJSONObject(j);
                        String type = block.optString("type");
                        
                        if ("tool_result".equals(type)) {
                            String toolUseId = block.optString("tool_use_id");
                            if (!validToolCallIds.contains(toolUseId)) {
                                continue;
                            }
                            JSONObject tm = new JSONObject();
                            tm.put("role", "tool");
                            tm.put("tool_call_id", toolUseId);
                            String imagePath = block.optString("image_path", "");
                            if (!imagePath.isEmpty()) {
                                tm.put("content", buildOpenAiToolContent(block.optString("content", ""), imagePath));
                            } else {
                                tm.put("content", block.optString("content", ""));
                            }
                            result.put(tm);
                        }
                    }
                }
            }
        }
        
        return result;
    }

    private JSONArray buildOpenAiToolContent(String textContent, String imagePath) throws Exception {
        JSONArray content = new JSONArray();

        JSONObject textBlock = new JSONObject();
        textBlock.put("type", "text");
        textBlock.put("text", textContent != null && !textContent.isEmpty()
            ? textContent
            : "Image tool result attached.");
        content.put(textBlock);

        String dataUrl = toImageDataUrl(imagePath);
        if (dataUrl != null && !dataUrl.isEmpty()) {
            JSONObject imageBlock = new JSONObject();
            imageBlock.put("type", "image_url");
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", dataUrl);
            imageBlock.put("image_url", imageUrl);
            content.put(imageBlock);
        }

        return content;
    }

    private static final int MAX_IMAGE_SIZE = 200 * 1024; // 200KB max for AI
    private static final int MAX_IMAGE_DIM = 1280; // Max dimension
    
    private String toImageDataUrl(String imagePath) {
        try {
            File file = new File(imagePath);
            if (!file.isFile()) {
                return null;
            }

            Bitmap original = BitmapFactory.decodeFile(imagePath);
            if (original == null) {
                return null;
            }
            
            // Scale down if too large
            Bitmap bitmap = original;
            int w = original.getWidth();
            int h = original.getHeight();
            if (w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM) {
                float scale = Math.min((float) MAX_IMAGE_DIM / w, (float) MAX_IMAGE_DIM / h);
                int newW = (int) (w * scale);
                int newH = (int) (h * scale);
                bitmap = Bitmap.createScaledBitmap(original, newW, newH, true);
                original.recycle();
            }
            
            // Compress to JPEG
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int quality = 60;
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            
            // Reduce quality if still too large
            while (out.size() > MAX_IMAGE_SIZE && quality > 10) {
                out.reset();
                quality -= 10;
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            }
            
            bitmap.recycle();
            
            String base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            Log.d(TAG, "Image compressed: " + imagePath + " -> " + out.size() / 1024 + "KB, quality=" + quality);
            return "data:image/jpeg;base64," + base64;
        } catch (Exception e) {
            Log.w(TAG, "Failed to convert image to data URL: " + imagePath, e);
            return null;
        }
    }

    private String inferImageMimeType(String fileName) {
        String lower = fileName != null ? fileName.toLowerCase() : "";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private JSONArray buildUserContentWithImages(String text) throws Exception {
        JSONArray content = new JSONArray();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("<image_data>([^<]+)</image_data>");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            String before = text.substring(lastEnd, matcher.start()).trim();
            if (!before.isEmpty()) {
                JSONObject textBlock = new JSONObject();
                textBlock.put("type", "text");
                textBlock.put("text", before);
                content.put(textBlock);
            }
            String dataUrl = matcher.group(1).trim();
            if (!dataUrl.startsWith("data:")) {
                dataUrl = "data:" + dataUrl;
            }
            Log.d(TAG, "Image data URL prefix: " + dataUrl.substring(0, Math.min(80, dataUrl.length())));
            JSONObject imageBlock = new JSONObject();
            imageBlock.put("type", "image_url");
            JSONObject imageUrl = new JSONObject();
            imageUrl.put("url", dataUrl);
            imageUrl.put("detail", "auto");
            imageBlock.put("image_url", imageUrl);
            content.put(imageBlock);
            lastEnd = matcher.end();
        }
        String after = text.substring(lastEnd).trim();
        if (!after.isEmpty()) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", after);
            content.put(textBlock);
        }
        if (content.length() == 0) {
            JSONObject textBlock = new JSONObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.put(textBlock);
        }
        return content;
    }
    
    private JSONArray convertToolsOpenAI(JSONArray tools) throws Exception {
        JSONArray result = new JSONArray();
        
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            String name = tool.optString("name");
            String desc = tool.optString("description");
            JSONObject schema = tool.optJSONObject("input_schema");
            
            JSONObject func = new JSONObject();
            func.put("name", name);
            if (desc != null && !desc.isEmpty()) {
                func.put("description", desc);
            }
            if (schema != null) {
                func.put("parameters", schema);
            }
            
            JSONObject wrap = new JSONObject();
            wrap.put("type", "function");
            wrap.put("function", func);
            
            result.put(wrap);
        }
        
        return result;
    }
}
