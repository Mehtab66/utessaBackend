package com.crm.crm_backend.crm.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.RingCentralDtos.AuthorizeResponse;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.RcCallResponse;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.RcStatus;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.SyncResult;
import com.crm.crm_backend.crm.api.dto.RingCentralDtos.WebPhoneResponse;
import com.crm.crm_backend.crm.application.RingCentralClient.RcApiException;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.PhoneMessage;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.RcCall;
import com.crm.crm_backend.crm.domain.RcConnection;
import com.crm.crm_backend.crm.domain.Voicemail;
import com.crm.crm_backend.crm.infrastructure.MessageRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.PhoneConfigRepository;
import com.crm.crm_backend.crm.infrastructure.RcCallRepository;
import com.crm.crm_backend.crm.infrastructure.RcConnectionRepository;
import com.crm.crm_backend.crm.infrastructure.VoicemailRepository;
import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * RingCentral phone system: per-user sign-in (OAuth authorization code), browser phone (WebRTC
 * SIP provisioning), call-log and inbound-text sync, missed-call handling and the missed-call
 * auto-text, and sending Utessa texts through RingCentral.
 *
 * <p>Screen-pop matching and call documentation reuse {@link CallDeskService} / {@link CallFlowService}.
 */
@Service
public class RingCentralService {

	private static final Logger log = LoggerFactory.getLogger(RingCentralService.class);
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final long STATE_TTL_SECONDS = 10 * 60;
	/** Call-log results that mean nobody picked up an inbound call. */
	private static final Set<String> MISSED_RESULTS = Set.of(
			"missed", "voicemail", "no answer", "rejected", "busy", "hang up", "abandoned", "declined", "stopped",
			// Call forwarded to the browser phone while it wasn't registered (page closed / refreshing)
			"ip phone offline");
	public static final String DEFAULT_AUTO_TEXT =
			"Sorry we missed your call! We will call you back shortly. Reply STOP to opt out.";

	private final RingCentralConfig config;
	private final RingCentralClient client;
	private final RingCentralTokens tokens;
	private final TokenCipher cipher;
	private final RcConnectionRepository connections;
	private final RcCallRepository calls;
	private final PatientRepository patients;
	private final MessageRepository messages;
	private final VoicemailRepository voicemails;
	private final PhoneConfigRepository phoneConfigs;
	private final UserRepository users;
	private final MembershipRepository memberships;
	private final TaskService taskService;
	private final CrmSupport support;

	public RingCentralService(
			RingCentralConfig config,
			RingCentralClient client,
			RingCentralTokens tokens,
			TokenCipher cipher,
			RcConnectionRepository connections,
			RcCallRepository calls,
			PatientRepository patients,
			MessageRepository messages,
			VoicemailRepository voicemails,
			PhoneConfigRepository phoneConfigs,
			UserRepository users,
			MembershipRepository memberships,
			TaskService taskService,
			CrmSupport support
	) {
		this.config = config;
		this.client = client;
		this.tokens = tokens;
		this.cipher = cipher;
		this.connections = connections;
		this.calls = calls;
		this.patients = patients;
		this.messages = messages;
		this.voicemails = voicemails;
		this.phoneConfigs = phoneConfigs;
		this.users = users;
		this.memberships = memberships;
		this.taskService = taskService;
		this.support = support;
	}

