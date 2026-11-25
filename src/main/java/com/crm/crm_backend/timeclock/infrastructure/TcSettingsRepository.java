package com.crm.crm_backend.timeclock.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.timeclock.domain.TcSettings;

public interface TcSettingsRepository extends JpaRepository<TcSettings, UUID> {
}
