package com.crm.crm_backend.operations.domain;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Metadata for an uploaded file or a written document. Bytes live in OpsFileBlob. */
@Getter
@Setter
@Entity
@Table(name = "ops_files")
public class OpsFile extends OpsEntity {

	/** VAULT, VENDOR, RESOURCES or TRAINING. */
	@Column(nullable = false)
	private String space;

	@Column(name = "folder_id")
	private UUID folderId;

	@Column(name = "vendor_id")
	private UUID vendorId;

	@Column(name = "staff_id")
	private UUID staffId;

	private String category;

	@Column(name = "doc_type")
	private String docType;

	@Column(nullable = false)
	private String name;

	@Column(name = "content_type")
	private String contentType;

	@Column(name = "size_bytes")
	private Long sizeBytes;

	@Column(name = "body_text", columnDefinition = "TEXT")
	private String bodyText;

	@Column(name = "expires_on")
	private LocalDate expiresOn;

	@Column(columnDefinition = "TEXT")
	private String notes;

	@Column(name = "link_ref")
	private String linkRef;

	@Column(name = "uploaded_by")
	private String uploadedBy;
}
