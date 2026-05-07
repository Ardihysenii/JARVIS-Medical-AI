package com.jarvis.jarvismedicalai;

import com.jarvis.jarvismedicalai.audio.JarvisEar;
import com.jarvis.jarvismedicalai.ai.JarvisBrain;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class JarvisMedicalAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(JarvisMedicalAiApplication.class, args);
    }

    @Bean
    public CommandLineRunner testJarvis(JarvisEar ear, JarvisBrain brain) {
        return args -> {
            System.out.println("JARVIS STARTED...");
            while (true) {
                byte[] audio = ear.listen(5);
                String result = brain.analyzePain(audio);

                System.out.println("ANALIZA: " + result);

                if (result.toUpperCase().contains("EMERGENCY")) {
                    System.out.println("[ALARM] PAIN DETECTED - ALERT THE DOCTOR");
                }
                Thread.sleep(500);
            }
        };
    }
}