	// ── status ──────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public RcStatus status(UserPrincipal principal) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		RcConnection mine = connections.findByOrganizationIdAndUserId(orgId, principal.userId()).orElse(null);
		List<RcConnection> all = connections.findByOrganizationId(orgId);
		Map<UUID, String> names = userNames(all.stream().map(RcConnection::getUserId).toList());
		List<String> staff = all.stream()
				.filter(c -> "CONNECTED".equals(c.getStatus()))
				.map(c -> names.getOrDefault(c.getUserId(), "Staff member")
						+ (c.getExtensionNumber() == null ? "" : " · ext " + c.getExtensionNumber()))
				.toList();
		boolean admin = isAdmin(principal);
		Map<String, Object> practice = practiceConfig(orgId);
		return new RcStatus(
				config.isConfigured(),
				config.isEnabled(),
				mine != null && "CONNECTED".equals(mine.getStatus()),
				mine == null ? "NOT_CONNECTED" : mine.getStatus(),
				mine == null ? null : mine.getExtensionName(),
				mine == null ? null : mine.getExtensionNumber(),
				mine == null ? null : mine.getCallerIdNumber(),
				mine == null ? null : mine.getSmsNumber(),
				mine == null ? null : mine.getLastSyncAt(),
				mine == null ? null : mine.getLastError(),
				admin ? config.redirectUri() : null,
				staff,
				autoTextEnabled(practice),
				autoTextMessage(practice));
	}

	// ── sign-in (OAuth authorization code) ──────────────────────────────

	public AuthorizeResponse authorizeUrl(UserPrincipal principal) {
		support.requireAccess(principal);
		requireConfigured();
		byte[] nonce = new byte[12];
		RANDOM.nextBytes(nonce);
		String state = cipher.sign(String.join("|",
				principal.organizationId().toString(),
				principal.userId().toString(),
				String.valueOf(Instant.now().getEpochSecond() + STATE_TTL_SECONDS),
				HexFormat.of().formatHex(nonce)));
		return new AuthorizeResponse(client.authorizeUrl(state));
	}

	/** Result shown in the sign-in popup. */
	public record CallbackResult(boolean ok, String message) {
	}

	/** Handles RingCentral's redirect. Runs without a Utessa session: identity comes from the signed state. */
	@Transactional
	public CallbackResult handleCallback(String code, String state, String error, String errorDescription) {
		if (error != null && !error.isBlank()) {
			return new CallbackResult(false, errorDescription != null && !errorDescription.isBlank() ? errorDescription : error);
		}
		String payload = cipher.verify(state);
		if (payload == null || code == null || code.isBlank()) {
			return new CallbackResult(false, "The RingCentral sign-in link was invalid. Start again from Utessa.");
		}
		String[] parts = payload.split("\\|");
		UUID orgId;
		UUID userId;
		try {
			if (parts.length < 3 || Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
				return new CallbackResult(false, "The RingCentral sign-in took too long. Start again from Utessa.");
			}
			orgId = UUID.fromString(parts[0]);
			userId = UUID.fromString(parts[1]);
		} catch (IllegalArgumentException ex) {
			return new CallbackResult(false, "The RingCentral sign-in link was invalid. Start again from Utessa.");
		}
		Membership membership = memberships.findByUserIdAndOrganizationId(userId, orgId).orElse(null);
		if (membership == null) {
			return new CallbackResult(false, "Your Utessa account is no longer a member of this practice.");
		}
		return withTenant(orgId, userId, membership.getRole().name(), () -> {
			try {
				JsonNode token = client.token(Map.of(
						"grant_type", "authorization_code",
						"code", code,
						"redirect_uri", config.redirectUri()));
				RcConnection c = connections.findByOrganizationIdAndUserId(orgId, userId).orElseGet(() -> {
					RcConnection n = new RcConnection();
					n.setOrganizationId(orgId);
					n.setUserId(userId);
					return n;
				});
				tokens.apply(c, token);
				c.setStatus("CONNECTED");
				c.setLastError(null);
				loadProfile(c, token.path("access_token").asText());
				connections.save(c);
				return new CallbackResult(true, "RingCentral is connected"
						+ (c.getExtensionNumber() == null ? "." : " (ext " + c.getExtensionNumber() + ")."));
			} catch (RcApiException ex) {
				log.warn("RingCentral sign-in failed: {}", ex.getMessage());
				return new CallbackResult(false, "RingCentral sign-in failed: " + ex.getMessage());
			}
		});
	}

	/** Reads the extension and its numbers; picks the caller ID and the SMS-capable number. */
	private void loadProfile(RcConnection c, String accessToken) {
		JsonNode ext = client.get(accessToken, "/restapi/v1.0/account/~/extension/~");
		c.setRcExtensionId(textOrNull(ext.path("id")));
		c.setRcAccountId(textOrNull(ext.path("account").path("id")));
		c.setExtensionNumber(textOrNull(ext.path("extensionNumber")));
		c.setExtensionName(textOrNull(ext.path("name")));
		List<Map<String, Object>> numbers = new ArrayList<>();
		try {
			JsonNode list = client.get(accessToken, "/restapi/v1.0/account/~/extension/~/phone-number?perPage=100");
			for (JsonNode n : list.path("records")) {
				String phone = textOrNull(n.path("phoneNumber"));
				if (phone == null) {
					continue;
				}
				List<String> features = new ArrayList<>();
				n.path("features").forEach(f -> features.add(f.asText()));
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("phoneNumber", phone);
				row.put("usageType", n.path("usageType").asText(""));
				row.put("features", features);
				numbers.add(row);
			}
		} catch (RcApiException ex) {
			log.warn("RingCentral phone-number lookup failed: {}", ex.getMessage());
		}
		c.setPhoneNumbers(numbers);
		c.setSmsNumber(pickNumber(numbers, "SmsSender"));
		String configured = config.fromNumber().isBlank() ? null : config.fromNumber();
		c.setCallerIdNumber(configured != null ? configured : pickNumber(numbers, "CallerId"));
	}

	/** Prefers the extension's own direct number, then any number with the feature. */
	@SuppressWarnings("unchecked")
	private static String pickNumber(List<Map<String, Object>> numbers, String feature) {
		String fallback = null;
		for (Map<String, Object> n : numbers) {
			List<String> f = (List<String>) n.getOrDefault("features", List.of());
			if (!f.contains(feature)) {
				continue;
			}
			if ("DirectNumber".equalsIgnoreCase(String.valueOf(n.get("usageType")))) {
				return String.valueOf(n.get("phoneNumber"));
			}
			if (fallback == null) {
				fallback = String.valueOf(n.get("phoneNumber"));
			}
		}
		return fallback;
	}

	@Transactional
	public void disconnect(UserPrincipal principal) {
		support.requireAccess(principal);
		connections.findByOrganizationIdAndUserId(support.orgId(), principal.userId()).ifPresent(c -> {
			client.revoke(cipher.decrypt(c.getRefreshTokenEnc()));
			connections.delete(c);
		});
	}

	// ── browser phone ───────────────────────────────────────────────────

	/** POST /restapi/v1.0/client-info/sip-provision — SIP credentials for the WebRTC phone. */
	@Transactional(readOnly = true)
	public WebPhoneResponse webPhone(UserPrincipal principal) {
		support.requireAccess(principal);
		requireConfigured();
		RcConnection c = requireMine(principal);
		try {
			JsonNode res = tokens.call(c.getId(), "POST", "/restapi/v1.0/client-info/sip-provision",
					Map.of("sipInfo", List.of(Map.of("transport", "WSS"))));
			JsonNode sip = res.path("sipInfo").path(0);
			if (sip.isMissingNode()) {
				throw ApiException.badRequest("RingCentral did not return browser-phone credentials.");
			}
			return new WebPhoneResponse(sip, textOrNull(res.path("device").path("id")),
					c.getCallerIdNumber(), c.getExtensionNumber(), c.getExtensionName());
		} catch (RcApiException ex) {
			throw ApiException.badRequest(explain(ex));
		}
	}

	private static String explain(RcApiException ex) {
		String blob = ex.getMessage() + " " + (ex.body() == null ? "" : ex.body().toString());
		if (blob.matches("(?is).*voip ?calling.*")) {
			return "Turn on the \"VoIP Calling\" permission for the RingCentral app, then connect RingCentral again.";
		}
		if (blob.matches("(?is).*emergency address.*")) {
			return "Set an emergency address on your RingCentral extension, then try again.";
		}
		if (blob.matches("(?is).*(digital line|device).*")) {
			return "Your RingCentral extension needs a digital line for browser calling. " + ex.getMessage();
		}
		return ex.getMessage();
	}

	// ── call log ────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<RcCallResponse> callLog(UserPrincipal principal, int limit, boolean missedOnly) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
		List<RcCall> list = missedOnly
				? calls.findByOrganizationIdAndMissedTrueOrderByStartedAtDesc(orgId, page)
				: calls.findByOrganizationIdOrderByStartedAtDesc(orgId, page);
		Map<UUID, Patient> ps = support.patients(list.stream().map(RcCall::getPatientId).toList());
		Map<UUID, String> staff = userNames(list.stream().map(RcCall::getConnectionUserId).toList());
		return list.stream().map(r -> {
			Patient p = ps.get(r.getPatientId());
			boolean inbound = "INBOUND".equals(r.getDirection());
			return new RcCallResponse(r.getId(), r.getDirection(), r.getResult(), r.isMissed(),
					r.getFromNumber(), r.getFromName(), r.getToNumber(), r.getToName(),
					inbound ? r.getFromNumber() : r.getToNumber(),
					p == null ? null : p.getId(), p == null ? null : p.getFullName(),
					p == null ? null : p.getLifecycleStage().name(),
					r.getStartedAt(), r.getDurationSeconds(), r.isHasRecording(),
					r.getAutoTextStatus(), r.getAutoTextNote(), staff.get(r.getConnectionUserId()));
		}).toList();
	}

	/**
	 * Pulls the signed-in user's call log and texts now instead of waiting for the background sync.
	 * Transactional itself: {@link #sync} is called on {@code this}, which bypasses Spring's proxy.
	 */
	@Transactional
	public SyncResult syncMine(UserPrincipal principal) {
		support.requireAccess(principal);
		RcConnection c = requireMine(principal);
		return sync(c.getId());
	}

	// ── sync (background job + "sync now") ─────────────────────────────

	@Transactional
	public SyncResult sync(UUID connectionId) {
		RcConnection c = connections.findById(connectionId).orElse(null);
		if (c == null || !"CONNECTED".equals(c.getStatus())) {
			return new SyncResult(0, 0, 0, 0, "Not connected");
		}
		Membership m = memberships.findByUserIdAndOrganizationId(c.getUserId(), c.getOrganizationId()).orElse(null);
		if (m == null) {
			return new SyncResult(0, 0, 0, 0, "User is no longer a member of the practice");
		}
		User u = users.findById(c.getUserId()).orElse(null);
		UserPrincipal actor = new UserPrincipal(c.getUserId(), c.getOrganizationId(),
				u == null ? null : u.getEmail(), m.getRole().name(), true, false);
		return withTenant(c.getOrganizationId(), c.getUserId(), m.getRole().name(), () -> doSync(c, actor));
	}

	private SyncResult doSync(RcConnection c, UserPrincipal actor) {
		Instant started = Instant.now();
		int newCalls = 0;
		int missed = 0;
		int autoTexts = 0;
		int newTexts = 0;
		String error = null;
		Map<String, Object> practice = practiceConfig(c.getOrganizationId());

		// 0 · No texting number yet? RingCentral may have enabled SMS since sign-in — check again.
		if (c.getSmsNumber() == null) {
			try {
				JsonNode list = tokens.call(c.getId(), "GET", "/restapi/v1.0/account/~/extension/~/phone-number?perPage=100", null);
				List<Map<String, Object>> numbers = new ArrayList<>();
				for (JsonNode n : list.path("records")) {
					List<String> features = new ArrayList<>();
					n.path("features").forEach(f -> features.add(f.asText()));
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("phoneNumber", n.path("phoneNumber").asText(""));
					row.put("usageType", n.path("usageType").asText(""));
					row.put("features", features);
					numbers.add(row);
				}
				String sms = pickNumber(numbers, "SmsSender");
				if (sms != null) {
					connections.updateSmsNumber(c.getId(), sms);
					c = connections.findById(c.getId()).orElse(c);
				}
			} catch (RcApiException | ApiException ex) {
				log.debug("RingCentral number refresh skipped: {}", ex.getMessage());
			}
		}

		// 1 · Call log
		try {
			Instant from = c.getLastSyncAt() == null
					? started.minus(24, ChronoUnit.HOURS)
					: c.getLastSyncAt().minus(10, ChronoUnit.MINUTES);
			JsonNode log = tokens.call(c.getId(), "GET", "/restapi/v1.0/account/~/extension/~/call-log?view=Simple&perPage=250&dateFrom="
					+ RingCentralClient.enc(from.truncatedTo(ChronoUnit.SECONDS).toString()), null);
			List<JsonNode> records = new ArrayList<>();
			log.path("records").forEach(records::add);
			records.sort((a, b) -> a.path("startTime").asText("").compareTo(b.path("startTime").asText("")));
			for (JsonNode r : records) {
				Handled h = importCall(c, r, actor, practice);
				if (h == null) {
					continue;
				}
				newCalls++;
				if (h.missed()) {
					missed++;
				}
				if (h.texted()) {
					autoTexts++;
				}
			}
		} catch (RcApiException | ApiException ex) {
			error = "Call log: " + ex.getMessage();
			log.warn("RingCentral call-log sync failed for connection {}: {}", c.getId(), ex.getMessage());
		}

		// 2 · Inbound texts
		try {
			newTexts = importInboundTexts(c, started);
		} catch (RcApiException | ApiException ex) {
			error = (error == null ? "" : error + " · ") + "Texts: " + ex.getMessage();
			log.warn("RingCentral message sync failed for connection {}: {}", c.getId(), ex.getMessage());
		}

		// The token columns may have been refreshed in another transaction; only touch the sync fields.
		connections.updateSync(c.getId(), started, started);
		return new SyncResult(newCalls, missed, autoTexts, newTexts, error);
	}

	private record Handled(boolean missed, boolean texted) {
	}

	private Handled importCall(RcConnection c, JsonNode r, UserPrincipal actor, Map<String, Object> practice) {
		String recordId = textOrNull(r.path("id"));
		if (recordId == null || calls.existsByOrganizationIdAndRcRecordId(c.getOrganizationId(), recordId)) {
			return null;
		}
		String tsid = textOrNull(r.path("telephonySessionId"));
		// The same call can appear in several staff members' logs (ring groups); keep it once.
		if (tsid != null && calls.existsByOrganizationIdAndTelephonySessionId(c.getOrganizationId(), tsid)) {
			return null;
		}
		boolean inbound = "inbound".equalsIgnoreCase(r.path("direction").asText());
		String result = textOrNull(r.path("result"));
		// A call to the company/main number that RingCentral forwards to this extension is logged as an
		// "Outbound" leg from the caller to our own number. It is really an incoming call.
		if (!inbound) {
			Set<String> own = ownNumbers(c);
			String toTail = CrmSupport.normalizePhone(textOrNull(r.path("to").path("phoneNumber")));
			String fromTail = CrmSupport.normalizePhone(textOrNull(r.path("from").path("phoneNumber")));
			if (toTail != null && own.contains(toTail) && (fromTail == null || !own.contains(fromTail))) {
				inbound = true;
			}
		}
		JsonNode other = inbound ? r.path("from") : r.path("to");
		String otherNumber = textOrNull(other.path("phoneNumber"));
		Instant startedAt;
		try {
			startedAt = Instant.parse(r.path("startTime").asText());
		} catch (Exception ex) {
			startedAt = Instant.now();
		}

		RcCall call = new RcCall();
		call.setOrganizationId(c.getOrganizationId());
		call.setRcRecordId(recordId);
		call.setTelephonySessionId(tsid);
		call.setSessionId(textOrNull(r.path("sessionId")));
		call.setConnectionUserId(c.getUserId());
		call.setDirection(inbound ? "INBOUND" : "OUTBOUND");
		call.setResult(result);
		call.setFromNumber(textOrNull(r.path("from").path("phoneNumber")));
		call.setFromName(textOrNull(r.path("from").path("name")));
		call.setToNumber(textOrNull(r.path("to").path("phoneNumber")));
		call.setToName(textOrNull(r.path("to").path("name")));
		call.setStartedAt(startedAt);
		call.setDurationSeconds(r.path("duration").isNumber() ? r.path("duration").asInt() : null);
		call.setHasRecording(r.hasNonNull("recording"));
		String normalized = CrmSupport.normalizePhone(otherNumber);
		call.setPhoneNormalized(normalized);
		Patient patient = null;
		if (normalized != null && normalized.length() >= 7) {
			List<Patient> found = patients.findByPhone(c.getOrganizationId(), normalized);
			patient = found.isEmpty() ? null : found.get(0);
		}
		call.setPatientId(patient == null ? null : patient.getId());
		boolean missed = inbound && result != null && MISSED_RESULTS.contains(result.toLowerCase());
		call.setMissed(missed);

		boolean texted = false;
		// Only act on calls that happened after RingCentral was connected (no back-filling old calls).
		boolean fresh = !startedAt.isBefore(c.getCreatedAt().minusSeconds(60))
				&& startedAt.isAfter(Instant.now().minus(6, ChronoUnit.HOURS));
		if (missed && fresh && normalized != null && normalized.length() == 10) {
			texted = handleMissedCall(c, call, patient, actor, practice, otherNumber, "voicemail".equalsIgnoreCase(result));
		}
		calls.save(call);
		return new Handled(missed, texted);
	}

	/** Logs the missed call on the timeline, files a voicemail, adds a call-back task and sends the auto-text. */
	private boolean handleMissedCall(
			RcConnection c,
			RcCall call,
			Patient patient,
			UserPrincipal actor,
			Map<String, Object> practice,
			String rawNumber,
			boolean voicemailLeft
	) {
		String display = patient != null ? patient.getFullName()
				: call.getFromName() != null && !call.getFromName().isBlank() ? call.getFromName()
				: formatPhone(call.getPhoneNormalized());

		Activity a = new Activity();
		a.setPatientId(patient == null ? null : patient.getId());
		a.setActivityType(ActivityType.CALL_INBOUND);
		a.setSubject(voicemailLeft ? "Missed call · voicemail left" : "Missed call");
		a.setBody("Missed on RingCentral" + (c.getExtensionNumber() == null ? "" : " (ext " + c.getExtensionNumber() + ")")
				+ (call.getResult() == null ? "" : " · " + call.getResult()) + ".");
		a.setPhone(rawNumber);
		a.setCallOutcome("Missed");
		a.setDurationSeconds(call.getDurationSeconds());
		a.setPhoneLine("RingCentral");
		a.setCallerName(display);
		a.setCallerType(patient == null ? "NEW_CALLER" : (patient.isLead() ? "LEAD" : "PATIENT"));
		a.setActorUserId(null);
		a.setActorName("RingCentral");
		a.setOccurredAt(call.getStartedAt());
		call.setActivityId(support.save(a).getId());

		if (voicemailLeft) {
			Voicemail v = new Voicemail();
			v.setPatientId(patient == null ? null : patient.getId());
			v.setFromPhone(rawNumber);
			v.setPhoneNormalized(call.getPhoneNormalized());
			v.setCallerName(display);
			v.setMailbox("RingCentral" + (c.getExtensionNumber() == null ? "" : " · ext " + c.getExtensionNumber()));
			v.setDurationSeconds(call.getDurationSeconds());
			v.setReceivedAt(call.getStartedAt());
			voicemails.save(v);
		}

		try {
			taskService.createSystemTask(actor, patient == null ? null : patient.getId(), null, null,
					"Call back " + display + " · missed call", "CALL",
					patient == null ? Priority.HIGH : patient.getPriority(),
					Instant.now().plus(1, ChronoUnit.HOURS),
					patient != null && patient.getOwnerUserId() != null ? patient.getOwnerUserId() : c.getUserId());
		} catch (RuntimeException ex) {
			log.warn("Could not create call-back task: {}", ex.getMessage());
		}

		return sendAutoText(c, call, patient, practice, rawNumber);
	}

	private boolean sendAutoText(RcConnection c, RcCall call, Patient patient, Map<String, Object> practice, String rawNumber) {
		if (!autoTextEnabled(practice)) {
			skip(call, "SKIPPED", "Auto-text is turned off");
			return false;
		}
		RcConnection sender = smsSender(c.getOrganizationId(), c.getUserId());
		if (sender == null) {
			skip(call, "SKIPPED", "No SMS-enabled RingCentral number");
			return false;
		}
		String normalized = call.getPhoneNormalized();
		String blocked = blockedReason(c.getOrganizationId(), patient, normalized);
		if (blocked != null) {
			skip(call, "SKIPPED", blocked);
			return false;
		}
		// Don't text the same caller twice in a short time (repeat attempts, ring groups).
		Instant since = Instant.now().minus(2, ChronoUnit.HOURS);
		boolean recent = messages.findByOrganizationIdAndPhoneNormalizedOrderByCreatedAtAsc(c.getOrganizationId(), normalized).stream()
				.anyMatch(m -> "OUT".equals(m.getDirection()) && m.getCreatedAt() != null && m.getCreatedAt().isAfter(since));
		if (recent) {
			skip(call, "SKIPPED", "Already texted this number in the last 2 hours");
			return false;
		}
		String text = autoTextMessage(practice);
		PhoneMessage msg = new PhoneMessage();
		msg.setOrganizationId(c.getOrganizationId());
		msg.setPatientId(patient == null ? null : patient.getId());
		msg.setPhone(rawNumber);
		msg.setPhoneNormalized(normalized);
		msg.setContactName(patient != null ? patient.getFullName() : call.getFromName());
		msg.setDirection("OUT");
		msg.setBody(text);
		msg.setLine("RingCentral");
		msg.setSentByName("Missed-call auto-reply");
		try {
			JsonNode res = sendSms(sender, rawNumber, text);
			msg.setStatus("SENT");
			msg.setExternalId("rc-sms:" + textOrNull(res.path("id")));
			messages.save(msg);
			skip(call, "SENT", "Sent from " + sender.getSmsNumber());
			if (patient != null) {
				Activity a = new Activity();
				a.setPatientId(patient.getId());
				a.setActivityType(ActivityType.SMS);
				a.setSubject("Missed-call auto-text sent");
				a.setBody(text);
				a.setPhone(rawNumber);
				a.setActorName("RingCentral");
				support.save(a);
			}
			return true;
		} catch (RcApiException | ApiException ex) {
			msg.setStatus("FAILED");
			messages.save(msg);
			skip(call, "FAILED", CrmSupport.truncate(ex.getMessage(), 240));
			return false;
		}
	}

	/** Last-10-digit forms of the extension's own numbers (direct, main company, fax…). */
	private static Set<String> ownNumbers(RcConnection c) {
		Set<String> own = new java.util.HashSet<>();
		for (Map<String, Object> n : c.getPhoneNumbers() == null ? List.<Map<String, Object>>of() : c.getPhoneNumbers()) {
			String d = CrmSupport.normalizePhone(String.valueOf(n.get("phoneNumber")));
			if (d != null) {
				own.add(d);
			}
		}
		String caller = CrmSupport.normalizePhone(c.getCallerIdNumber());
		if (caller != null) {
			own.add(caller);
		}
		return own;
	}

	private static void skip(RcCall call, String status, String note) {
		call.setAutoTextStatus(status);
		call.setAutoTextNote(CrmSupport.truncate(note, 255));
	}

	private int importInboundTexts(RcConnection c, Instant started) {
		Instant from = c.getLastMessageSyncAt() == null
				? started.minus(24, ChronoUnit.HOURS)
				: c.getLastMessageSyncAt().minus(5, ChronoUnit.MINUTES);
		JsonNode res = tokens.call(c.getId(), "GET", "/restapi/v1.0/account/~/extension/~/message-store?messageType=SMS&direction=Inbound&perPage=250&dateFrom="
				+ RingCentralClient.enc(from.truncatedTo(ChronoUnit.SECONDS).toString()), null);
		int added = 0;
		for (JsonNode m : res.path("records")) {
			String id = textOrNull(m.path("id"));
			if (id == null || messages.existsByOrganizationIdAndExternalId(c.getOrganizationId(), "rc-sms:" + id)) {
				continue;
			}
			String fromNumber = textOrNull(m.path("from").path("phoneNumber"));
			String normalized = CrmSupport.normalizePhone(fromNumber);
			if (normalized == null) {
				continue;
			}
			List<Patient> found = patients.findByPhone(c.getOrganizationId(), normalized);
			Patient p = found.size() == 1 ? found.get(0) : null;
			PhoneMessage msg = new PhoneMessage();
			msg.setOrganizationId(c.getOrganizationId());
			msg.setPatientId(p == null ? null : p.getId());
			msg.setPhone(fromNumber);
			msg.setPhoneNormalized(normalized);
			msg.setContactName(p != null ? p.getFullName() : textOrNull(m.path("from").path("name")));
			msg.setDirection("IN");
			msg.setBody(m.path("subject").asText(""));
			msg.setStatus("RECEIVED");
			msg.setLine("RingCentral");
			msg.setExternalId("rc-sms:" + id);
			messages.save(msg);
			if (p != null) {
				Activity a = new Activity();
				a.setPatientId(p.getId());
				a.setActivityType(ActivityType.SMS);
				a.setSubject("Text message received");
				a.setBody(msg.getBody());
				a.setPhone(fromNumber);
				a.setActorName("RingCentral");
				support.save(a);
			}
			added++;
		}
		return added;
	}

	// ── outbound texts from the Texts tab ───────────────────────────────

	/**
	 * Sends a queued Utessa text through RingCentral. Leaves it QUEUED when no one in the practice
	 * has an SMS-enabled RingCentral number connected; marks it FAILED when RingCentral refuses it.
	 */
	@Transactional
	public void deliver(PhoneMessage m, UUID senderUserId) {
		if (!config.isConfigured()) {
			return;
		}
		RcConnection sender = smsSender(m.getOrganizationId(), senderUserId);
		if (sender == null) {
			return;
		}
		try {
			JsonNode res = sendSms(sender, m.getPhone(), m.getBody());
			m.setStatus("SENT");
			m.setExternalId("rc-sms:" + textOrNull(res.path("id")));
			m.setLine(m.getLine() == null ? "RingCentral" : m.getLine());
		} catch (RcApiException ex) {
			log.warn("RingCentral SMS failed: {}", ex.getMessage());
			if (ex.status() >= 400 && ex.status() < 500 && ex.status() != 401 && ex.status() != 429) {
				m.setStatus("FAILED");
			}
		} catch (ApiException ex) {
			log.warn("RingCentral SMS not sent: {}", ex.getMessage());
		}
		messages.save(m);
	}

	private JsonNode sendSms(RcConnection sender, String to, String text) {
		String e164 = toE164(to);
		if (e164 == null) {
			throw ApiException.badRequest("Not a valid US mobile number.");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", Map.of("phoneNumber", sender.getSmsNumber()));
		body.put("to", List.of(Map.of("phoneNumber", e164)));
		body.put("text", text);
		return tokens.call(sender.getId(), "POST", "/restapi/v1.0/account/~/extension/~/sms", body);
	}

	/** The sender's own SMS number if they have one, else any connected SMS number in the practice. */
	private RcConnection smsSender(UUID orgId, UUID preferredUserId) {
		List<RcConnection> all = connections.findByOrganizationId(orgId).stream()
				.filter(c -> "CONNECTED".equals(c.getStatus()) && c.getSmsNumber() != null)
				.toList();
		return all.stream().filter(c -> c.getUserId().equals(preferredUserId)).findFirst()
				.orElse(all.isEmpty() ? null : all.get(0));
	}

	/** Same rules as the Texts tab: STOP replies, do-not-contact, and SMS consent for known patients. */
	private String blockedReason(UUID orgId, Patient patient, String normalized) {
		boolean optedOut = messages.findByOrganizationIdAndPhoneNormalizedOrderByCreatedAtAsc(orgId, normalized).stream()
				.filter(m -> "IN".equals(m.getDirection()))
				.reduce((a, b) -> b)
				.map(m -> m.getBody().trim().equalsIgnoreCase("STOP"))
				.orElse(false);
		if (optedOut) {
			return "Caller replied STOP";
		}
		if (patient != null && patient.isDoNotContact()) {
			return "Do-not-contact is set";
		}
		if (patient != null && !patient.isConsentSms()) {
			return "No SMS consent on the record";
		}
		return null;
	}

	// ── helpers ─────────────────────────────────────────────────────────

	public static boolean autoTextEnabled(Map<String, Object> practice) {
		Object v = practice.get("missedCallAutoText");
		return v == null || Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
	}

	public static String autoTextMessage(Map<String, Object> practice) {
		Object v = practice.get("missedCallTextMessage");
		String text = v == null ? null : String.valueOf(v).trim();
		return text == null || text.isEmpty() ? DEFAULT_AUTO_TEXT : CrmSupport.truncate(text, 600);
	}

	private Map<String, Object> practiceConfig(UUID orgId) {
		return phoneConfigs.findByOrganizationIdAndUserIdIsNull(orgId).map(p -> p.getConfig()).orElse(Map.of());
	}

	private RcConnection requireMine(UserPrincipal principal) {
		RcConnection c = connections.findByOrganizationIdAndUserId(support.orgId(), principal.userId())
				.orElseThrow(() -> ApiException.conflict("Connect your RingCentral account first."));
		if (!"CONNECTED".equals(c.getStatus())) {
			throw ApiException.conflict(c.getLastError() == null ? "Connect RingCentral again." : c.getLastError());
		}
		return c;
	}

	private void requireConfigured() {
		if (!config.isConfigured()) {
			throw ApiException.badRequest("RingCentral is not set up on the server. Add RINGCENTRAL_CLIENT_ID, "
					+ "RINGCENTRAL_CLIENT_SECRET and RINGCENTRAL_ENABLED=true to the backend .env.");
		}
	}

	private boolean isAdmin(UserPrincipal principal) {
		try {
			support.requireAdmin(principal);
			return true;
		} catch (ApiException ex) {
			return false;
		}
	}

	private Map<UUID, String> userNames(List<UUID> ids) {
		List<UUID> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
		if (clean.isEmpty()) {
			return Map.of();
		}
		return users.findAllById(clean).stream().collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
	}

	/** Runs with the given tenant, then restores whatever tenant the caller had. */
	private static <T> T withTenant(UUID orgId, UUID userId, String role, Supplier<T> work) {
		UUID prevOrg = TenantContext.getOrganizationId();
		UUID prevUser = TenantContext.getUserId();
		String prevRole = TenantContext.getRole();
		TenantContext.set(orgId, userId, role);
		try {
			return work.get();
		} finally {
			if (prevOrg == null) {
				TenantContext.clear();
			} else {
				TenantContext.set(prevOrg, prevUser, prevRole);
			}
		}
	}

	static String toE164(String phone) {
		String digits = phone == null ? "" : phone.replaceAll("[^0-9]", "");
		if (digits.length() == 10) {
			return "+1" + digits;
		}
		if (digits.length() == 11 && digits.startsWith("1")) {
			return "+" + digits;
		}
		if (phone != null && phone.trim().startsWith("+") && digits.length() >= 10 && digits.length() <= 15) {
			return "+" + digits;
		}
		return null;
	}

	private static String formatPhone(String ten) {
		if (ten == null || ten.length() != 10) {
			return ten == null ? "Unknown caller" : ten;
		}
		return "(" + ten.substring(0, 3) + ") " + ten.substring(3, 6) + "-" + ten.substring(6);
	}

	private static String textOrNull(JsonNode n) {
		if (n == null || n.isMissingNode() || n.isNull()) {
			return null;
		}
		String s = n.asText();
		return s == null || s.isBlank() ? null : s;
	}
}
