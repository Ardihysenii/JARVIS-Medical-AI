package com.jarvis.jarvismedicalai.audio;

import com.jarvis.jarvismedicalai.ai.JarvisBrain;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;

@Component
public class JarvisOrchestrator implements CommandLineRunner {

    private final JarvisEar ear;
    private final JarvisBrain brain;

    // We use a 3-second window for better context
    // 16000Hz * 2 bytes (16-bit) * 3 seconds = 96,000 bytes
    private static final int BUFFER_SIZE = 96000;


    private static final int OVERLAP_SIZE = 32000;

    public JarvisOrchestrator(JarvisEar ear, JarvisBrain brain) {
        this.ear = ear;
        this.brain = brain;
    }

    @Override
    public void run(String... args) {
        System.out.println("---");
        System.out.println(">>> [SYSTEM] JARVIS MEDICAL AI IS ONLINE");
        System.out.println(">>> [SYSTEM] MONITORING FOR DISTRESS & WAKE WORD: 'HEY JARVIS'");
        System.out.println("---");


        ByteArrayOutputStream rollingBuffer = new ByteArrayOutputStream();

        ear.start247Listening(audioChunk -> {
            try {
                rollingBuffer.write(audioChunk);

                if (rollingBuffer.size() >= BUFFER_SIZE) {
                    byte[] fullClip = rollingBuffer.toByteArray();


                    rollingBuffer.reset();
                    rollingBuffer.write(fullClip, fullClip.length - OVERLAP_SIZE, OVERLAP_SIZE);


                    CompletableFuture.runAsync(() -> handleAudioAnalysis(fullClip));
                }
            } catch (Exception e) {
                System.err.println("[CRITICAL ERROR]: " + e.getMessage());
            }
        });
    }

    private void handleAudioAnalysis(byte[] audioData) {
        try {
            String status = brain.analyzePain(audioData);
            if ("EMERGENCY".equals(status)) {
                System.out.println("!!! [ALERT] DISTRESS SIGNAL DETECTED - NOTIFYING NURSES !!!");

            }


            String transcript = brain.transcribe(audioData);

            if (transcript != null && !transcript.isBlank()) {
                String input = transcript.toLowerCase();


                if (input.contains("jarvis")) {
                    System.out.println(">>> [ACTIVE SESSION]: " + transcript);

                    // Check if it's just a greeting or a full question
                    String response;
                    if (input.endsWith("jarvis") || input.equals("hey jarvis")) {
                        response = "Hello there! I'm here. How can I help you today?";
                    } else {
                        // Let the Brain (LLM) generate a professional medical response
                        response = brain.think(transcript);
                    }

                    // Speak the response back to the patient
                    brain.speak(response);
                }
            }
        } catch (Exception e) {
            System.err.println("[ANALYSIS ERROR]: " + e.getMessage());
        }
    }
}