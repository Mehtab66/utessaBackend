package com.crm.crm_backend.crm.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.crm.domain.Fax;
import com.crm.crm_backend.crm.domain.PhoneConfig;
import com.crm.crm_backend.crm.domain.PhoneMessage;
import com.crm.crm_backend.crm.domain.Voicemail;

/** List view without the file bytes. */
public interface FaxSummary {
	UUID getId();

	String getDirection();

	String getFaxNumber();

	String getPartyName();

	UUID getPatientId();

	String getSubject();

	String getDocumentType();

	Integer getPages();

	String getStatus();

	String getFileName();

	String getContentType();

	Integer getFileSize();

	boolean isCoverPage();

	String getCoverNote();

	String getFiledByName();

	Instant getFiledAt();

	String getCreatedByName();

	Instant getCreatedAt();
}
