package com.jarvis.jarvismedicalai.audio;

import org.springframework.stereotype.Component;
import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

@Component
public class JarvisEar {

    // Medical Grade Audio Settings
    private static final float SAMPLE_RATE = 16000f;
    private static final int SAMPLE_SIZE = 16;
    private static final int CHANNELS = 1;
    private static final int BUFFER_SIZE = 2048;

    private volatile boolean isRunning = false;
    private volatile boolean isMuted = false;
    private TargetDataLine sharedLine;

    // Advanced JARVIS Filters
    private static final double NOISE_FLOOR = 500.0; // Ignore hospital hum/HVAC
    private static final double TARGET_RMS = 6000.0; // Optimal for transcription
    private static final double MAX_GAIN = 15.0;     // Boost weak patient voices

    /**
     * FIX FOR YOUR IDE ERROR:
     * This method captures a specific duration and returns it.
     */
    public byte[] listen(int seconds) {
        try {
            ensureLineOpen();
            sharedLine.flush();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            long end = System.currentTimeMillis() + (seconds * 1000L);

            System.out.println(">>> [JARVIS] RECORDING SESSION STARTED...");

            while (System.currentTimeMillis() < end) {
                int count = sharedLine.read(buf, 0, buf.length);
                if (count > 0) {
                    double rms = calculateRMS(buf);
                    // We still apply AGC so the test clip is clear
                    byte[] processed = applyMedicalAGC(buf, rms);
                    out.write(processed, 0, processed.length);
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            System.err.println("[EAR ERROR] Failed to record: " + e.getMessage());
            return new byte[0];
        }
    }

    /**
     * JARVIS 24/7 MONITORING MODE
     */
    public void start247Listening(Consumer<byte[]> audioProcessor) {
        try {
            ensureLineOpen();
            isRunning = true;
            System.out.println(">>> [JARVIS] EAR IS NOW 24/7 ACTIVE...");

            byte[] buf = new byte[BUFFER_SIZE];

            while (isRunning) {
                int count = sharedLine.read(buf, 0, buf.length);

                // Mute logic prevents Jarvis from hearing himself speak
                if (count > 0 && !isMuted) {
                    double rms = calculateRMS(buf);

                    // VAD (Voice Activity Detection) - Only send sound if it's not silence
                    if (rms > NOISE_FLOOR) {
                        byte[] processed = applyMedicalAGC(buf, rms);
                        audioProcessor.accept(processed);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[EAR CRITICAL] 24/7 Loop crashed: " + e.getMessage());
        }
    }

    private void ensureLineOpen() throws LineUnavailableException {
        if (sharedLine == null || !sharedLine.isOpen()) {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE, CHANNELS, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

            if (!AudioSystem.isLineSupported(info)) {
                throw new RuntimeException("Microphone not supported or not found!");
            }

            sharedLine = (TargetDataLine) AudioSystem.getLine(info);
            sharedLine.open(format, BUFFER_SIZE * 10);
            sharedLine.start();
        }
    }

    private byte[] applyMedicalAGC(byte[] pcm, double currentRms) {
        byte[] processed = new byte[pcm.length];
        // Calculate how much we need to boost or lower the volume
        double gain = Math.min(MAX_GAIN, TARGET_RMS / Math.max(currentRms, 1.0));

        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
            double boosted = sample * gain;

            // Soft Limiter: Prevent "Ear Bleed" distortion
            if (Math.abs(boosted) > 28000) {
                boosted = Math.signum(boosted) * 28000;
            }

            short finalSample = (short) boosted;
            processed[i] = (byte) (finalSample & 0xFF);
            processed[i + 1] = (byte) ((finalSample >> 8) & 0xFF);
        }
        return processed;
    }

    private double calculateRMS(byte[] pcm) {
        long sum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
            sum += (long) sample * sample;
        }
        return Math.sqrt((double) sum / (pcm.length / 2.0));
    }

    public void setMute(boolean mute) { this.isMuted = mute; }

    public void stop() {
        this.isRunning = false;
        if (sharedLine != null) {
            sharedLine.stop();
            sharedLine.close();
        }
    }
}