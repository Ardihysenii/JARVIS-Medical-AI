package com.jarvis.jarvismedicalai.ai;

import com.jarvis.jarvismedicalai.util.WavHelper;
// --- NEW IMPORTS FOR DB AND EMAIL ---
import com.jarvis.jarvismedicalai.entity.MedicalAlert;
import com.jarvis.jarvismedicalai.repository.AlertRepository;
import com.jarvis.jarvismedicalai.Notification.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.jarvis.jarvismedicalai.audio.VoiceService;
// ------------------------------------
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║         JARVIS — Medical AI Brain for hellocare.ai               ║
 * ║         Patient Listener · Emergency Detector · Voice Speaker    ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 * Two AI engines in one service:
 *
 *  1. analyzePain(byte[]) — Sends patient room audio to Gemini,
 *     returns EMERGENCY or NORMAL.
 *
 *  2. speak(String) — Sends text to Gemini TTS, plays the spoken
 *     response through the system speakers in real time.
 *
 * Gemini models used:
 *   • Audio analysis : gemini-2.0-flash  (primary)
 *                      gemini-2.5-flash  (fallback)
 *   • Voice / TTS    : gemini-2.5-flash-preview-tts  (speaks aloud)
 */
@Service
public class JarvisBrain {

    private static final Logger log = Logger.getLogger(JarvisBrain.class.getName());

    // --- NEW BEAN INJECTIONS ---
    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private VoiceService voiceService;
    // ---------------------------

    // ── API Key ───────────────────────────────────────────────────────────────
    @Value("${google.api.key}")
    private String apiKey;

    // ── Gemini Endpoints ──────────────────────────────────────────────────────
    private static final String GEMINI_BASE_URL  = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String PRIMARY_MODEL    = "gemini-2.5-flash:generateContent";
    private static final String FALLBACK_MODEL   = "gemini-2.5-flash:generateContent";


    // Gemini TTS model — returns spoken audio for any text input
    private static final String TTS_MODEL        = "gemini-2.5-flash-preview-tts:generateContent";

    // Gemini TTS output format: 24 kHz · 16-bit · Mono · Little-Endian PCM
    private static final float TTS_SAMPLE_RATE   = 24000f;
    private static final int   TTS_SAMPLE_BITS   = 16;
    private static final int   TTS_CHANNELS      = 1;

    // ── Audio Size Guards (for patient mic input) ─────────────────────────────
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024; // 20 MB Gemini limit
    private static final int MIN_AUDIO_BYTES = 3200;              // ~100ms at 16kHz

    // ── Quota Cooldown ────────────────────────────────────────────────────────
    private volatile long quotaResetTimestamp = 0L;

