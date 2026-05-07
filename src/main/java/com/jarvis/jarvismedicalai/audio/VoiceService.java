package com.jarvis.jarvismedicalai.audio;

import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
public class VoiceService {

    /**
     * Versioni i optimizuar për ekzekutim të menjëhershëm në Windows.
     * Përdor Bypass për Execution Policy që sistemi të mos e bllokojë komandën.
     */
    public void speak(String text) {
        if (text == null || text.isBlank()) return;

        try {
            // Heqim karakteret që prishin skriptin
            String cleanText = text.replace("'", "").replace("\"", "");

            // Komanda më e thjeshtë dhe më e shpejtë për Windows
            String command = String.format(
                    "Add-Type -AssemblyName System.Speech; " +
                            "(New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('%s')",
                    cleanText
            );

            // Shtojmë Bypass që Windows ta lejojë ekzekutimin pa vonesë
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe",
                    "-ExecutionPolicy", "Bypass",
                    "-Command",
                    command
            );

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // I japim 5 sekonda kohë procesit të përfundojë para se Java ta mbyllë
            process.waitFor(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.err.println("[VOICE ERROR] Gabim fatal: " + e.getMessage());
        }
    }
}