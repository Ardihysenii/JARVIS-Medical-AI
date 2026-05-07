package com.jarvis.jarvismedicalai.Notification;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class NotificationService {

    private final String SERVICE_ID = "service_e9z0ahr";
    private final String TEMPLATE_ID = "template_ai3mt2b";
    private final String PUBLIC_KEY = "YLZAsh3eQ876wDh6B";
    private final String PRIVATE_KEY = "4pB8oh8FSxq0ljBVVWTLE";

    public void sendEmailJSAlert(String status, String details) {
        HttpClient client = HttpClient.newHttpClient();
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM dd, yyyy | HH:mm:ss"));

        String jsonPayload = """
            {
                "service_id": "%s",
                "template_id": "%s",
                "user_id": "%s",
                "accessToken": "%s",
                "template_params": {
                    "alert_type": "%s",
                    "message": "%s",
                    "time": "%s"
                }
            }
            """.formatted(SERVICE_ID, TEMPLATE_ID, PUBLIC_KEY, PRIVATE_KEY, status, details, currentTime);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.emailjs.com/api/v1.0/email/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) {
                        System.out.println("✅ EmailJS: Alarmi u dërgua me sukses!");
                    } else {
                        System.out.println("❌ EmailJS Error: " + res.statusCode() + " - " + res.body());
                    }
                });
    }
}