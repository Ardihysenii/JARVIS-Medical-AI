package com.jarvis.jarvismedicalai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "medical_alerts")
public class MedicalAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String status;

    private LocalDateTime timestamp;

    @Column(columnDefinition = "TEXT")
    private String details;


    public MedicalAlert() {}

    public MedicalAlert(String status, String details) {
        this.status = status;
        this.details = details;
        this.timestamp = LocalDateTime.now();
    }


    public Long getId() { return id; }
    public String getStatus() { return status; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getDetails() { return details; }
}