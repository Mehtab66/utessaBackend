package com.crm.crm_backend.crm.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "crm_faxes")
public class Fax extends CrmAuditedEntity {

	/** IN or OUT. */
	@Column(nullable = false)
	private String direction;

	@Column(name = "fax_number", nullable = false)
	private String faxNumber;

	@Column(name = "party_name")
	private String partyName;

	@Column(name = "patient_id")
	private UUID patientId;

	private String subject;

	@Column(name = "document_type")
	private String documentType;

	private Integer pages;

	@Column(name = "cover_page", nullable = false)
	private boolean coverPage;

	@Column(name = "cover_note", columnDefinition = "TEXT")
	private String coverNote;

	/** QUEUED, SENT, FAILED, RECEIVED, FILED. */
	@Column(nullable = false)
	private String status;

	@Column(name = "file_name")
	private String fileName;

	@Column(name = "content_type")
	private String contentType;

	@Column(name = "file_size")
	private Integer fileSize;

	@Basic(fetch = FetchType.LAZY)
	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(name = "file_data", columnDefinition = "bytea")
	private byte[] fileData;

	@Column(name = "filed_by_name")
	private String filedByName;

	@Column(name = "filed_at")
	private Instant filedAt;

	@Column(name = "created_by_name")
	private String createdByName;
}
