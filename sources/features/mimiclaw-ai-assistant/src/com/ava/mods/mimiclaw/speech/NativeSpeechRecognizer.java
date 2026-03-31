package com.ava.mods.mimiclaw.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class NativeSpeechRecognizer {
    private static final String TAG = "NativeSpeechRecognizer";
    
    private final Context context;
    private final Handler mainHandler;
    private SpeechRecognizer speechRecognizer;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private SpeechCallback callback;
    private StringBuilder fullTranscript = new StringBuilder();
    
    public interface SpeechCallback {
        void onPartialResult(String text);
        void onFinalResult(String text);
        void onError(String error);
        void onReady();
    }
    
    public NativeSpeechRecognizer(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public boolean isAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }
    
    public boolean isListening() {
        return isListening.get();
    }
    
    public void startListening(SpeechCallback callback) {
        this.callback = callback;
        
        if (!isAvailable()) {
            if (callback != null) {
                callback.onError("Speech recognition not available on this device");
            }
            return;
        }
        
        if (isListening.get()) {
            stopListening();
        }
        
        mainHandler.post(() -> {
            try {
                fullTranscript = new StringBuilder();
                
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                }
                
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        Log.d(TAG, "Ready for speech");
                        isListening.set(true);
                        if (callback != null) {
                            callback.onReady();
                        }
                    }
                    
                    @Override
                    public void onBeginningOfSpeech() {
                        Log.d(TAG, "Speech started");
                    }
                    
                    @Override
                    public void onRmsChanged(float rmsdB) {
                    }
                    
                    @Override
                    public void onBufferReceived(byte[] buffer) {
                    }
                    
                    @Override
                    public void onEndOfSpeech() {
                        Log.d(TAG, "Speech ended");
                    }
                    
                    @Override
                    public void onError(int error) {
                        isListening.set(false);
                        String errorMsg = getErrorMessage(error);
                        Log.e(TAG, "Speech error: " + errorMsg);
                        if (callback != null) {
                            callback.onError(errorMsg);
                        }
                    }
                    
                    @Override
                    public void onResults(Bundle results) {
                        isListening.set(false);
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String text = matches.get(0);
                            if (fullTranscript.length() > 0) {
                                fullTranscript.append(" ");
                            }
                            fullTranscript.append(text);
                            Log.d(TAG, "Final result: " + fullTranscript.toString());
                            if (callback != null) {
                                callback.onFinalResult(fullTranscript.toString());
                            }
                        } else {
                            if (callback != null) {
                                callback.onFinalResult(fullTranscript.toString());
                            }
                        }
                    }
                    
                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String partial = matches.get(0);
                            String combined = fullTranscript.length() > 0 
                                ? fullTranscript.toString() + " " + partial 
                                : partial;
                            Log.d(TAG, "Partial result: " + combined);
                            if (callback != null) {
                                callback.onPartialResult(combined);
                            }
                        }
                    }
                    
                    @Override
                    public void onEvent(int eventType, Bundle params) {
                    }
                });
                
                Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toString());
                intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                // Enable offline recognition if available
                intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
                
                speechRecognizer.startListening(intent);
                Log.d(TAG, "Started listening");
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to start speech recognition", e);
                isListening.set(false);
                if (callback != null) {
                    callback.onError("Failed to start: " + e.getMessage());
                }
            }
        });
    }
    
    public void stopListening() {
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.stopListening();
                    isListening.set(false);
                    Log.d(TAG, "Stopped listening");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop speech recognition", e);
            }
        });
    }
    
    public void destroy() {
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                    isListening.set(false);
                    Log.d(TAG, "Destroyed speech recognizer");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to destroy speech recognition", e);
            }
        });
    }
    
    private String getErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Network error (offline mode may not be available)";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech detected";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognition service busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Unknown error: " + error;
        }
    }
}