    // ── Singleton HttpClient ──────────────────────────────────────────────────
    private HttpClient httpClient;


    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        log.info("[JARVIS] Brain initialized — hellocare.ai ready.");
    }

    // =========================================================================
    //  1. AUDIO ANALYSIS — Detect patient distress
    // =========================================================================

    /**
     * Sends raw PCM audio from the patient room to Gemini and returns:
     *   "EMERGENCY"       — distress detected, alert staff
     *   "NORMAL"          — no distress
     *   "AUDIO_TOO_SHORT" — clip too short to analyze
     *   "AUDIO_TOO_LARGE" — exceeds Gemini 20MB inline limit
     *   "QUOTA_COOLDOWN"  — waiting for API quota to reset
     *   "API_ERROR_XXX"   — Gemini HTTP error
     *   "SYSTEM_FAULT: X" — unexpected exception
     */
    public String analyzePain(byte[] rawAudio) {

        // ── Validate audio ────────────────────────────────────────────────────
        if (rawAudio == null || rawAudio.length < MIN_AUDIO_BYTES) {
            log.warning("[JARVIS] Audio too short: " +
                    (rawAudio == null ? 0 : rawAudio.length) + " bytes.");
            return "AUDIO_TOO_SHORT";
        }
        if (rawAudio.length > MAX_AUDIO_BYTES) {
            log.warning("[JARVIS] Audio too large: " +
                    (rawAudio.length / 1024 / 1024) + " MB.");
            return "AUDIO_TOO_LARGE";
        }

        // ── Quota cooldown ────────────────────────────────────────────────────
        long now = System.currentTimeMillis();
        if (now < quotaResetTimestamp) {
            long waitSec = (quotaResetTimestamp - now) / 1000;
            log.warning("[JARVIS] Quota cooldown: " + waitSec + "s remaining. Skipping.");
            return "QUOTA_COOLDOWN";
        }

        try {
            // Add WAV header + encode to base64
            byte[] wavAudio    = WavHelper.addWavHeader(rawAudio);
            String base64Audio = Base64.getEncoder().encodeToString(wavAudio);

            // Log how much data we're sending (helps debug silent/corrupt audio)
            log.info("[JARVIS] Sending to Gemini — WAV size: " + wavAudio.length +
                    " bytes | base64 length: " + base64Audio.length() + " chars");

            // Try primary model, fall back on 404 / 503
            String result = callGemini(PRIMARY_MODEL, base64Audio);
            if (result.startsWith("API_ERROR_404") || result.startsWith("API_ERROR_503")) {
                log.warning("[JARVIS] Primary model unavailable — switching to fallback.");
                result = callGemini(FALLBACK_MODEL, base64Audio);
            }

            // --- EMERGENCY TRIGGER LOGIC ---
            if (result != null && result.contains("EM")) {

                MedicalAlert alert = new MedicalAlert("EMERGENCY", "Critical vocal distress detected in patient room.");
                alertRepository.save(alert);

                notificationService.sendEmailJSAlert(
                        "EMERGENCY",
                        "Patient is in a critical moment! GO HELP FAST!"
                );

                log.info("[JARVIS] Emergency recorded in DB and Email notification sent.");

                // KJO PJESË MUNGONTE:
                log.info("[JARVIS] Starting Voice Reassurance Mode...");
                startPatientReassurance();
            }
            // -------------------------------

            return result;

        } catch (Exception e) {
            log.severe("[JARVIS] System fault in analyzePain: " + e.getMessage());
            return "SYSTEM_FAULT: " + e.getMessage();
        }
    }

    private void startPatientReassurance() {
        new Thread(() -> {
            try {
                voiceService.speak("I have detected your distress. Do not worry, I have already notified the medical staff.");
                Thread.sleep(3500);
                voiceService.speak("Please try to stay calm and breathe slowly.");
            } catch (Exception e) {
                log.warning("[JARVIS] Voice thread interrupted: " + e.getMessage());
            }
        }).start();
    }



    // =========================================================================
    //  2. VOICE / TTS — JARVIS speaks aloud
    // =========================================================================

    /**
     * Converts text to speech using Gemini TTS and plays it through
     * the system speakers immediately.
     *
     * Usage examples:
     *   jarvisBrain.speak("Emergency detected in room 3. Alerting staff.");
     *   jarvisBrain.speak("Duke monitoruar dhomën. Gjithçka normale.");
     *   jarvisBrain.speak("Warning: unusual sounds detected. Please check the patient.");
     *
     * @param text  The sentence JARVIS should say out loud.
     */
    public void speak(String text) {
        if (text == null || text.isBlank()) {
            log.warning("[JARVIS-TTS] speak() called with empty text.");
            return;
        }

        log.info("[JARVIS-TTS] Speaking: \"" + text + "\"");
        System.out.println("[JARVIS] 🔊 Speaking: " + text);

        try {
            // ── Build TTS request body ────────────────────────────────────────
            String ttsBody = buildTtsBody(text);
            String url     = GEMINI_BASE_URL + TTS_MODEL + "?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(ttsBody))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();
            log.info("[JARVIS-TTS] API response status: " + status);

            if (status != 200) {
                String err = extractApiError(response.body());
                log.severe("[JARVIS-TTS] TTS API error " + status + ": " + err);
                System.err.println("[JARVIS-TTS] Could not generate speech: " + err);
                return;
            }

            // ── Extract base64 PCM audio from response ────────────────────────
            byte[] pcmAudio = extractTtsAudio(response.body());
            if (pcmAudio == null || pcmAudio.length == 0) {
                log.severe("[JARVIS-TTS] Empty audio data in TTS response.");
                System.err.println("[JARVIS-TTS] No audio received from Gemini TTS.");
                return;
            }

            log.info("[JARVIS-TTS] Received " + pcmAudio.length + " bytes of PCM audio.");

            // ── Play through speakers ─────────────────────────────────────────
            playPcmAudio(pcmAudio);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warning("[JARVIS-TTS] TTS interrupted.");
        } catch (Exception e) {
            log.severe("[JARVIS-TTS] TTS fault: " + e.getMessage());
            System.err.println("[JARVIS-TTS] Speech failed: " + e.getMessage());
        }
    }

    /**
     * Plays raw 16-bit little-endian PCM audio through the default system speakers.
     * Gemini TTS returns 24kHz mono PCM — this method matches that format exactly.
     */
    private void playPcmAudio(byte[] pcmData) throws Exception {
        AudioFormat format = new AudioFormat(
                TTS_SAMPLE_RATE,   // 24000 Hz
                TTS_SAMPLE_BITS,   // 16-bit
                TTS_CHANNELS,      // Mono
                true,              // Signed
                false              // Little-endian
        );

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            log.severe("[JARVIS-TTS] Speaker output not supported on this system.");
            System.err.println("[JARVIS-TTS] No speaker output available.");
            return;
        }

        try (SourceDataLine speaker = (SourceDataLine) AudioSystem.getLine(info)) {
            speaker.open(format, pcmData.length);
            speaker.start();

            // Write PCM in chunks so playback starts immediately (streaming feel)
            int chunkSize = 4096;
            int offset    = 0;
            while (offset < pcmData.length) {
                int remaining = pcmData.length - offset;
                int toWrite   = Math.min(chunkSize, remaining);
                speaker.write(pcmData, offset, toWrite);
                offset += toWrite;
            }

            // Wait until the speaker buffer drains completely before closing
            speaker.drain();
            speaker.stop();
        }

        log.info("[JARVIS-TTS] Playback complete.");
    }

    /**
     * Builds the Gemini TTS request JSON body.
     *
     * Voice "Kore" is chosen because it is calm and authoritative —
     * suitable for a medical AI assistant.
     * Other voices: Puck, Charon, Fenrir, Aoede, Orbit, Zephyr
     */
    private String buildTtsBody(String text) {
        return new StringBuilder()
                .append("{")
                .append("\"contents\":[{")
                .append("\"parts\":[{\"text\":\"").append(escapeJson(text)).append("\"}]")
                .append("}],")
                .append("\"generationConfig\":{")
                .append("\"responseModalities\":[\"AUDIO\"],")
                .append("\"speechConfig\":{")
                .append("\"voiceConfig\":{")
                .append("\"prebuiltVoiceConfig\":{")
                .append("\"voiceName\":\"Kore\"")   // calm, authoritative medical voice
                .append("}")
                .append("}")
                .append("}")
                .append("}")
                .append("}")
                .toString();
    }

    /**
     * Extracts the base64-encoded PCM audio from a Gemini TTS response.
     *
     * TTS response structure:
     * {
     *   "candidates": [{
     *     "content": {
     *       "parts": [{
     *         "inlineData": {
     *           "mimeType": "audio/pcm;rate=24000",
     *           "data": "<base64 PCM>"
     *         }
     *       }]
     *     }
     *   }]
     * }
     */
    private byte[] extractTtsAudio(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;

        // Match "data":"<base64>" inside inlineData block
        Pattern p = Pattern.compile(
                "\"inlineData\"\\s*:\\s*\\{[^}]*\"data\"\\s*:\\s*\"([A-Za-z0-9+/=]+)\"",
                Pattern.DOTALL
        );
        Matcher m = p.matcher(responseBody);
        if (m.find()) {
            try {
                return Base64.getDecoder().decode(m.group(1));
            } catch (IllegalArgumentException e) {
                log.severe("[JARVIS-TTS] Failed to decode base64 audio: " + e.getMessage());
            }
        }

        // Fallback: try any large base64 blob (Gemini sometimes reorders fields)
        Pattern fallback = Pattern.compile("\"data\"\\s*:\\s*\"([A-Za-z0-9+/=]{100,})\"");
        Matcher fb = fallback.matcher(responseBody);
        if (fb.find()) {
            try {
                return Base64.getDecoder().decode(fb.group(1));
            } catch (IllegalArgumentException e) {
                log.severe("[JARVIS-TTS] Fallback decode failed: " + e.getMessage());
            }
        }

        log.warning("[JARVIS-TTS] Could not find audio data in response: " +
                responseBody.substring(0, Math.min(500, responseBody.length())));
        return null;
    }

    // =========================================================================
    //  PRIVATE — GEMINI ANALYSIS API CALL WITH SMART RETRY
    // =========================================================================

    private String callGemini(String model, String base64Audio) {
        String url      = GEMINI_BASE_URL + model + "?key=" + apiKey;
        String jsonBody = buildAnalysisBody(base64Audio);

        int maxRetries = 2;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                int status = response.statusCode();
                log.info("[JARVIS] API response — model=" + model +
                        " | status=" + status + " | attempt=" + attempt);

                if (status == 200) {
                    return parseAiResponse(response.body());
                }

                String errorDetail = extractApiError(response.body());

                if (status == 429) {
                    long retryAfterMs = parseRetryAfterMs(response.body());
                    quotaResetTimestamp = System.currentTimeMillis() +
                            (retryAfterMs > 0 ? retryAfterMs : 60_000L);
                    log.warning("[JARVIS] 429 — Quota exceeded. Cooldown: " +
                            (quotaResetTimestamp - System.currentTimeMillis()) / 1000 + "s. " +
                            "Enable billing: console.cloud.google.com/billing");
                    return "API_ERROR_429: " + errorDetail;
                }

                if (status == 400 || status == 401 || status == 403 || status == 404) {
                    log.severe("[JARVIS] Hard error " + status + ": " + errorDetail);
                    return "API_ERROR_" + status + ": " + errorDetail;
                }

                if (attempt < maxRetries) {
                    log.warning("[JARVIS] Server error " + status + " — retrying in 3s...");
                    Thread.sleep(3_000);
                } else {
                    return "API_ERROR_" + status + ": " + errorDetail;
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "SYSTEM_FAULT: Thread interrupted.";
            } catch (Exception e) {
                log.warning("[JARVIS] Network error (attempt " + attempt + "): " + e.getMessage());
                if (attempt == maxRetries) return "SYSTEM_FAULT: " + e.getMessage();
                try { Thread.sleep(3_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "SYSTEM_FAULT: Thread interrupted.";
                }
            }
        }
        return "SYSTEM_FAULT: All retries exhausted.";
    }



    // =========================================================================
    //  PRIVATE — JSON BUILDERS
    // =========================================================================

    /**
     * Builds the patient audio analysis request body.
     *
     * temperature = 0.0 → fully deterministic (critical for medical decisions)
     * maxOutputTokens = 10 → we only want ONE word back
     */
    private String buildAnalysisBody(String base64Audio) {
        String prompt =
                "You are Jarvis, an advanced automated medical clinical assistant monitoring room audio.\n\n" +
                        "CRITICAL INSTRUCTIONS:\n" +
                        "1. Analyze the audio clip to see if there is an EMERGENCY (screaming, gasping, agonizing pain, calls for help).\n" +
                        "2. Listen carefully and transcribe any human spoken words into the TRANSCRIPT section.\n\n" +
                        "OUTPUT FORMAT REQUIRED:\n" +
                        "Your entire output must look precisely like this template, do not omit the separator block:\n" +
                        "STATUS: [Put either EMERGENCY or NORMAL here]\n" +
                        "TRANSCRIPT: [Write the exact words spoken here, or write NONE if there is no talking]";

        return new StringBuilder()
                .append("{")
                .append("\"contents\":[{\"parts\":[")
                .append("{\"text\":\"").append(escapeJson(prompt)).append("\"},")
                .append("{\"inline_data\":{")
                .append("\"mime_type\":\"audio/wav\",")
                .append("\"data\":\"").append(base64Audio).append("\"")
                .append("}}")
                .append("]}],")
                .append("\"generationConfig\":{")
                .append("\"temperature\":0.1,")
                .append("\"maxOutputTokens\":150")
                .append("}}")
                .toString();
    }
    // =========================================================================
    //  PRIVATE — RESPONSE PARSERS
    // =========================================================================

    private String parseAiResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            log.warning("[JARVIS] Empty Gemini response.");
            return "NORMAL";
        }

        // Strategy 1: precise — extract from parts array
        Pattern partsPattern = Pattern.compile(
                "\"parts\"\\s*:\\s*\\[\\s*\\{\\s*\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"",
                Pattern.DOTALL
        );
        Matcher pm = partsPattern.matcher(responseBody);
        if (pm.find()) return classifyResponse(pm.group(1));

        // Strategy 2: fallback — any "text" field
        Pattern textPattern = Pattern.compile(
                "\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"", Pattern.DOTALL
        );
        Matcher tm = textPattern.matcher(responseBody);
        if (tm.find()) return classifyResponse(tm.group(1));

        log.warning("[JARVIS] Could not parse response: " +
                responseBody.substring(0, Math.min(300, responseBody.length())));
        return "NORMAL";
    }

    private String classifyResponse(String rawText) {
        if (rawText == null || rawText.isBlank()) return "NORMAL";

        String upperText = rawText.toUpperCase();
        log.info("[JARVIS BRAIN] ENGINE RECEIVED: \n" + rawText);

        // 1. Precise Emergency Triage Check
        if (upperText.contains("STATUS: EMERGENCY") || upperText.contains("EMERGENCY")) {
            System.out.println(">>> [ALERT] CRITICAL THRESHOLD TRIGGERED!");
            // Your database recording and alert loops stay here...
            return "EMERGENCY";
        }

        // 2. Resilient Conversational Wake Word Parsing
        if (upperText.contains("TRANSCRIPT:")) {
            try {
                int transcriptIndex = upperText.indexOf("TRANSCRIPT:");
                String transcriptSection = rawText.substring(transcriptIndex + 11).trim();

                if (!transcriptSection.equalsIgnoreCase("NONE") && !transcriptSection.isBlank()) {
                    System.out.println(">>> [TRANSCRIPTION DIAGNOSTIC]: " + transcriptSection);

                    // Triggers conversational flow if your name is detected
                    if (transcriptSection.toLowerCase().contains("jarvis")) {
                        System.out.println(">>> [WAKE WORD CAPTURED] processing clinical context...");
                        String aiResponse = think(transcriptSection);
                        speak(aiResponse);
                    }
                }
            } catch (Exception e) {
                log.warning("[JARVIS] Failed parsing speech transcript data: " + e.getMessage());
            }
        }

        return "NORMAL";
    }

    // =========================================================================
    //  PRIVATE — UTILITIES
    // =========================================================================

    private long parseRetryAfterMs(String errorBody) {
        if (errorBody == null) return 0L;
        Pattern p = Pattern.compile("retry in (\\d+(?:\\.\\d+)?)s", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(errorBody);
        if (m.find()) {
            try {
                return (long)(Double.parseDouble(m.group(1)) * 1000) + 2000L;
            } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private String extractApiError(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) return "Unknown error";
        Pattern p = Pattern.compile(
                "\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*?)\"", Pattern.DOTALL);
        Matcher m = p.matcher(errorBody);
        if (m.find()) return m.group(1).replace("\\n", " ").trim();
        return errorBody.substring(0, Math.min(300, errorBody.length()));
    }

    private String escapeJson(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 3. COGNITION — JARVIS thinks and generates a medical response.
     * This takes text from the patient and returns a doctor-like response.
     */

    public String think(String patientText) {
        if (patientText == null || patientText.isBlank()) return null;

        log.info("[JARVIS-BRAIN] Thinking about: " + patientText);

        // DOCTOR PERSONA: Professional, calm, no prescriptions.
        String doctorPrompt =
                "CONTEXT: You are Jarvis, a professional medical AI assistant for hellocare.ai. " +
                        "TONE: Clinical, empathetic, and concise. " +
                        "RULES: " +
                        "1. Respond to the patient's inquiry like a triage doctor. " +
                        "2. CRITICAL: NEVER prescribe medications, dosages, or specific drugs. " +
                        "3. If asked for meds, say: 'I am an AI assistant and cannot issue prescriptions. Please consult our staff doctor.' " +
                        "4. Keep responses under 3 sentences because there are people in line. " +
                        "5. If they just say 'Hey Jarvis', respond with: 'Hello, I am Jarvis. How can I assist you with your health today?' " +
                        "PATIENT SAYS: " + patientText;

        try {
            // We reuse your existing callGemini logic but pass the doctor prompt
            String response = callGeminiForText(PRIMARY_MODEL, doctorPrompt);
            return response;
        } catch (Exception e) {
            log.severe("[JARVIS-BRAIN] Thinking failed: " + e.getMessage());
            return "I apologize, my internal systems are refreshing. How can I help?";
        }
    }

    // Helper for text-only generation (since your existing callGemini expects audio)
    private String callGeminiForText(String model, String textPrompt) throws Exception {
        String url = GEMINI_BASE_URL + model + "?key=" + apiKey;
        String jsonBody = "{\"contents\":[{\"parts\":[{\"text\":\"" + escapeJson(textPrompt) + "\"}]}]}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parseAiResponse(response.body()); // Reuses your existing parser
    }

    public String transcribe(byte[] rawAudio) {
        if (rawAudio == null || rawAudio.length < MIN_AUDIO_BYTES) return "";

        try {
            byte[] wavAudio = WavHelper.addWavHeader(rawAudio);
            String base64Audio = Base64.getEncoder().encodeToString(wavAudio);

            // We ask Gemini specifically to just transcribe what it hears
            String prompt = "Transcribe the following audio exactly. If you hear 'Hey Jarvis', make sure to include it. If there is only background noise, return an empty string.";

            String jsonBody = new StringBuilder()
                    .append("{")
                    .append("\"contents\":[{\"parts\":[")
                    .append("{\"text\":\"").append(escapeJson(prompt)).append("\"},")
                    .append("{\"inline_data\":{")
                    .append("\"mime_type\":\"audio/wav\",")
                    .append("\"data\":\"").append(base64Audio).append("\"")
                    .append("}}")
                    .append("]}],")
                    .append("\"generationConfig\":{")
                    .append("\"temperature\":0.0") // 0.0 for accuracy
                    .append("}}")
                    .toString();

            String url = GEMINI_BASE_URL + PRIMARY_MODEL + "?key=" + apiKey;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parseAiResponse(response.body());

        } catch (Exception e) {
            log.warning("[JARVIS-STT] Transcription failed: " + e.getMessage());
            return "";
        }
    }
}

