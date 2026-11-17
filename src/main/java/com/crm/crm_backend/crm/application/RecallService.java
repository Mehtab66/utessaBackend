package com.crm.crm_backend.crm.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentRequest;
import com.crm.crm_backend.crm.api.dto.AppointmentDtos.AppointmentResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallAttemptRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallImportRequest;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallImportResponse;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallImportRow;
import com.crm.crm_backend.crm.api.dto.CallFlowDtos.RecallResponse;
import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.Recall;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.RecallRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/** No-shows & cancellations call list: build it (automatically or from a file), work it, rebook. */
@Service
public class RecallService {

	private static final int MAX_IMPORT_ROWS = 5000;

	private final RecallRepository repository;
	private final PatientRepository patientRepository;
	private final AppointmentRepository appointmentRepository;
	private final AppointmentService appointmentService;
	private final CrmSupport support;

	public RecallService(
			RecallRepository repository,
			PatientRepository patientRepository,
			AppointmentRepository appointmentRepository,
			AppointmentService appointmentService,
			CrmSupport support
	) {
		this.repository = repository;
		this.patientRepository = patientRepository;
		this.appointmentRepository = appointmentRepository;
		this.appointmentService = appointmentService;
		this.support = support;
	}

	@Transactional(readOnly = true)
	public List<RecallResponse> list(UserPrincipal principal) {
		support.requireAccess(principal);
		List<Recall> rows = repository.findByOrganizationIdOrderByMissedAtDesc(support.orgId());
		Map<UUID, Patient> patients = support.patients(rows.stream().map(Recall::getPatientId).toList());
		Map<UUID, Appointment> rebooked = appointmentRepository.findAllById(rows.stream()
						.map(Recall::getRebookedAppointmentId).filter(java.util.Objects::nonNull).toList())
				.stream().collect(Collectors.toMap(Appointment::getId, Function.identity()));
		return rows.stream().map(r -> toResponse(r, patients.get(r.getPatientId()), rebooked.get(r.getRebookedAppointmentId()))).toList();
	}

	@Transactional
	public RecallResponse attempt(UserPrincipal principal, UUID id, RecallAttemptRequest req) {
		support.requireAccess(principal);
		Recall recall = require(id);
		Patient patient = support.requirePatient(recall.getPatientId());
		String outcome = req.outcome().trim().toUpperCase();
		ZoneId zone = ZoneId.systemDefault();
		AppointmentResponse booked = null;
		String outcomeLabel;
		switch (outcome) {
			case "REBOOKED" -> {
				if (req.newStartsAt() == null) {
					throw ApiException.badRequest("Choose the new visit date and time.");
				}
				String provider = CrmSupport.clean(req.providerName()) != null ? req.providerName().trim() : recall.getProviderName();
				booked = appointmentService.create(principal, new AppointmentRequest(patient.getId(), null, null, null,
						req.appointmentType() == null ? "FOLLOW_UP" : req.appointmentType(), "IN_PERSON", null,
						req.newStartsAt(), 30, provider, null, principal.userId(), null,
						"Rebooked from missed visit " + LocalDate.ofInstant(recall.getMissedAt(), zone)));
				recall.setStatus(Recall.Status.REBOOKED);
				recall.setRebookedAppointmentId(booked.id());
				recall.setNextAttemptOn(null);
				outcomeLabel = "Rebooked";
			}
			case "NOT_REACHED", "LEFT_VOICEMAIL" -> {
				recall.setStatus(Recall.Status.NOT_REACHED);
				recall.setNextAttemptOn(req.tryAgainOn() != null ? req.tryAgainOn() : nextBusinessDay(LocalDate.now(zone)));
				outcomeLabel = "LEFT_VOICEMAIL".equals(outcome) ? "Left voicemail" : "Not reached";
			}
			case "CLOSED" -> {
				if (CrmSupport.clean(req.closeReason()) == null) {
					throw ApiException.badRequest("Choose why this visit won't be rebooked.");
				}
				recall.setStatus(Recall.Status.CLOSED);
				recall.setCloseReason(req.closeReason().trim());
				recall.setNextAttemptOn(null);
				outcomeLabel = "Closed · " + req.closeReason().trim();
			}
			default -> throw ApiException.badRequest("Unknown call outcome.");
		}
		recall.setAttempts(recall.getAttempts() + 1);
		recall.setLastOutcome(outcomeLabel);
		recall.setLastAttemptAt(Instant.now());
		if (CrmSupport.clean(req.notes()) != null) {
			recall.setNotes(recall.getNotes() == null ? req.notes().trim() : recall.getNotes() + "\n" + req.notes().trim());
		}
		repository.save(recall);

		Activity call = new Activity();
		call.setPatientId(patient.getId());
		call.setActivityType(ActivityType.CALL_OUTBOUND);
		call.setSubject("Recall call · " + outcomeLabel);
		call.setBody(CrmSupport.clean(req.notes()));
		call.setPhone(patient.getPhone());
		call.setCallOutcome(outcomeLabel);
		call.setCallReason("Rebook missed visit");
		call.setCallerName(patient.getFullName());
		call.setCallerType(patient.isLead() ? "LEAD" : "PATIENT");
		call.setDurationSeconds(req.durationSeconds());
		call.setActorUserId(principal.userId());
		call.setActorName(support.actorName(principal));
		support.save(call);
		patient.setLastContactAt(call.getOccurredAt());
		patient.setCallOutcome(outcomeLabel);
		patientRepository.save(patient);

		Appointment rebooked = booked == null ? null : appointmentRepository.findById(booked.id()).orElse(null);
		return toResponse(recall, patient, rebooked);
	}

