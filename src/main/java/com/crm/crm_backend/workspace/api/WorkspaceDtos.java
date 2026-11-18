package com.crm.crm_backend.workspace.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WorkspaceDtos {

	private WorkspaceDtos() {
	}

	/* ── Tasks ─────────────────────────────────────────────── */
	public record TaskRequest(
			@NotBlank(message = "Give the task a title.") @Size(max = 255) String title,
			String notes,
			@Size(max = 20) String status,
			@Size(max = 20) String priority,
			LocalDate dueDate,
			UUID assigneeUserId
	) {
	}

	public record TaskStatusRequest(@NotBlank String status) {
	}

	public record TaskResponse(
			UUID id, String title, String notes, String status, String priority, LocalDate dueDate,
			UUID assigneeUserId, String assigneeName, UUID createdByUserId, String createdByName,
			Instant completedAt, Instant createdAt, Instant updatedAt
	) {
	}

	/* ── Calendar ──────────────────────────────────────────── */
	public record EventRequest(
			@NotBlank(message = "Give the event a title.") @Size(max = 255) String title,
			String description,
			@Size(max = 255) String location,
			@Size(max = 30) String category,
			@NotNull(message = "Start time is required.") Instant startsAt,
			Instant endsAt,
			Boolean allDay
	) {
	}

	public record EventResponse(
			UUID id, String title, String description, String location, String category,
			Instant startsAt, Instant endsAt, boolean allDay, String createdByName, String source
	) {
	}

	/* ── Notes ─────────────────────────────────────────────── */
	public record NoteRequest(
			@NotBlank(message = "Give the note a title.") @Size(max = 255) String title,
			String body,
			@Size(max = 20) String color,
			Boolean pinned,
			Boolean shared
	) {
	}

	public record NoteResponse(
			UUID id, String title, String body, String color, boolean pinned, boolean shared,
			boolean mine, String ownerName, Instant updatedAt
	) {
	}

	/* ── Team board ────────────────────────────────────────── */
	public record AnnouncementRequest(
			@NotBlank(message = "Give the post a title.") @Size(max = 255) String title,
			@NotBlank(message = "Write something to share.") String body,
			@Size(max = 30) String category,
			Boolean pinned
	) {
	}

	public record AnnouncementResponse(
			UUID id, String title, String body, String category, boolean pinned,
			String authorName, boolean canManage, Instant createdAt
	) {
	}

	/* ── Team directory ────────────────────────────────────── */
	public record TeamMember(
			UUID userId, String fullName, String email, String phone, String role,
			boolean you, Instant joinedAt, long openTasks
	) {
	}

	/* ── Home summary ──────────────────────────────────────── */
	public record WorkspaceSummary(
			long myOpenTasks,
			long dueToday,
			long overdue,
			long teamSize,
			List<TaskResponse> myTasks,
			List<EventResponse> upcomingEvents,
			List<AnnouncementResponse> announcements
	) {
	}
}
