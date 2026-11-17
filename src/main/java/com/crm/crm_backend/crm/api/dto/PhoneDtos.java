package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Phone workspace: overview, contacts, texts, voicemail, fax and call-handling configuration. */
public final class PhoneDtos {

	private PhoneDtos() {
	}

	public record PhoneOverview(
			String phoneStatus,
			String phoneProvider,
			String mainNumber,
			long unreadTexts,
			long queuedTexts,
			long newVoicemails,
			long unfiledFaxes,
			long queuedFaxes,
			String myName,
			String myRole,
			Map<String, Object> myPrefs,
			Map<String, Object> practice,
			boolean canEditPractice
	) {
	}

	public record PhoneContact(
			String type,
			UUID id,
			String name,
			String subtitle,
			String phone,
			String fax,
			String email,
			boolean canText,
			String link
	) {
	}

	public record ThreadSummary(
			String phoneNormalized,
			String phone,
			String name,
			UUID patientId,
			String patientType,
			String lastBody,
			Instant lastAt,
			String lastDirection,
			String lastStatus,
			long unread,
			String blockedReason
	) {
	}

	public record MessageResponse(
			UUID id,
			String direction,
			String body,
			String status,
			String sentByName,
			String line,
			Instant createdAt
	) {
	}

	public record ThreadResponse(ThreadSummary thread, List<MessageResponse> messages) {
	}

	public record SendTextRequest(
			@NotBlank(message = "Enter a mobile number.") @Size(max = 50) String phone,
			UUID patientId,
			@Size(max = 150) String contactName,
			@NotBlank(message = "Write a message.") @Size(max = 640, message = "Keep texts under 640 characters.") String body,
			@Size(max = 40) String line
	) {
	}

	public record VoicemailResponse(
			UUID id,
			UUID patientId,
			String patientName,
			String patientType,
			String fromPhone,
			String callerName,
			String mailbox,
			Integer durationSeconds,
			String transcript,
			String recordingUrl,
			Instant receivedAt,
			String status,
			String handledByName,
			Instant handledAt
	) {
	}

	public record LogVoicemailRequest(
			@NotBlank(message = "Enter the caller's number.") @Size(max = 50) String fromPhone,
			@Size(max = 150) String callerName,
			@Size(max = 60) String mailbox,
			@PositiveOrZero Integer durationSeconds,
			String transcript,
			Instant receivedAt
	) {
	}

	public record FaxResponse(
			UUID id,
			String direction,
			String faxNumber,
			String partyName,
			UUID patientId,
			String patientName,
			String subject,
			String documentType,
			Integer pages,
			String status,
			String fileName,
			String contentType,
			Integer fileSize,
			boolean coverPage,
			String coverNote,
			String filedByName,
			Instant filedAt,
			String createdByName,
			Instant createdAt
	) {
	}

	public record FileFaxRequest(
			@NotNull(message = "Choose a patient.") UUID patientId,
			@Size(max = 60) String documentType
	) {
	}

	public record ForwardFaxRequest(
			@NotBlank(message = "Enter a fax number.") @Size(max = 50) String faxNumber,
			@Size(max = 150) String partyName
	) {
	}

	public record ConfigRequest(@NotNull Map<String, Object> config) {
	}
}
