package com.crm.crm_backend.crm.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.crm.crm_backend.crm.api.dto.PhoneDtos.ConfigRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.FaxResponse;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.FileFaxRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.ForwardFaxRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.LogVoicemailRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.MessageResponse;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.PhoneContact;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.PhoneOverview;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.SendTextRequest;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.ThreadResponse;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.ThreadSummary;
import com.crm.crm_backend.crm.api.dto.PhoneDtos.VoicemailResponse;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.CrmIntegration;
import com.crm.crm_backend.crm.domain.Fax;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.PhoneConfig;
import com.crm.crm_backend.crm.domain.PhoneMessage;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.domain.Voicemail;
import com.crm.crm_backend.crm.infrastructure.CrmIntegrationRepository;
import com.crm.crm_backend.crm.infrastructure.FaxRepository;
import com.crm.crm_backend.crm.infrastructure.FaxSummary;
import com.crm.crm_backend.crm.infrastructure.MessageRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.PhoneConfigRepository;
import com.crm.crm_backend.crm.infrastructure.VoicemailRepository;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.RoleGuard;
import com.crm.crm_backend.shared.security.UserPrincipal;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Phone workspace. Texts and faxes sent from Utessa are stored as QUEUED and handed to the
 * phone-system connector once it is connected; nothing here claims a message was delivered.
 */
@Service
public class PhoneService {

	private static final long MAX_FAX_BYTES = 10L * 1024 * 1024;
	private static final Set<String> FAX_TYPES = Set.of("application/pdf", "image/tiff", "image/jpeg", "image/png");
	private static final int MAX_CONFIG_CHARS = 30_000;

	private final MessageRepository messages;
	private final VoicemailRepository voicemails;
	private final FaxRepository faxes;
	private final PhoneConfigRepository configs;
	private final CrmIntegrationRepository integrations;
	private final PatientRepository patients;
	private final StaffMemberRepository staff;
	private final CrmSupport support;
	private final ObjectMapper json = new ObjectMapper();

	public PhoneService(
			MessageRepository messages,
			VoicemailRepository voicemails,
			FaxRepository faxes,
			PhoneConfigRepository configs,
			CrmIntegrationRepository integrations,
			PatientRepository patients,
			StaffMemberRepository staff,
			CrmSupport support
	) {
		this.messages = messages;
		this.voicemails = voicemails;
		this.faxes = faxes;
		this.configs = configs;
		this.integrations = integrations;
		this.patients = patients;
		this.staff = staff;
		this.support = support;
	}

	// ── overview & configuration ────────────────────────────────────────