	@Transactional
	public RecallResponse reopen(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		Recall recall = require(id);
		recall.setStatus(Recall.Status.TO_CALL);
		recall.setCloseReason(null);
		recall.setNextAttemptOn(LocalDate.now());
		repository.save(recall);
		return toResponse(recall, patientRepository.findById(recall.getPatientId()).orElse(null), null);
	}

	@Transactional
	public void delete(UserPrincipal principal, UUID id) {
		support.requireAccess(principal);
		repository.delete(require(id));
	}

	@Transactional
	public RecallImportResponse importRows(UserPrincipal principal, RecallImportRequest req) {
		support.requireAccess(principal);
		if (req.rows().size() > MAX_IMPORT_ROWS) {
			throw ApiException.badRequest("Up to " + MAX_IMPORT_ROWS + " rows per file.");
		}
		String source = "EHR".equalsIgnoreCase(req.source()) ? "EHR" : "UPLOAD";
		UUID orgId = support.orgId();
		List<Patient> everyone = patientRepository.findByOrganizationIdOrderByCreatedAtDesc(orgId);
		int added = 0;
		int linked = 0;
		int created = 0;
		int dupes = 0;
		int invalid = 0;
		List<String> messages = new ArrayList<>();
		int rowNo = 1;
		for (RecallImportRow row : req.rows()) {
			rowNo++;
			String first = CrmSupport.clean(row.firstName());
			String last = CrmSupport.clean(row.lastName());
			if (first == null || last == null || row.missedAt() == null
					|| (CrmSupport.normalizePhone(row.phone()) == null && row.dateOfBirth() == null)) {
				invalid++;
				if (messages.size() < 20) {
					messages.add("Row " + rowNo + ": needs first name, last name, visit date and a phone or date of birth.");
				}
				continue;
			}
			Optional<Patient> match = findMatch(everyone, row);
			Patient patient;
			if (match.isPresent()) {
				patient = match.get();
				linked++;
			} else {
				patient = new Patient();
				patient.setFirstName(first);
				patient.setLastName(last);
				patient.setFullName(CrmSupport.joinName(first, last));
				patient.setDateOfBirth(row.dateOfBirth());
				patient.setPhone(CrmSupport.clean(row.phone()));
				patient.setPhoneNormalized(CrmSupport.normalizePhone(row.phone()));
				patient.setMrn(CrmSupport.clean(row.mrn()));
				patient.setLifecycleStage(Lifecycle.PATIENT);
				patient.setLeadStatus(LeadStatus.CONVERTED);
				patient.setPriority(Priority.MEDIUM);
				patient.setSource("Recall import");
				patient.setPreferredProvider(CrmSupport.clean(row.providerName()));
				patient.setOwnerUserId(principal.userId());
				patient.setOwnerName(support.actorName(principal));
				patient.setCreatedByUserId(principal.userId());
				patient = patientRepository.save(patient);
				patient.setConvertedAt(patient.getCreatedAt());
				everyone.add(patient);
				created++;
			}
			if (repository.findByOrganizationIdAndPatientIdAndMissedAt(orgId, patient.getId(), row.missedAt()).isPresent()) {
				dupes++;
				continue;
			}
			Recall recall = new Recall();
			recall.setPatientId(patient.getId());
			recall.setMissedAt(row.missedAt());
			String status = row.status() == null ? "" : row.status().toLowerCase();
			recall.setMissType(status.contains("cancel") ? "CANCELLED" : "NO_SHOW");
			recall.setVisitType(CrmSupport.clean(row.visitType()));
			recall.setProviderName(CrmSupport.clean(row.providerName()));
			recall.setSource(source);
			recall.setNextAttemptOn(LocalDate.now());
			repository.save(recall);
			added++;
		}
		if (added > 0) {
			support.log(principal, ActivityType.SYSTEM, null, null, null,
					"Imported " + added + " missed/cancelled visit" + (added == 1 ? "" : "s") + " to the call list", null);
		}
		return new RecallImportResponse(added, linked, created, dupes, invalid, messages);
	}

