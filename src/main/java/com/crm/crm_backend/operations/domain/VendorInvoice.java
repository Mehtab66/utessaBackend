package com.crm.crm_backend.operations.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "ops_vendor_invoices")
public class VendorInvoice extends OpsEntity {

	@Column(name = "vendor_id", nullable = false)
	private UUID vendorId;

	@Column(nullable = false)
	private String number;

	@Column(name = "invoice_date")
	private LocalDate invoiceDate;

	@Column(name = "due_date")
	private LocalDate dueDate;

	private BigDecimal amount;
	private String description;

	@Column(nullable = false)
	private String status = "Unpaid";

	@Column(name = "paid_on")
	private LocalDate paidOn;

	@Column(name = "paid_amount")
	private BigDecimal paidAmount;

	private String method;
	private String reference;

	@Column(name = "file_id")
	private UUID fileId;
}
