package com.crm.crm_backend.operations.domain;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ops_file_blobs")
public class OpsFileBlob {

	@Id
	@Column(name = "file_id")
	private UUID fileId;

	@JdbcTypeCode(SqlTypes.VARBINARY)
	@Column(nullable = false, columnDefinition = "bytea")
	private byte[] data;
}