	// ── internals ───────────────────────────────────────────────────────

	private Optional<Patient> findMatch(List<Patient> everyone, RecallImportRow row) {
		String mrn = CrmSupport.clean(row.mrn());
		if (mrn != null) {
			Optional<Patient> byMrn = everyone.stream().filter(p -> mrn.equalsIgnoreCase(p.getMrn())).findFirst();
			if (byMrn.isPresent()) {
				return byMrn;
			}
		}
		String first = row.firstName().trim().toLowerCase();
		String last = row.lastName().trim().toLowerCase();
		if (row.dateOfBirth() != null) {
			Optional<Patient> byDob = everyone.stream()
					.filter(p -> row.dateOfBirth().equals(p.getDateOfBirth()))
					.filter(p -> last.equalsIgnoreCase(p.getLastName()) && first.equalsIgnoreCase(p.getFirstName()))
					.findFirst();
			if (byDob.isPresent()) {
				return byDob;
			}
		}
		String phone = CrmSupport.normalizePhone(row.phone());
		if (phone != null) {
			return everyone.stream()
					.filter(p -> phone.equals(p.getPhoneNormalized()) || phone.equals(p.getAltPhoneNormalized()))
					.filter(p -> last.equalsIgnoreCase(p.getLastName()))
					.findFirst();
		}
		return Optional.empty();
	}

	private Recall require(UUID id) {
		return repository.findByIdAndOrganizationId(id, support.orgId())
				.orElseThrow(() -> ApiException.notFound("Call-list entry not found."));
	}

	private static LocalDate nextBusinessDay(LocalDate d) {
		LocalDate n = d.plusDays(1);
		while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) {
			n = n.plusDays(1);
		}
		return n;
	}

	private RecallResponse toResponse(Recall r, Patient p, Appointment rebooked) {
		return new RecallResponse(
				r.getId(), r.getPatientId(),
				p == null ? null : p.getFullName(), p == null ? null : p.getPhone(), p == null ? null : p.getDateOfBirth(),
				p == null ? null : p.getMrn(), p != null && p.isConsentSms() && !p.isDoNotContact(),
				r.getAppointmentId(), r.getMissedAt(), r.getMissType(), r.getVisitType(), r.getProviderName(), r.getSource(),
				r.getStatus().name(), r.getAttempts(), r.getLastOutcome(), r.getLastAttemptAt(), r.getNextAttemptOn(),
				r.getRebookedAppointmentId(), rebooked == null ? null : rebooked.getStartsAt(), r.getCloseReason(),
				r.getNotes(), r.getCreatedAt());
	}
}