	@Transactional(readOnly = true)
	public PhoneOverview overview(UserPrincipal principal) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		CrmIntegration phone = integrations.findByOrganizationIdAndKind(orgId, CrmIntegration.PHONE).orElse(null);
		List<PhoneMessage> msgs = messages.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		long unread = msgs.stream().filter(m -> "IN".equals(m.getDirection()) && m.getReadAt() == null).count();
		long queuedTexts = msgs.stream().filter(m -> "QUEUED".equals(m.getStatus())).count();
		long newVm = voicemails.findByOrganizationIdOrderByReceivedAtDesc(orgId).stream().filter(v -> !"HANDLED".equals(v.getStatus())).count();
		List<FaxSummary> fx = faxes.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		long unfiled = fx.stream().filter(f -> "RECEIVED".equals(f.getStatus())).count();
		long queuedFax = fx.stream().filter(f -> "QUEUED".equals(f.getStatus())).count();
		String main = phone == null ? null : stringOf(phone.getSettings().get("mainNumber"));
		boolean admin;
		try {
			support.requireAdmin(principal);
			admin = true;
		} catch (ApiException ex) {
			admin = false;
		}
		return new PhoneOverview(
				phone == null ? "NOT_CONNECTED" : phone.getStatus(),
				phone == null ? null : phone.getProvider(),
				main, unread, queuedTexts, newVm, unfiled, queuedFax,
				support.actorName(principal), principal.role(),
				myConfig(principal), practiceConfig(), admin);
	}

	@Transactional
	public Map<String, Object> saveMyConfig(UserPrincipal principal, ConfigRequest request) {
		support.requireAccess(principal);
		PhoneConfig cfg = configs.findByOrganizationIdAndUserId(support.orgId(), principal.userId()).orElseGet(() -> {
			PhoneConfig c = new PhoneConfig();
			c.setUserId(principal.userId());
			return c;
		});
		Map<String, Object> merged = new LinkedHashMap<>(defaultUserPrefs());
		mergeKnown(merged, cfg.getConfig());
		mergeKnown(merged, checked(request.config()));
		cfg.setConfig(merged);
		configs.save(cfg);
		return merged;
	}

	@Transactional
	public Map<String, Object> savePracticeConfig(UserPrincipal principal, ConfigRequest request) {
		RoleGuard.requireOrgAccess(principal, CrmSupport.PERM_CRM_ADMIN);
		PhoneConfig cfg = configs.findByOrganizationIdAndUserIdIsNull(support.orgId()).orElseGet(PhoneConfig::new);
		Map<String, Object> merged = new LinkedHashMap<>(defaultPractice());
		mergeKnown(merged, cfg.getConfig());
		mergeKnown(merged, checked(request.config()));
		cfg.setConfig(merged);
		configs.save(cfg);
		return merged;
	}

	private Map<String, Object> myConfig(UserPrincipal principal) {
		Map<String, Object> out = new LinkedHashMap<>(defaultUserPrefs());
		configs.findByOrganizationIdAndUserId(support.orgId(), principal.userId()).ifPresent(c -> mergeKnown(out, c.getConfig()));
		return out;
	}

	private Map<String, Object> practiceConfig() {
		Map<String, Object> out = new LinkedHashMap<>(defaultPractice());
		configs.findByOrganizationIdAndUserIdIsNull(support.orgId()).ifPresent(c -> mergeKnown(out, c.getConfig()));
		return out;
	}

	private Map<String, Object> checked(Map<String, Object> in) {
		try {
			String raw = json.writeValueAsString(in);
			if (raw.length() > MAX_CONFIG_CHARS) {
				throw ApiException.badRequest("These settings are too large to save.");
			}
			if (hasSecretKey(in)) {
				throw ApiException.badRequest("Credentials can't be stored in phone settings.");
			}
		} catch (ApiException ex) {
			throw ex;
		} catch (Exception ex) {
			throw ApiException.badRequest("Settings could not be read.");
		}
		return in;
	}

	/** Only settings the workspace knows about are stored. */
	private static void mergeKnown(Map<String, Object> target, Map<String, Object> source) {
		source.forEach((k, v) -> {
			if (target.containsKey(k) && v != null) {
				target.put(k, v);
			}
		});
	}

	private static boolean hasSecretKey(Object node) {
		if (node instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				String k = String.valueOf(e.getKey()).replaceAll("[^A-Za-z]", "").toLowerCase();
				if (k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("apikey")) {
					return true;
				}
				if (hasSecretKey(e.getValue())) {
					return true;
				}
			}
		} else if (node instanceof List<?> list) {
			for (Object o : list) {
				if (hasSecretKey(o)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Map<String, Object> defaultUserPrefs() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("presence", "AVAILABLE");
		m.put("line", "MAIN");
		m.put("queues", List.of("Front desk"));
		m.put("ringApp", true);
		m.put("ringDesk", false);
		m.put("extension", "");
		m.put("ringMobile", false);
		m.put("mobileNumber", "");
		m.put("mobileAfterRings", 4);
		m.put("greeting", "STANDARD");
		m.put("emailVoicemail", true);
		return m;
	}

	private static Map<String, Object> defaultPractice() {
		Map<String, Object> m = new LinkedHashMap<>();
		List<Map<String, Object>> hours = new ArrayList<>();
		for (String d : List.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")) {
			boolean weekday = !d.startsWith("S");
			hours.add(new LinkedHashMap<>(Map.of("day", d, "open", weekday, "from", "08:00", "to", "17:00")));
		}
		m.put("hours", hours);
		m.put("afterHours", "ANSWERING_SERVICE");
		m.put("answeringServiceNumber", "");
		m.put("holidayDate", "");
		m.put("practiceName", "");
		m.put("greeting", "Thank you for calling. If this is a medical emergency, hang up and dial 911.");
		m.put("afterHoursGreeting", "You've reached us after hours. Our office is closed. If this is an emergency, hang up and dial 911.");
		m.put("menu", List.of(
				new LinkedHashMap<>(Map.of("key", "1", "label", "Schedule or change an appointment", "dest", "Front desk")),
				new LinkedHashMap<>(Map.of("key", "2", "label", "Prescription refills", "dest", "Clinical")),
				new LinkedHashMap<>(Map.of("key", "3", "label", "Billing and insurance", "dest", "Billing")),
				new LinkedHashMap<>(Map.of("key", "0", "label", "Speak with the front desk", "dest", "Front desk"))));
		m.put("noInputDest", "Front desk");
		m.put("queues", List.of(
				new LinkedHashMap<>(Map.of("name", "Front desk", "members", List.of(), "strategy", "ALL", "waitSeconds", 120, "overflow", "VOICEMAIL")),
				new LinkedHashMap<>(Map.of("name", "Clinical", "members", List.of(), "strategy", "ROUND_ROBIN", "waitSeconds", 120, "overflow", "VOICEMAIL")),
				new LinkedHashMap<>(Map.of("name", "Billing", "members", List.of(), "strategy", "LONGEST_IDLE", "waitSeconds", 300, "overflow", "CALLBACK"))));
		m.put("recordAll", false);
		m.put("recordingNotice", true);
		m.put("recordingRetentionDays", 90);
		m.put("lines", List.of(
				new LinkedHashMap<>(Map.of("id", "MAIN", "label", "Main line", "number", "")),
				new LinkedHashMap<>(Map.of("id", "REFERRAL", "label", "Referral line", "number", ""))));
		m.put("faxNumber", "");
		return m;
	}

	// ── contacts ────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<PhoneContact> contacts(UserPrincipal principal, String q, String group) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase();
		String digits = CrmSupport.normalizePhone(q);
		List<PhoneContact> out = new ArrayList<>();
		String g = group == null ? "ALL" : group.toUpperCase();

		if (g.equals("ALL") || g.equals("PATIENTS")) {
			for (Patient p : patients.findByOrganizationIdOrderByCreatedAtDesc(orgId)) {
				if (p.getPhone() == null && p.getAltPhone() == null) {
					continue;
				}
				out.add(new PhoneContact(p.isLead() ? "LEAD" : "PATIENT", p.getId(), p.getFullName(),
						(p.isLead() ? "Patient lead" : "Patient") + (p.getMrn() == null ? "" : " · MRN " + p.getMrn()),
						p.getPhone() != null ? p.getPhone() : p.getAltPhone(), null, p.getEmail(),
						p.isConsentSms() && !p.isDoNotContact(), "/app/crm/patients/" + p.getId()));
			}
		}
		if (g.equals("ALL") || g.equals("REFERRING")) {
			for (ReferralPartner r : support.partners().values()) {
				out.add(new PhoneContact("PARTNER", r.getId(), r.getName(),
						r.getSpecialty() == null ? "Referring office" : r.getSpecialty(), r.getPhone(), r.getFax(), r.getEmail(),
						false, "/app/crm/referrals?tab=partners"));
			}
		}
		if (g.equals("ALL") || g.equals("STAFF")) {
			for (StaffMember s : staff.findByOrganizationIdOrderByLastNameAscFirstNameAsc(orgId)) {
				String phone = firstNonBlank(s.getWorkPhone(), s.getMobilePhone(), s.getPhone());
				String title = s.getJobTitle() == null ? "Staff" : s.getJobTitle();
				out.add(new PhoneContact("STAFF", s.getId(), s.isProvider() ? CallFlowService.providerName(s) : CrmSupport.joinName(s.getFirstName(), s.getLastName()),
						title + (s.getDepartment() == null ? "" : " · " + s.getDepartment()), phone, null, s.getEmail(), false, null));
			}
		}
		return out.stream()
				.filter(c -> needle == null
						|| c.name().toLowerCase().contains(needle)
						|| (c.subtitle() != null && c.subtitle().toLowerCase().contains(needle))
						|| (digits != null && digits.length() >= 3 && c.phone() != null
								&& String.valueOf(CrmSupport.normalizePhone(c.phone())).contains(digits)))
				.sorted(Comparator.comparing(PhoneContact::name, String.CASE_INSENSITIVE_ORDER))
				.limit(300)
				.toList();
	}

	// ── texts ───────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<ThreadSummary> threads(UserPrincipal principal) {
		support.requireAccess(principal);
		UUID orgId = support.orgId();
		Map<String, List<PhoneMessage>> byPhone = messages.findByOrganizationIdOrderByCreatedAtDesc(orgId).stream()
				.collect(Collectors.groupingBy(PhoneMessage::getPhoneNormalized, LinkedHashMap::new, Collectors.toList()));
		List<ThreadSummary> out = new ArrayList<>();
		for (Map.Entry<String, List<PhoneMessage>> e : byPhone.entrySet()) {
			out.add(summary(e.getKey(), e.getValue()));
		}
		return out;
	}

	@Transactional(readOnly = true)
	public ThreadResponse thread(UserPrincipal principal, String phone) {
		support.requireAccess(principal);
		String normalized = requirePhone(phone);
		List<PhoneMessage> list = messages.findByOrganizationIdAndPhoneNormalizedOrderByCreatedAtAsc(support.orgId(), normalized);
		List<PhoneMessage> newestFirst = new ArrayList<>(list);
		java.util.Collections.reverse(newestFirst);
		ThreadSummary s = list.isEmpty() ? summary(normalized, List.of(), phone) : summary(normalized, newestFirst);
		return new ThreadResponse(s, list.stream().map(PhoneService::toMessage).toList());
	}

	@Transactional
	public void markRead(UserPrincipal principal, String phone) {
		support.requireAccess(principal);
		String normalized = requirePhone(phone);
		List<PhoneMessage> list = messages.findByOrganizationIdAndPhoneNormalizedOrderByCreatedAtAsc(support.orgId(), normalized);
		Instant now = Instant.now();
		list.stream().filter(m -> "IN".equals(m.getDirection()) && m.getReadAt() == null).forEach(m -> m.setReadAt(now));
		messages.saveAll(list);
	}

	@Transactional
	public MessageResponse sendText(UserPrincipal principal, SendTextRequest r) {
		support.requireAccess(principal);
		String normalized = requirePhone(r.phone());
		if (normalized.length() != 10) {
			throw ApiException.badRequest("Enter a 10-digit mobile number.");
		}
		Patient patient = r.patientId() != null ? support.requirePatient(r.patientId()) : matchPatient(normalized);
		String blocked = blockedReason(patient, normalized);
		if (blocked != null) {
			throw ApiException.badRequest(blocked);
		}
		PhoneMessage m = new PhoneMessage();
		m.setPatientId(patient == null ? null : patient.getId());
		m.setPhone(r.phone().trim());
		m.setPhoneNormalized(normalized);
		m.setContactName(patient != null ? patient.getFullName() : CrmSupport.clean(r.contactName()));
		m.setDirection("OUT");
		m.setBody(r.body().trim());
		m.setStatus("QUEUED");
		m.setLine(CrmSupport.clean(r.line()));
		m.setSentByUserId(principal.userId());
		m.setSentByName(support.actorName(principal));
		PhoneMessage saved = messages.save(m);
		if (patient != null) {
			Activity a = new Activity();
			a.setPatientId(patient.getId());
			a.setActivityType(ActivityType.SMS);
			a.setSubject("Text message queued");
			a.setBody(saved.getBody());
			a.setPhone(saved.getPhone());
			a.setActorUserId(principal.userId());
			a.setActorName(saved.getSentByName());
			support.save(a);
		}
		return toMessage(saved);
	}

	private ThreadSummary summary(String normalized, List<PhoneMessage> newestFirst) {
		return summary(normalized, newestFirst, null);
	}

	private ThreadSummary summary(String normalized, List<PhoneMessage> newestFirst, String rawPhone) {
		PhoneMessage last = newestFirst.isEmpty() ? null : newestFirst.get(0);
		UUID patientId = newestFirst.stream().map(PhoneMessage::getPatientId).filter(java.util.Objects::nonNull).findFirst().orElse(null);
		Patient patient = patientId != null ? patients.findById(patientId).orElse(null) : matchPatient(normalized);
		String name = patient != null ? patient.getFullName()
				: newestFirst.stream().map(PhoneMessage::getContactName).filter(java.util.Objects::nonNull).findFirst().orElse(null);
		long unread = newestFirst.stream().filter(m -> "IN".equals(m.getDirection()) && m.getReadAt() == null).count();
		return new ThreadSummary(normalized, last != null ? last.getPhone() : (rawPhone == null ? normalized : rawPhone), name,
				patient == null ? null : patient.getId(), patient == null ? null : (patient.isLead() ? "LEAD" : "PATIENT"),
				last == null ? null : last.getBody(), last == null ? null : last.getCreatedAt(),
				last == null ? null : last.getDirection(), last == null ? null : last.getStatus(), unread,
				blockedReason(patient, normalized));
	}

	private String blockedReason(Patient patient, String normalized) {
		boolean optedOut = messages.findByOrganizationIdAndPhoneNormalizedOrderByCreatedAtAsc(support.orgId(), normalized).stream()
				.filter(m -> "IN".equals(m.getDirection()))
				.reduce((a, b) -> b)
				.map(m -> m.getBody().trim().equalsIgnoreCase("STOP"))
				.orElse(false);
		if (optedOut) {
			return "This number replied STOP and can't be texted. Call instead.";
		}
		if (patient != null && patient.isDoNotContact()) {
			return "Do-not-contact is set on " + patient.getFullName() + "'s record.";
		}
		if (patient != null && !patient.isConsentSms()) {
			return patient.getFullName() + " hasn't agreed to texts. Record SMS consent on their profile first.";
		}
		return null;
	}

	private Patient matchPatient(String normalized) {
		List<Patient> found = patients.findByPhone(support.orgId(), normalized);
		return found.size() == 1 ? found.get(0) : null;
	}

	private static MessageResponse toMessage(PhoneMessage m) {
		return new MessageResponse(m.getId(), m.getDirection(), m.getBody(), m.getStatus(), m.getSentByName(), m.getLine(), m.getCreatedAt());
	}

	// ── voicemail ───────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<VoicemailResponse> voicemails(UserPrincipal principal) {
		support.requireAccess(principal);
		List<Voicemail> list = voicemails.findByOrganizationIdOrderByReceivedAtDesc(support.orgId());
		Map<UUID, Patient> ps = support.patients(list.stream().map(Voicemail::getPatientId).toList());
		return list.stream().map(v -> toVoicemail(v, ps.get(v.getPatientId()))).toList();
	}

	@Transactional
	public VoicemailResponse logVoicemail(UserPrincipal principal, LogVoicemailRequest r) {
		support.requireAccess(principal);
		String normalized = requirePhone(r.fromPhone());
		Patient patient = matchPatient(normalized);
		Voicemail v = new Voicemail();
		v.setPatientId(patient == null ? null : patient.getId());
		v.setFromPhone(r.fromPhone().trim());
		v.setPhoneNormalized(normalized);
		v.setCallerName(patient != null ? patient.getFullName() : CrmSupport.clean(r.callerName()));
		v.setMailbox(CrmSupport.clean(r.mailbox()) == null ? "Main line" : r.mailbox().trim());
		v.setDurationSeconds(r.durationSeconds());
		v.setTranscript(CrmSupport.clean(r.transcript()));
		v.setReceivedAt(r.receivedAt() == null ? Instant.now() : r.receivedAt());
		Voicemail saved = voicemails.save(v);
		if (patient != null) {
			support.log(principal, ActivityType.SYSTEM, patient.getId(), null, null, "Voicemail left on " + saved.getMailbox(), saved.getTranscript());
		}
		return toVoicemail(saved, patient);
	}

	@Transactional
	public VoicemailResponse setVoicemailStatus(UserPrincipal principal, UUID id, String status) {
		support.requireAccess(principal);
		Voicemail v = voicemails.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Voicemail not found."));
		String s = status == null ? "" : status.toUpperCase();
		if (!Set.of("NEW", "HEARD", "HANDLED").contains(s)) {
			throw ApiException.badRequest("Unknown voicemail status.");
		}
		v.setStatus(s);
		if ("HANDLED".equals(s)) {
			v.setHandledAt(Instant.now());
			v.setHandledByName(support.actorName(principal));
		} else {
			v.setHandledAt(null);
			v.setHandledByName(null);
		}
		voicemails.save(v);
		return toVoicemail(v, v.getPatientId() == null ? null : patients.findById(v.getPatientId()).orElse(null));
	}

	private static VoicemailResponse toVoicemail(Voicemail v, Patient p) {
		return new VoicemailResponse(v.getId(), v.getPatientId(), p == null ? null : p.getFullName(),
				p == null ? null : (p.isLead() ? "LEAD" : "PATIENT"), v.getFromPhone(), v.getCallerName(), v.getMailbox(),
				v.getDurationSeconds(), v.getTranscript(), v.getRecordingUrl(), v.getReceivedAt(), v.getStatus(),
				v.getHandledByName(), v.getHandledAt());
	}

	// ── fax ─────────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public List<FaxResponse> faxes(UserPrincipal principal) {
		support.requireAccess(principal);
		List<FaxSummary> list = faxes.findByOrganizationIdOrderByCreatedAtDesc(support.orgId());
		Map<UUID, Patient> ps = support.patients(list.stream().map(FaxSummary::getPatientId).toList());
		return list.stream().map(f -> toFax(f, ps.get(f.getPatientId()))).toList();
	}

	/** Sends (queues) an outbound fax, or logs a received/scanned fax when direction = IN. */
	@Transactional
	public FaxResponse createFax(
			UserPrincipal principal,
			String direction,
			String faxNumber,
			String partyName,
			UUID patientId,
			String subject,
			String documentType,
			boolean coverPage,
			String coverNote,
			MultipartFile file
	) {
		support.requireAccess(principal);
		String dir = "IN".equalsIgnoreCase(direction) ? "IN" : "OUT";
		String normalized = CrmSupport.normalizePhone(faxNumber);
		if (normalized == null || normalized.length() != 10) {
			throw ApiException.badRequest("Enter a 10-digit fax number.");
		}
		if (file == null || file.isEmpty()) {
			throw ApiException.badRequest("Attach the document (PDF, TIFF, JPG or PNG).");
		}
		if (file.getSize() > MAX_FAX_BYTES) {
			throw ApiException.badRequest("Fax documents can be up to 10 MB.");
		}
		String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
		if (!FAX_TYPES.contains(type)) {
			throw ApiException.badRequest("Fax documents must be PDF, TIFF, JPG or PNG.");
		}
		if (patientId != null) {
			support.requirePatient(patientId);
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (IOException ex) {
			throw ApiException.badRequest("The document could not be read.");
		}
		Fax f = new Fax();
		f.setDirection(dir);
		f.setFaxNumber(faxNumber.trim());
		f.setPartyName(CrmSupport.clean(partyName));
		f.setPatientId(patientId);
		f.setSubject(CrmSupport.clean(subject));
		f.setDocumentType(CrmSupport.clean(documentType));
		f.setCoverPage("OUT".equals(dir) && coverPage);
		f.setCoverNote(CrmSupport.clean(coverNote));
		f.setStatus("OUT".equals(dir) ? "QUEUED" : (patientId != null ? "FILED" : "RECEIVED"));
		f.setFileName(CrmSupport.truncate(file.getOriginalFilename(), 255));
		f.setContentType(type);
		f.setFileSize((int) file.getSize());
		f.setFileData(bytes);
		f.setPages(countPages(bytes, type) + (f.isCoverPage() ? 1 : 0));
		f.setCreatedByName(support.actorName(principal));
		if ("FILED".equals(f.getStatus())) {
			f.setFiledAt(Instant.now());
			f.setFiledByName(f.getCreatedByName());
		}
		Fax saved = faxes.save(f);
		if (patientId != null) {
			support.log(principal, ActivityType.SYSTEM, patientId, null, null,
					("OUT".equals(dir) ? "Fax queued to " : "Fax received from ")
							+ (saved.getPartyName() == null ? saved.getFaxNumber() : saved.getPartyName())
							+ (saved.getDocumentType() == null ? "" : " · " + saved.getDocumentType()),
					saved.getSubject());
		}
		return faxResponse(saved);
	}

	@Transactional
	public FaxResponse fileFax(UserPrincipal principal, UUID id, FileFaxRequest r) {
		support.requireAccess(principal);
		Fax f = requireFax(id);
		if (!"IN".equals(f.getDirection())) {
			throw ApiException.badRequest("Only received faxes are filed to a chart.");
		}
		Patient p = support.requirePatient(r.patientId());
		f.setPatientId(p.getId());
		f.setDocumentType(CrmSupport.clean(r.documentType()));
		f.setStatus("FILED");
		f.setFiledAt(Instant.now());
		f.setFiledByName(support.actorName(principal));
		faxes.save(f);
		support.log(principal, ActivityType.SYSTEM, p.getId(), null, null,
				"Fax filed: " + (f.getDocumentType() == null ? "Document" : f.getDocumentType())
						+ " from " + (f.getPartyName() == null ? f.getFaxNumber() : f.getPartyName()), f.getSubject());
		return faxResponse(f);
	}

	@Transactional
	public FaxResponse forwardFax(UserPrincipal principal, UUID id, ForwardFaxRequest r) {
		support.requireAccess(principal);
		Fax src = requireFax(id);
		String normalized = CrmSupport.normalizePhone(r.faxNumber());
		if (normalized == null || normalized.length() != 10) {
			throw ApiException.badRequest("Enter a 10-digit fax number.");
		}
		Fax f = new Fax();
		f.setDirection("OUT");
		f.setFaxNumber(r.faxNumber().trim());
		f.setPartyName(CrmSupport.clean(r.partyName()));
		f.setPatientId(src.getPatientId());
		f.setSubject(src.getSubject() == null ? "Forwarded fax" : "Fwd: " + src.getSubject());
		f.setDocumentType(src.getDocumentType());
		f.setStatus("QUEUED");
		f.setFileName(src.getFileName());
		f.setContentType(src.getContentType());
		f.setFileSize(src.getFileSize());
		f.setFileData(src.getFileData());
		f.setPages(src.getPages());
		f.setCreatedByName(support.actorName(principal));
		return faxResponse(faxes.save(f));
	}

	@Transactional(readOnly = true)
	public Fax faxFile(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Fax f = requireFax(id);
		if (f.getFileData() == null) {
			throw ApiException.notFound("This fax has no document.");
		}
		return f;
	}

	private Fax requireFax(UUID id) {
		return faxes.findByIdAndOrganizationId(id, support.orgId()).orElseThrow(() -> ApiException.notFound("Fax not found."));
	}

	private FaxResponse faxResponse(Fax f) {
		Patient p = f.getPatientId() == null ? null : patients.findById(f.getPatientId()).orElse(null);
		return new FaxResponse(f.getId(), f.getDirection(), f.getFaxNumber(), f.getPartyName(), f.getPatientId(),
				p == null ? null : p.getFullName(), f.getSubject(), f.getDocumentType(), f.getPages(), f.getStatus(),
				f.getFileName(), f.getContentType(), f.getFileSize(), f.isCoverPage(), f.getCoverNote(), f.getFiledByName(),
				f.getFiledAt(), f.getCreatedByName(), f.getCreatedAt());
	}

	private static FaxResponse toFax(FaxSummary f, Patient p) {
		return new FaxResponse(f.getId(), f.getDirection(), f.getFaxNumber(), f.getPartyName(), f.getPatientId(),
				p == null ? null : p.getFullName(), f.getSubject(), f.getDocumentType(), f.getPages(), f.getStatus(),
				f.getFileName(), f.getContentType(), f.getFileSize(), f.isCoverPage(), f.getCoverNote(), f.getFiledByName(),
				f.getFiledAt(), f.getCreatedByName(), f.getCreatedAt());
	}

	private static int countPages(byte[] bytes, String type) {
		if (!"application/pdf".equals(type)) {
			return 1;
		}
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		Matcher m = Pattern.compile("/Type\\s*/Page[^s]").matcher(text);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return Math.max(1, n);
	}

	// ── helpers ─────────────────────────────────────────────────────────

	private static String requirePhone(String phone) {
		String n = CrmSupport.normalizePhone(phone);
		if (n == null || n.length() < 7) {
			throw ApiException.badRequest("Enter a valid phone number.");
		}
		return n;
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}

	private static String stringOf(Object o) {
		return o == null ? null : String.valueOf(o);
	}
}
