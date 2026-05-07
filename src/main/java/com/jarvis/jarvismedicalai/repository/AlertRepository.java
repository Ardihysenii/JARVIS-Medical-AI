package com.jarvis.jarvismedicalai.repository;

import com.jarvis.jarvismedicalai.entity.MedicalAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AlertRepository extends JpaRepository<MedicalAlert, Long> {

}