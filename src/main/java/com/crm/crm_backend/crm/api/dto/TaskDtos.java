package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.TaskStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TaskDtos {

	private TaskDtos() {
	}

	public record TaskRequest(
			UUID patientId,
			UUID opportunityId,
			UUID referralId,
			@NotBlank(message = "Task title is required.") @Size(max = 255) String title,
			@Size(max = 50) String taskType,
			Priority priority,
			TaskStatus status,
			@NotNull(message = "Due date is required.") Instant dueAt,
			UUID assignedUserId,
			String notes
	) {
	}

	public record TaskResponse(
			UUID id,
			UUID patientId,
			String patientName,
			String patientPhone,
			UUID opportunityId,
			UUID referralId,
			String title,
			String taskType,
			Priority priority,
			TaskStatus status,
			Instant dueAt,
			boolean overdue,
			UUID assignedUserId,
			String assignedName,
			Instant completedAt,
			String completedByName,
			String notes,
			String createdByName,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record CompleteTaskRequest(
			String outcome,
			Instant nextFollowUpAt,
			@Size(max = 255) String nextFollowUpTitle
	) {
	}
}
