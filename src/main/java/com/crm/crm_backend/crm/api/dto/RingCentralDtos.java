package com.crm.crm_backend.crm.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public final class RingCentralDtos {

	private RingCentralDtos() {
	}

	/** Connection state for the signed-in user plus the practice-wide view. */
	public record RcStatus(
			boolean configured,
			boolean enabled,
			boolean connected,
			String status,
			String extensionName,
			String extensionNumber,
			String callerIdNumber,
			String smsNumber,
			Instant lastSyncAt,
			String lastError,
			String redirectUri,
			List<String> connectedStaff,
			boolean autoTextEnabled,
			String autoTextMessage
	) {
	}

	public record AuthorizeResponse(String url) {
	}

	/** SIP credentials for the browser phone (WebRTC). */
	public record WebPhoneResponse(
			JsonNode sipInfo,
			String deviceId,
			String callerIdNumber,
			String extensionNumber,
			String extensionName
	) {
	}

	public record RcCallResponse(
			UUID id,
			String direction,
			String result,
			boolean missed,
			String fromNumber,
			String fromName,
			String toNumber,
			String toName,
			String phone,
			UUID patientId,
			String patientName,
			String lifecycleStage,
			Instant startedAt,
			Integer durationSeconds,
			boolean hasRecording,
			String autoTextStatus,
			String autoTextNote,
			String staffName
	) {
	}

	public record SyncResult(int newCalls, int missedCalls, int autoTexts, int newTexts, String error) {
	}
}
