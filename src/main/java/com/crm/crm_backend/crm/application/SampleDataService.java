package com.crm.crm_backend.crm.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.crm.domain.Activity;
import com.crm.crm_backend.crm.domain.ActivityType;
import com.crm.crm_backend.crm.domain.Appointment;
import com.crm.crm_backend.crm.domain.AppointmentStatus;
import com.crm.crm_backend.crm.domain.CrmTarget;
import com.crm.crm_backend.crm.domain.CrmTask;
import com.crm.crm_backend.crm.domain.Fax;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.crm.domain.Opportunity;
import com.crm.crm_backend.crm.domain.OpportunityStage;
import com.crm.crm_backend.crm.domain.Patient;
import com.crm.crm_backend.crm.domain.PhoneConfig;
import com.crm.crm_backend.crm.domain.PhoneMessage;
import com.crm.crm_backend.crm.domain.Priority;
import com.crm.crm_backend.crm.domain.Recall;
import com.crm.crm_backend.crm.domain.Referral;
import com.crm.crm_backend.crm.domain.ReferralPartner;
import com.crm.crm_backend.crm.domain.ReferralStatus;
import com.crm.crm_backend.crm.domain.TaskStatus;
import com.crm.crm_backend.crm.domain.Voicemail;
import com.crm.crm_backend.crm.infrastructure.ActivityRepository;
import com.crm.crm_backend.crm.infrastructure.AppointmentRepository;
import com.crm.crm_backend.crm.infrastructure.CrmTargetRepository;
import com.crm.crm_backend.crm.infrastructure.CrmTaskRepository;
import com.crm.crm_backend.crm.infrastructure.FaxRepository;
import com.crm.crm_backend.crm.infrastructure.MessageRepository;
import com.crm.crm_backend.crm.infrastructure.OpportunityRepository;
import com.crm.crm_backend.crm.infrastructure.PatientRepository;
import com.crm.crm_backend.crm.infrastructure.PhoneConfigRepository;
import com.crm.crm_backend.crm.infrastructure.RecallRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralPartnerRepository;
import com.crm.crm_backend.crm.infrastructure.ReferralRepository;
import com.crm.crm_backend.crm.infrastructure.VoicemailRepository;
import com.crm.crm_backend.operations.domain.EmploymentStatus;
import com.crm.crm_backend.operations.domain.StaffMember;
import com.crm.crm_backend.operations.infrastructure.StaffMemberRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * Sample data: fills one practice with a realistic, linked healthcare CRM dataset spread
 * over the last 90 days so every screen can be tried end to end. Every row created is recorded in
 * crm_sample_records so {@link #remove} deletes exactly that data and nothing else.
 * Fictional people only — all phone numbers use the 555 range.
 */
@Service
public class SampleDataService {

	private static final String PATIENT = "PATIENT";

	private final JdbcTemplate jdbc;
	@PersistenceContext
	private EntityManager entityManager;
	private final CrmSupport support;
	private final TaskService taskService;
	private final PatientRepository patients;
	private final ReferralPartnerRepository partners;
	private final ReferralRepository referrals;
	private final OpportunityRepository opportunities;
	private final AppointmentRepository appointments;
	private final CrmTaskRepository tasks;
	private final ActivityRepository activities;
	private final RecallRepository recalls;
	private final CrmTargetRepository targets;
	private final MessageRepository messages;
	private final VoicemailRepository voicemails;
	private final FaxRepository faxes;
	private final PhoneConfigRepository phoneConfigs;
	private final StaffMemberRepository staff;
	private final OrganizationRepository organizations;

	public SampleDataService(
			JdbcTemplate jdbc,
			CrmSupport support,
			TaskService taskService,
			PatientRepository patients,
			ReferralPartnerRepository partners,
			ReferralRepository referrals,
			OpportunityRepository opportunities,
			AppointmentRepository appointments,
			CrmTaskRepository tasks,
			ActivityRepository activities,
			RecallRepository recalls,
			CrmTargetRepository targets,
			MessageRepository messages,
			VoicemailRepository voicemails,
			FaxRepository faxes,
			PhoneConfigRepository phoneConfigs,
			StaffMemberRepository staff,
			OrganizationRepository organizations
	) {
		this.jdbc = jdbc;
		this.support = support;
		this.taskService = taskService;
		this.patients = patients;
		this.partners = partners;
		this.referrals = referrals;
		this.opportunities = opportunities;
		this.appointments = appointments;
		this.tasks = tasks;
		this.activities = activities;
		this.recalls = recalls;
		this.targets = targets;
		this.messages = messages;
		this.voicemails = voicemails;
		this.faxes = faxes;
		this.phoneConfigs = phoneConfigs;
		this.staff = staff;
		this.organizations = organizations;
	}

	public record Status(boolean loaded, Map<String, Integer> counts) {
	}

	@Transactional(readOnly = true)
	public Status status(UserPrincipal principal) {
		support.requireAccess(principal);
		Map<String, Integer> counts = new LinkedHashMap<>();
		jdbc.query("select entity_type, count(*) from crm_sample_records where organization_id = ? group by entity_type order by 1",
				rs -> {
					counts.put(rs.getString(1), rs.getInt(2));
				}, support.orgId());
		return new Status(!counts.isEmpty(), counts);
	}

	// ═════════════════════════════════════════════════════════════════════
	// Load
	// ═════════════════════════════════════════════════════════════════════

	@Transactional
	public Status load(UserPrincipal principal) {
		support.requireAdmin(principal);
		UUID orgId = support.orgId();
		Integer existing = jdbc.queryForObject("select count(*) from crm_sample_records where organization_id = ?", Integer.class, orgId);
		if (existing != null && existing > 0) {
			throw ApiException.conflict("Sample data is already loaded. Remove it first to load a fresh copy.");
		}
		Ctx c = new Ctx(principal);

		// ── providers (Operations → Providers) ──────────────────────────
		StaffMember singh = provider(c, "Amir", "Singh", "MD", "Cardiologist", "Cardiology", "(972) 555-0201");
		StaffMember shah = provider(c, "Anita", "Shah", "MD", "Internal medicine physician", "Internal medicine", "(972) 555-0202");
		StaffMember chen = provider(c, "Carmen", "Chen", "APRN", "Nurse practitioner", "Family medicine", "(972) 555-0203");
		String drSingh = CallFlowService.providerName(singh);
		String drShah = CallFlowService.providerName(shah);
		String npChen = CallFlowService.providerName(chen);

		// ── referral partners ───────────────────────────────────────────
		ReferralPartner plano = partner(c, "Plano Family Medicine", "PRACTICE", "ACTIVE", "Family medicine", "Karen Lopez", "(972) 555-0190", "(972) 555-0191", "1295837461", 80);
		ReferralPartner patel = partner(c, "Dr. Raj Patel", "PHYSICIAN", "ACTIVE", "Primary care", "Raj Patel, MD", "(469) 555-0310", "(469) 555-0311", "1487650329", 75);
		ReferralPartner baylor = partner(c, "Baylor Scott & White – Plano", "HOSPITAL", "ACTIVE", "Hospital discharge planning", "Monica Reyes", "(469) 555-0300", "(469) 555-0301", null, 85);
		ReferralPartner careNow = partner(c, "CareNow Urgent Care", "URGENT_CARE", "ACTIVE", "Urgent care", "Front desk", "(214) 555-0350", "(214) 555-0351", null, 60);
		ReferralPartner aetna = partner(c, "Aetna Care Management", "PAYER", "PROSPECT", "Care coordination", "Dana Whitfield", "(800) 555-0400", null, null, 40);

		// ── patients & leads ────────────────────────────────────────────
		Patient linda = person(c, "Linda", "Park", "1961-07-22", "(469) 555-0182", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.MEDIUM, "Existing patient referral", null, "Cardiology follow-up", drSingh, "Medicare Part B", true, 80, "100355", "Hypertension follow-up", null, "English");
		Patient robert = person(c, "Robert", "Chen", "1958-02-11", "(972) 555-0168", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.MEDIUM, "Referral", plano, "Echocardiogram", drShah, "Blue Cross Blue Shield", true, 70, "100377", "Shortness of breath on exertion", null, "English");
		Patient priya = person(c, "Priya", "Nair", "1990-05-03", "(214) 555-0131", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.MEDIUM, "Web form", plano, "New patient consult", drSingh, "Aetna", true, 45, "100482", "Palpitations", null, "English");
		Patient daniel = person(c, "Daniel", "Okoye", "1984-09-17", "(469) 555-0144", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.MEDIUM, "Google / search", null, "Sleep study", drShah, "UnitedHealthcare", false, 30, "100512", "Snoring and daytime fatigue", null, "English");
		Patient rosa = person(c, "Rosa", "Delgado", "1972-12-01", "(972) 555-0175", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.HIGH, "Phone call", null, "Diabetes management", npChen, "Cigna", true, 25, "100611", "A1C follow-up", null, "Spanish");
		Patient samuel = person(c, "Samuel", "Bennett", "1966-03-28", "(214) 555-0192", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.MEDIUM, "Referral", baylor, "Post-discharge follow-up", drSingh, "Humana", false, 20, "100720", "Follow-up after hospital stay", null, "English");
		Patient grace = person(c, "Grace", "Kim", "1995-08-09", "(469) 555-0156", Lifecycle.PATIENT, LeadStatus.CONVERTED, Priority.LOW, "Social media", null, "Annual physical", npChen, null, true, 12, null, "Annual physical", null, "English");
		grace.setCoverageType("SELF_PAY");
		samuel.setEhrSyncStatus(IntegrationService.SYNC_PENDING);
		grace.setEhrSyncStatus(IntegrationService.SYNC_PENDING);

		Patient marcus = person(c, "Marcus", "Johnson", "1979-04-14", "(214) 555-0111", Lifecycle.LEAD, LeadStatus.NEW, Priority.URGENT, "Phone call", null, "Cardiology", null, "UnitedHealthcare", false, 0, null, "Chest tightness when climbing stairs", null, "English");
		Patient aisha = person(c, "Aisha", "Rahman", "1988-11-30", "(972) 555-0122", Lifecycle.LEAD, LeadStatus.NEW, Priority.HIGH, "Web form", null, "Cardiology", null, "Aetna", true, 1, null, "Palpitations at night", null, "Urdu");
		Patient kevin = person(c, "Kevin", "O'Brien", "1969-01-19", "(469) 555-0133", Lifecycle.LEAD, LeadStatus.NEW, Priority.MEDIUM, "Google / search", null, "Annual physical", null, "Cigna", false, 2, null, "Wants a new primary care provider", null, "English");
		Patient elena = person(c, "Elena", "Petrova", "1981-06-25", "(214) 555-0145", Lifecycle.LEAD, LeadStatus.ATTEMPTING_CONTACT, Priority.MEDIUM, "Social media", null, "Weight management", null, null, true, 4, null, "Asked about the weight management program", null, "English");
		Patient james = person(c, "James", "Wright", "1955-10-10", "(972) 555-0157", Lifecycle.LEAD, LeadStatus.ATTEMPTING_CONTACT, Priority.HIGH, "Referral", patel, "Cardiology consult", null, "Medicare Part B", false, 3, null, "Uncontrolled hypertension", null, "English");
		Patient fatima = person(c, "Fatima", "Ali", "1993-02-02", "(469) 555-0166", Lifecycle.LEAD, LeadStatus.CONTACTED, Priority.MEDIUM, "Insurance directory", null, "Women’s health", null, "Blue Cross Blue Shield", true, 6, null, "Establish care", null, "Arabic");
		Patient tom = person(c, "Tom", "Alvarez", "1975-07-07", "(214) 555-0177", Lifecycle.LEAD, LeadStatus.CONTACTED, Priority.LOW, "Walk-in", null, "Flu shot", null, "Tricare", false, 9, null, "Vaccination question", null, "Spanish");
		Patient hannah = person(c, "Hannah", "Lee", "1987-03-15", "(972) 555-0189", Lifecycle.LEAD, LeadStatus.CONTACTED, Priority.HIGH, "Referral", plano, "Holter monitor", null, "Blue Cross Blue Shield", true, 5, null, "Dizziness and irregular heartbeat", null, "English");
		Patient victor = person(c, "Victor", "Ramos", "1963-09-21", "(469) 555-0199", Lifecycle.LEAD, LeadStatus.QUALIFIED, Priority.HIGH, "Referral", baylor, "Cardiology consult", drSingh, "Medicare Part B", false, 11, null, "Follow-up after heart failure admission", null, "Spanish");
		Patient mei = person(c, "Mei", "Wong", "1991-12-12", "(214) 555-0121", Lifecycle.LEAD, LeadStatus.QUALIFIED, Priority.MEDIUM, "Web form", null, "Annual physical", drShah, "Kaiser Permanente", true, 13, null, "Annual physical and labs", null, "Chinese");
		Patient carlos = person(c, "Carlos", "Mendez", "1970-05-05", "(972) 555-0132", Lifecycle.LEAD, LeadStatus.APPOINTMENT_SCHEDULED, Priority.HIGH, "Phone call", null, "Hypertension", drSingh, "Humana", true, 7, null, "High blood pressure readings at home", null, "Spanish");
		Patient sarah = person(c, "Sarah", "Miller", "1984-08-18", "(469) 555-0147", Lifecycle.LEAD, LeadStatus.APPOINTMENT_SCHEDULED, Priority.MEDIUM, "Referral", careNow, "New patient visit", drShah, "Aetna", true, 10, null, "Seen at urgent care for chest pain, told to follow up", null, "English");
		Patient ahmed = person(c, "Ahmed", "Hassan", "1977-01-29", "(214) 555-0154", Lifecycle.LEAD, LeadStatus.APPOINTMENT_SCHEDULED, Priority.URGENT, "Referral", patel, "Urgent cardiology evaluation", drSingh, "UnitedHealthcare", false, 3, null, "Abnormal ECG from primary care", null, "English");
		Patient olivia = person(c, "Olivia", "Turner", "1999-04-04", "(972) 555-0165", Lifecycle.LEAD, LeadStatus.SHOWED, Priority.MEDIUM, "Google / search", null, "Weight management", npChen, "Cigna", true, 18, null, "Weight management program", null, "English");
		Patient brian = person(c, "Brian", "Foster", "1960-06-16", "(469) 555-0176", Lifecycle.LEAD, LeadStatus.LOST, Priority.MEDIUM, "Referral", patel, "Ablation consult", null, "Medicare Part B", false, 35, null, "Atrial fibrillation", "Chose another provider", "English");
		Patient nina = person(c, "Nina", "Kowalski", "1986-10-08", "(214) 555-0187", Lifecycle.LEAD, LeadStatus.LOST, Priority.LOW, "Insurance directory", null, "Annual physical", null, "Oscar Health", false, 50, null, "Annual physical", "Insurance not accepted", "Polish");
		Patient paul = person(c, "Paul", "Grant", "1974-02-27", "(972) 555-0198", Lifecycle.LEAD, LeadStatus.NOT_QUALIFIED, Priority.LOW, "Other", aetna, "Bariatric surgery", null, "Aetna", false, 28, null, "Asked about bariatric surgery", "Service not offered", "English");
		elena.setCallOutcome("Voicemail");
		james.setCallOutcome("No answer");
		patients.saveAll(List.of(grace, samuel, elena, james));

		List<Patient> everyone = List.of(linda, robert, priya, daniel, rosa, samuel, grace, marcus, aisha, kevin, elena,
				james, fatima, tom, hannah, victor, mei, carlos, sarah, ahmed, olivia, brian, nina, paul);
		for (Patient p : everyone) {
			Instant created = p.getCreatedAt();
			activity(c, p, ActivityType.SYSTEM, (p.getLifecycleStage() == Lifecycle.PATIENT && p.getConvertedAt() != null
					&& !p.getConvertedAt().isAfter(created.plusSeconds(1)) ? "Patient added" : "Lead created") + " · source: " + p.getSource(),
					p.getReasonForVisit(), null, created);
			if (p.getLeadStatus() == LeadStatus.LOST || p.getLeadStatus() == LeadStatus.NOT_QUALIFIED) {
				activity(c, p, ActivityType.STATUS_CHANGE, "Status: Contacted → " + CrmSupport.humanize(p.getLeadStatus()),
						p.getLostReason(), null, created.plus(4, ChronoUnit.DAYS));
			}
		}

		// ── opportunities ───────────────────────────────────────────────
		opportunity(c, linda, "Cardiac rehab program", "Cardiology", OpportunityStage.TREATMENT_PRESENTED, 4200, drSingh, 14, null);
		opportunity(c, robert, "Echocardiogram + stress test", "Cardiology", OpportunityStage.WON, 1850, drShah, 30, 20);
		opportunity(c, priya, "New patient consult – palpitations", "Cardiology", OpportunityStage.WON, 350, drSingh, 44, 40);
		opportunity(c, daniel, "Sleep study", "Sleep medicine", OpportunityStage.WON, 1200, drShah, 25, 6);
		opportunity(c, victor, "Cardiology consult after admission", "Cardiology", OpportunityStage.QUALIFIED, 450, drSingh, 10, null);
		opportunity(c, mei, "Annual physical + labs", "Primary care", OpportunityStage.QUALIFIED, 300, drShah, 12, null);
		Opportunity carlosOpp = opportunity(c, carlos, "New patient consult – hypertension", "Cardiology", OpportunityStage.CONSULT_SCHEDULED, 400, drSingh, 6, null);
		Opportunity ahmedOpp = opportunity(c, ahmed, "Urgent cardiology evaluation", "Cardiology", OpportunityStage.CONSULT_SCHEDULED, 600, drSingh, 2, null);
		opportunity(c, olivia, "Weight management program", "Weight management", OpportunityStage.CONSULT_COMPLETED, 2400, npChen, 16, null);
		opportunity(c, hannah, "Holter monitor study", "Cardiology", OpportunityStage.INQUIRY, 750, null, 4, null);
		Opportunity brianOpp = opportunity(c, brian, "Ablation consult", "Electrophysiology", OpportunityStage.LOST, 8000, null, 33, 25);
		brianOpp.setLostReason("Chose another provider");
		opportunities.save(brianOpp);

		// ── referrals ───────────────────────────────────────────────────
		Referral jamesRef = referral(c, james, patel, ReferralStatus.RECEIVED, Priority.HIGH, "Cardiology consult", "I10", "Medicare Part B", null, 3, 1, null);
		referral(c, hannah, plano, ReferralStatus.INSURANCE_VERIFICATION, Priority.MEDIUM, "Holter monitor", "R42, R00.2", "Blue Cross Blue Shield", null, 5, -1, null);
		Referral victorRef = referral(c, victor, baylor, ReferralStatus.AUTHORIZATION_PENDING, Priority.HIGH, "Heart failure follow-up", "I50.9", "Medicare Part B", "PA-88213", 11, -2, null);
		Referral sarahRef = referral(c, sarah, careNow, ReferralStatus.SCHEDULED, Priority.MEDIUM, "Chest pain follow-up", "R07.9", "Aetna", null, 10, 7, null);
		Referral ahmedRef = referral(c, ahmed, patel, ReferralStatus.SCHEDULED, Priority.URGENT, "Abnormal ECG", "R94.31", "UnitedHealthcare", "UHC-55120", 3, 2, null);
		referral(c, paul, aetna, ReferralStatus.DECLINED, Priority.LOW, "Bariatric surgery evaluation", "E66.01", "Aetna", null, 28, 25, "Outside scope of practice");
		referral(c, priya, plano, ReferralStatus.COMPLETED, Priority.MEDIUM, "Palpitations", "R00.2", "Aetna", null, 46, 40, null);

		// ── appointments ────────────────────────────────────────────────
		Instant soon = nextHourFromNow(2);
		appointment(c, ahmed, ahmedOpp, ahmedRef, "NEW_PATIENT", "IN_PERSON", AppointmentStatus.CONFIRMED, soon, 40, drSingh);
		appointment(c, sarah, null, sarahRef, "NEW_PATIENT", "IN_PERSON", AppointmentStatus.SCHEDULED, clinic(1, 9, 0), 40, drShah);
		appointment(c, carlos, carlosOpp, null, "CONSULTATION", "IN_PERSON", AppointmentStatus.SCHEDULED, clinic(2, 10, 40), 30, drSingh);
		appointment(c, grace, null, null, "TELEHEALTH", "TELEHEALTH", AppointmentStatus.SCHEDULED, clinic(2, 15, 0), 30, npChen);
		Appointment lindaNext = appointment(c, linda, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.CONFIRMED, clinic(3, 8, 20), 30, drSingh);
		appointment(c, rosa, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.SCHEDULED, clinic(4, 13, 40), 30, npChen);
		appointment(c, mei, null, null, "TELEHEALTH", "TELEHEALTH", AppointmentStatus.CONFIRMED, clinic(5, 11, 0), 30, drShah);
		appointment(c, robert, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.SCHEDULED, clinic(6, 9, 0), 30, drShah);
		appointment(c, olivia, null, null, "CONSULTATION", "IN_PERSON", AppointmentStatus.COMPLETED, clinic(-2, 10, 0), 40, npChen);
		appointment(c, priya, null, null, "NEW_PATIENT", "IN_PERSON", AppointmentStatus.COMPLETED, clinic(-40, 9, 0), 40, drSingh);
		appointment(c, robert, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.COMPLETED, clinic(-21, 14, 0), 30, drShah);
		Appointment danielDone = appointment(c, daniel, null, null, "CONSULTATION", "IN_PERSON", AppointmentStatus.COMPLETED, clinic(-10, 11, 0), 30, drShah);
		appointment(c, linda, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.COMPLETED, clinic(-60, 8, 40), 30, drSingh);
		Appointment samuelMissed = appointment(c, samuel, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.NO_SHOW, clinic(-3, 9, 40), 30, drSingh);
		Appointment rosaMissed = appointment(c, rosa, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.NO_SHOW, clinic(-6, 13, 0), 30, npChen);
		Appointment danielCancelled = appointment(c, daniel, null, null, "CONSULTATION", "IN_PERSON", AppointmentStatus.CANCELLED, clinic(-15, 10, 0), 30, drShah);
		Appointment lindaCancelled = appointment(c, linda, null, null, "FOLLOW_UP", "IN_PERSON", AppointmentStatus.CANCELLED, clinic(-9, 8, 40), 30, drSingh);
		Appointment victorMissed = appointment(c, victor, null, victorRef, "CONSULTATION", "IN_PERSON", AppointmentStatus.NO_SHOW, clinic(-1, 14, 20), 30, drSingh);
		danielCancelled.setCancelReason("Patient request");
		lindaCancelled.setCancelReason("Provider unavailable");
		appointments.saveAll(List.of(danielCancelled, lindaCancelled));

		// ── recall call list (no-shows & cancellations) ─────────────────
		recall(c, samuel, samuelMissed, "NO_SHOW", "UTESSA", Recall.Status.TO_CALL, 0, null, null, null);
		recall(c, rosa, rosaMissed, "NO_SHOW", "UTESSA", Recall.Status.NOT_REACHED, 1, "Left voicemail", LocalDate.now().plusDays(1), null);
		recall(c, victor, victorMissed, "NO_SHOW", "UTESSA", Recall.Status.TO_CALL, 0, null, null, null);
		recall(c, daniel, danielCancelled, "CANCELLED", "UTESSA", Recall.Status.REBOOKED, 2, "Rebooked", null, danielDone);
		recall(c, linda, lindaCancelled, "CANCELLED", "UTESSA", Recall.Status.REBOOKED, 1, "Rebooked", null, lindaNext);
		Recall imported = new Recall();
		imported.setPatientId(grace.getId());
		imported.setMissedAt(clinic(-5, 15, 20));
		imported.setMissType("NO_SHOW");
		imported.setVisitType("Annual physical");
		imported.setProviderName(npChen);
		imported.setSource("UPLOAD");
		imported.setNextAttemptOn(LocalDate.now());
		c.track("recall", recalls.save(imported).getId());

		// ── tasks & follow-ups ──────────────────────────────────────────
		Instant now = Instant.now();
		task(c, marcus, "First contact: Marcus Johnson", "CALL", Priority.URGENT, now.minus(20, ChronoUnit.MINUTES), TaskStatus.OPEN);
		task(c, aisha, "First contact: Aisha Rahman", "CALL", Priority.HIGH, now.plus(40, ChronoUnit.MINUTES), TaskStatus.OPEN);
		task(c, kevin, "First contact: Kevin O'Brien", "CALL", Priority.MEDIUM, now.minus(1, ChronoUnit.DAYS), TaskStatus.OPEN);
		task(c, elena, "Call back Elena Petrova", "CALL", Priority.MEDIUM, todayAt(16, 0), TaskStatus.OPEN);
		CrmTask jamesTask = task(c, james, "Process referral: verify insurance & schedule James Wright", "INSURANCE_VERIFICATION", Priority.HIGH, now.minus(1, ChronoUnit.DAYS), TaskStatus.OPEN);
		jamesTask.setReferralId(jamesRef.getId());
		tasks.save(jamesTask);
		task(c, hannah, "Verify BCBS eligibility for Holter study", "INSURANCE_VERIFICATION", Priority.MEDIUM, todayAt(15, 0), TaskStatus.OPEN);
		task(c, victor, "Follow up on Medicare prior authorization", "FOLLOW_UP", Priority.HIGH, clinic(1, 10, 0), TaskStatus.OPEN);
		task(c, fatima, "Send new-patient intake forms", "EMAIL", Priority.MEDIUM, clinic(2, 9, 0), TaskStatus.OPEN);
		task(c, mei, "Confirm lab order before telehealth visit", "CALL", Priority.MEDIUM, clinic(3, 11, 0), TaskStatus.OPEN);
		task(c, linda, "Review cardiac rehab plan with patient", "FOLLOW_UP", Priority.MEDIUM, clinic(2, 14, 0), TaskStatus.OPEN);
		CrmTask done = task(c, robert, "Confirm stress test results were sent to Dr. Lopez", "RECORDS_REQUEST", Priority.LOW, clinic(-5, 10, 0), TaskStatus.DONE);
		done.setCompletedAt(clinic(-5, 11, 30));
		done.setCompletedByName(c.actor);
		tasks.save(done);
		for (Patient p : everyone) {
			taskService.syncFollowUp(p.getId());
		}

		// ── calls (last 14 days) ────────────────────────────────────────
		seedCalls(c, everyone, List.of(linda, robert, priya, daniel, rosa, samuel, grace),
				List.of(carlos, sarah, fatima, tom, hannah), plano);
		touch(elena, "Voicemail", clinic(-1, 11, 15));
		touch(james, "No answer", clinic(-2, 16, 5));

		// ── texts ───────────────────────────────────────────────────────
		text(c, linda, "OUT", "Hi Linda, a reminder of your visit with " + drSingh + " on " + lindaNext.getStartsAt().atZone(zone()).toLocalDate() + ". Reply C to confirm. Reply STOP to opt out.", "DELIVERED", now.minus(26, ChronoUnit.HOURS), true);
		text(c, linda, "IN", "C", "RECEIVED", now.minus(25, ChronoUnit.HOURS), true);
		text(c, linda, "OUT", "Thanks, you're confirmed. See you soon!", "DELIVERED", now.minus(25, ChronoUnit.HOURS).plusSeconds(120), true);
		text(c, rosa, "OUT", "Hi Rosa, we missed you at your visit. Please call (972) 555-0100 to book a new time. Reply STOP to opt out.", "DELIVERED", now.minus(5, ChronoUnit.DAYS), true);
		text(c, rosa, "IN", "Thank you! Is Monday morning possible?", "RECEIVED", now.minus(4, ChronoUnit.DAYS), false);
		text(c, grace, "IN", "Hi, is there anything I need to do before my telehealth visit?", "RECEIVED", now.minus(2, ChronoUnit.HOURS), false);
		text(c, priya, "OUT", "Hi Priya, your visit summary is ready in the patient portal. Reply STOP to opt out.", "DELIVERED", now.minus(30, ChronoUnit.DAYS), true);
		text(c, priya, "IN", "STOP", "RECEIVED", now.minus(30, ChronoUnit.DAYS).plusSeconds(600), true);
		text(c, priya, "OUT", "You're unsubscribed and won't get more texts.", "DELIVERED", now.minus(30, ChronoUnit.DAYS).plusSeconds(610), true);

		// ── voicemail ───────────────────────────────────────────────────
		voicemail(c, linda, linda.getPhone(), null, "Main line", 38, "Hi, this is Linda Park, date of birth July 22, 1961. I need to move my appointment, something came up at work. Please call me back. Thanks.", now.minus(3, ChronoUnit.HOURS), "NEW");
		voicemail(c, null, "(972) 555-0101", "Laura Méndez", "Main line", 52, "Hello, my name is Laura Méndez. My doctor referred me for a heart evaluation. I have Aetna. You can reach me at this number. Thank you.", now.minus(20, ChronoUnit.HOURS), "NEW");
		voicemail(c, null, plano.getPhone(), "Plano Family Medicine", "Referral line", 27, "Hi, this is Karen from Plano Family Medicine. We faxed a referral for Hannah Lee this afternoon. Call us if you need her records.", now.minus(3, ChronoUnit.DAYS), "HANDLED");

		// ── faxes ───────────────────────────────────────────────────────
		fax(c, "IN", plano.getFax(), plano.getName(), null, "Referral: Hannah Lee", "Referral", "RECEIVED", false, null,
				List.of("REFERRAL", "Patient: Hannah Lee   DOB: 03/15/1987", "Referring: Plano Family Medicine", "Reason: Dizziness and irregular heartbeat. Please evaluate for Holter monitor.", "Insurance: Blue Cross Blue Shield"),
				now.minus(4, ChronoUnit.DAYS));
		fax(c, "IN", baylor.getFax(), baylor.getName(), victor, "Discharge summary – Victor Ramos", "Medical records", "FILED", false, null,
				List.of("DISCHARGE SUMMARY", "Patient: Victor Ramos   DOB: 09/21/1963", "Admitted for acute decompensated heart failure.", "Follow up with cardiology within 7 days."),
				now.minus(10, ChronoUnit.DAYS));
		fax(c, "OUT", patel.getFax(), patel.getName(), priya, "Visit note: Priya Nair", "Medical records", "QUEUED", true, "Visit note for your records.",
				List.of("VISIT NOTE", "Patient: Priya Nair   DOB: 05/03/1990", "Seen for palpitations. Holter normal. Follow up as needed."),
				now.minus(1, ChronoUnit.DAYS));

		// ── targets (this month) ────────────────────────────────────────
		LocalDate first = LocalDate.now().withDayOfMonth(1);
		LocalDate last = first.plusMonths(1).minusDays(1);
		String label = first.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " " + first.getYear();
		target(c, null, "Whole practice", "NEW_LEADS", 25, label, first, last);
		target(c, null, "Whole practice", "CONVERSIONS", 6, label, first, last);
		target(c, null, "Whole practice", "REVENUE_WON", 5000, label, first, last);
		target(c, principal.userId(), c.actor, "APPOINTMENTS_BOOKED", 20, label, first, last);
		target(c, principal.userId(), c.actor, "CALLS_MADE", 40, label, first, last);

		// ── practice phone numbers (only fills blanks; not removed later) ─
		fillPhoneNumbers(principal);

		return status(principal);
	}

	// ═════════════════════════════════════════════════════════════════════
	// Remove
	// ═════════════════════════════════════════════════════════════════════

	@Transactional
	public Status remove(UserPrincipal principal) {
		support.requireAdmin(principal);
		UUID orgId = support.orgId();
		String[][] order = {
				{ "activity", "crm_activities" },
				{ "task", "crm_tasks" },
				{ "recall", "crm_recalls" },
				{ "message", "crm_messages" },
				{ "voicemail", "crm_voicemails" },
				{ "fax", "crm_faxes" },
				{ "appointment", "crm_appointments" },
				{ "opportunity", "crm_opportunities" },
				{ "referral", "crm_referrals" },
				{ "target", "crm_targets" },
				{ PATIENT, "crm_patients" },
				{ "partner", "crm_referral_partners" },
				{ "staff", "staff_members" },
		};
		for (String[] o : order) {
			jdbc.update("delete from " + o[1] + " where organization_id = ? and id in "
					+ "(select entity_id from crm_sample_records where organization_id = ? and entity_type = ?)", orgId, orgId, o[0]);
		}
		entityManager.flush();
		entityManager.clear();
		jdbc.update("delete from crm_sample_records where organization_id = ?", orgId);
		return status(principal);
	}

	// ═════════════════════════════════════════════════════════════════════
	// Builders
	// ═════════════════════════════════════════════════════════════════════

	private final class Ctx {
		final UserPrincipal principal;
		final String actor;
		final UUID orgId;

		Ctx(UserPrincipal principal) {
			this.principal = principal;
			this.actor = support.actorName(principal);
			this.orgId = support.orgId();
		}

		void track(String type, UUID id) {
			jdbc.update("insert into crm_sample_records(organization_id, entity_type, entity_id) values (?, ?, ?)", orgId, type, id);
		}

		void backdate(String table, UUID id, Instant when) {
			entityManager.flush();
			jdbc.update("update " + table + " set created_at = ?, updated_at = ? where id = ?", Timestamp.from(when), Timestamp.from(when), id);
		}
	}

	private StaffMember provider(Ctx c, String first, String last, String designation, String title, String specialty, String phone) {
		StaffMember s = new StaffMember();
		s.setFirstName(first);
		s.setLastName(last);
		s.setDesignation(designation);
		s.setJobTitle(title);
		s.setCategory("Provider");
		s.setDepartment("Clinical");
		s.setProvider(true);
		s.setStatus(EmploymentStatus.ACTIVE);
		s.setSpecialty(specialty);
		s.setLocation("Main office");
		s.setWorkPhone(phone);
		s.setEmail(first.toLowerCase() + "." + last.toLowerCase() + "@sample-clinic.test");
		s.setNotes("Sample data");
		StaffMember saved = staff.save(s);
		c.track("staff", saved.getId());
		return saved;
	}

	private ReferralPartner partner(Ctx c, String name, String type, String status, String specialty, String contact,
			String phone, String fax, String npi, int daysAgo) {
		ReferralPartner p = new ReferralPartner();
		p.setName(name);
		p.setPartnerType(type);
		p.setRelationshipStatus(status);
		p.setSpecialty(specialty);
		p.setContactName(contact);
		p.setPhone(phone);
		p.setFax(fax);
		p.setNpi(npi);
		p.setCity("Plano");
		p.setState("TX");
		p.setOwnerUserId(c.principal.userId());
		p.setOwnerName(c.actor);
		p.setLastTouchAt(Instant.now().minus(Math.max(1, daysAgo / 8), ChronoUnit.DAYS));
		p.setNotes("Sample data");
		ReferralPartner saved = partners.save(p);
		c.track("partner", saved.getId());
		c.backdate("crm_referral_partners", saved.getId(), Instant.now().minus(daysAgo, ChronoUnit.DAYS));
		return saved;
	}

	private Patient person(Ctx c, String first, String last, String dob, String phone, Lifecycle lifecycle, LeadStatus status,
			Priority priority, String source, ReferralPartner partner, String service, String provider, String insurance,
			boolean sms, int daysAgo, String mrn, String reason, String lostReason, String language) {
		Patient p = new Patient();
		p.setFirstName(first);
		p.setLastName(last);
		p.setFullName(first + " " + last);
		p.setDateOfBirth(LocalDate.parse(dob));
		p.setPhone(phone);
		p.setPhoneNormalized(CrmSupport.normalizePhone(phone));
		p.setEmail((first + "." + last).toLowerCase().replaceAll("[^a-z.]", "") + "@example.test");
		p.setLifecycleStage(lifecycle);
		p.setLeadStatus(status);
		p.setPriority(priority);
		p.setSource(source);
		if (partner != null) {
			p.setReferralPartnerId(partner.getId());
			p.setSourceDetail(partner.getName());
		}
		p.setInterestedService(service);
		p.setPreferredProvider(provider);
		p.setPreferredLocation("Main office");
		p.setInsurancePayer(insurance);
		p.setInsuranceMemberId(insurance == null ? null : "W" + Math.abs((first + last).hashCode() % 900000 + 100000));
		p.setInsuranceVerified(lifecycle == Lifecycle.PATIENT && insurance != null);
		p.setCoverageType(insurance == null ? "SELF_PAY" : "INSURANCE");
		p.setConsentCall(true);
		p.setConsentSms(sms);
		p.setConsentEmail(sms);
		p.setSmsReminders(sms);
		p.setPreferredContact(sms ? "Text" : "Mobile");
		p.setPreferredLanguage(language);
		p.setCity("Plano");
		p.setState("TX");
		p.setMrn(mrn);
		p.setReasonForVisit(reason);
		p.setLostReason(lostReason);
		p.setOwnerUserId(c.principal.userId());
		p.setOwnerName(c.actor);
		p.setCreatedByUserId(c.principal.userId());
		p.setTags("Sample data");
		Instant created = daysAgo == 0 ? Instant.now().minus(25, ChronoUnit.MINUTES) : clinic(-daysAgo, 9 + Math.abs(first.hashCode() % 7), 15);
		if (lifecycle == Lifecycle.PATIENT) {
			p.setConvertedAt(created.plus(Math.min(5, daysAgo / 2), ChronoUnit.DAYS));
		}
		Patient saved = patients.save(p);
		c.track(PATIENT, saved.getId());
		c.backdate("crm_patients", saved.getId(), created);
		saved.setCreatedAt(created);
		return saved;
	}

	private Opportunity opportunity(Ctx c, Patient p, String name, String line, OpportunityStage stage, int value, String provider,
			int daysAgo, Integer closedDaysAgo) {
		Opportunity o = new Opportunity();
		o.setPatientId(p.getId());
		o.setName(name);
		o.setServiceLine(line);
		o.setStage(stage);
		o.setPriority(p.getPriority());
		o.setEstimatedValue(BigDecimal.valueOf(value));
		o.setProbability(OpportunityService.defaultProbability(stage));
		o.setExpectedCloseDate(LocalDate.now().plusDays(stage == OpportunityStage.WON || stage == OpportunityStage.LOST ? -closedDaysAgo : 14));
		o.setProviderName(provider);
		o.setOwnerUserId(c.principal.userId());
		o.setOwnerName(c.actor);
		if (closedDaysAgo != null) {
			o.setClosedAt(Instant.now().minus(closedDaysAgo, ChronoUnit.DAYS));
		}
		Opportunity saved = opportunities.save(o);
		c.track("opportunity", saved.getId());
		Instant created = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
		c.backdate("crm_opportunities", saved.getId(), created);
		activity(c, p, ActivityType.SYSTEM, "Opportunity created: " + name, "Service line: " + line, saved.getId(), created);
		if (stage != OpportunityStage.INQUIRY) {
			activity(c, p, ActivityType.STATUS_CHANGE, name + ": Inquiry → " + CrmSupport.humanize(stage), null, saved.getId(),
					closedDaysAgo != null ? Instant.now().minus(closedDaysAgo, ChronoUnit.DAYS) : created.plus(1, ChronoUnit.DAYS));
		}
		return saved;
	}

	private Referral referral(Ctx c, Patient p, ReferralPartner partner, ReferralStatus status, Priority priority, String service,
			String icd, String payer, String auth, int receivedDaysAgo, int dueInDays, String closedReason) {
		Referral r = new Referral();
		r.setPatientId(p.getId());
		r.setReferralPartnerId(partner.getId());
		r.setStatus(status);
		r.setPriority(priority);
		r.setReferringProviderName(partner.getContactName());
		r.setReferringProviderNpi(partner.getNpi());
		r.setServiceRequested(service);
		r.setReason(p.getReasonForVisit());
		r.setDiagnosisCodes(icd);
		r.setInsurancePayer(payer);
		r.setAuthorizationNumber(auth);
		r.setAuthorizationExpiresOn(auth == null ? null : LocalDate.now().plusDays(60));
		r.setReceivedOn(LocalDate.now().minusDays(receivedDaysAgo));
		r.setDueOn(LocalDate.now().plusDays(dueInDays));
		r.setAssignedUserId(c.principal.userId());
		r.setAssignedName(c.actor);
		r.setClosedReason(closedReason);
		if (!r.isOpen()) {
			r.setClosedAt(Instant.now().minus(Math.max(0, -dueInDays), ChronoUnit.DAYS));
		}
		Referral saved = referrals.save(r);
		c.track("referral", saved.getId());
		Instant received = clinic(-receivedDaysAgo, 10, 30);
		c.backdate("crm_referrals", saved.getId(), received);
		Activity a = activity(c, p, ActivityType.SYSTEM, "Referral received from " + partner.getName(), "Requested: " + service, null, received);
		a.setReferralId(saved.getId());
		activities.save(a);
		return saved;
	}

	private Appointment appointment(Ctx c, Patient p, Opportunity opp, Referral ref, String type, String mode, AppointmentStatus status,
			Instant startsAt, int minutes, String provider) {
		Appointment a = new Appointment();
		a.setPatientId(p.getId());
		a.setOpportunityId(opp == null ? null : opp.getId());
		a.setReferralId(ref == null ? null : ref.getId());
		a.setAppointmentType(type);
		a.setMode(mode);
		a.setStatus(status);
		a.setStartsAt(startsAt);
		a.setDurationMinutes(minutes);
		a.setProviderName(provider);
		a.setLocation("TELEHEALTH".equals(mode) ? "Video visit" : "Main office");
		a.setTitle(AppointmentService.typeLabel(type) + " · " + p.getFullName());
		a.setAssignedUserId(c.principal.userId());
		a.setAssignedName(c.actor);
		Appointment saved = appointments.save(a);
		c.track("appointment", saved.getId());
		Instant booked = startsAt.isAfter(Instant.now()) ? Instant.now().minus(2, ChronoUnit.DAYS) : startsAt.minus(7, ChronoUnit.DAYS);
		c.backdate("crm_appointments", saved.getId(), booked);
		activity(c, p, ActivityType.MEETING, "Appointment booked: " + saved.getTitle(), provider == null ? null : "With " + provider, saved.getOpportunityId(), booked);
		if (status == AppointmentStatus.COMPLETED || status == AppointmentStatus.NO_SHOW || status == AppointmentStatus.CANCELLED) {
			activity(c, p, ActivityType.STATUS_CHANGE, saved.getTitle() + ": Scheduled → " + CrmSupport.humanize(status), null,
					saved.getOpportunityId(), startsAt.plus(minutes, ChronoUnit.MINUTES));
		}
		return saved;
	}

	private void recall(Ctx c, Patient p, Appointment missed, String type, String source, Recall.Status status, int attempts,
			String lastOutcome, LocalDate next, Appointment rebooked) {
		Recall r = new Recall();
		r.setPatientId(p.getId());
		r.setAppointmentId(missed.getId());
		r.setMissedAt(missed.getStartsAt());
		r.setMissType(type);
		r.setVisitType(AppointmentService.typeLabel(missed.getAppointmentType()));
		r.setProviderName(missed.getProviderName());
		r.setSource(source);
		r.setStatus(status);
		r.setAttempts(attempts);
		r.setLastOutcome(lastOutcome);
		r.setLastAttemptAt(attempts > 0 ? missed.getStartsAt().plus(1, ChronoUnit.DAYS) : null);
		r.setNextAttemptOn(status == Recall.Status.TO_CALL ? LocalDate.now() : next);
		r.setRebookedAppointmentId(rebooked == null ? null : rebooked.getId());
		c.track("recall", recalls.save(r).getId());
	}

	private CrmTask task(Ctx c, Patient p, String title, String type, Priority priority, Instant due, TaskStatus status) {
		CrmTask t = new CrmTask();
		t.setPatientId(p.getId());
		t.setTitle(title);
		t.setTaskType(type);
		t.setPriority(priority);
		t.setDueAt(due);
		t.setStatus(status);
		t.setAssignedUserId(c.principal.userId());
		t.setAssignedName(c.actor);
		t.setCreatedByName(c.actor);
		CrmTask saved = tasks.save(t);
		c.track("task", saved.getId());
		return saved;
	}

	private Activity activity(Ctx c, Patient p, ActivityType type, String subject, String body, UUID oppId, Instant at) {
		Activity a = new Activity();
		a.setPatientId(p == null ? null : p.getId());
		a.setOpportunityId(oppId);
		a.setActivityType(type);
		a.setSubject(CrmSupport.truncate(subject, 255));
		a.setBody(body);
		a.setActorUserId(c.principal.userId());
		a.setActorName(c.actor);
		a.setOccurredAt(at);
		Activity saved = activities.save(a);
		c.track("activity", saved.getId());
		return saved;
	}

	private void seedCalls(Ctx c, List<Patient> everyone, List<Patient> existing, List<Patient> newCallers, ReferralPartner office) {
		Random rnd = new Random(42);
		String[] reasons = { "Schedule or reschedule", "Schedule or reschedule", "Question for the care team", "Prescription refill",
				"Billing or insurance", "Test results" };
		String[] outcomes = { "Resolved on the call", "Appointment booked or changed", "Message sent to care team", "Call back needed", "Resolved on the call" };
		String[] notes = { "Moved visit to next week.", "Asked about copay for echo.", "Refill request sent to Dr. Singh.",
				"Wanted lab results — told results are reviewed by the provider first.", "Confirmed directions to the office.", "" };
		for (Patient p : newCallers) {
			boolean booked = p.getLeadStatus() == LeadStatus.APPOINTMENT_SCHEDULED;
			Activity a = new Activity();
			a.setActorUserId(c.principal.userId());
			a.setActorName(c.actor);
			a.setOccurredAt(p.getCreatedAt().plus(2, ChronoUnit.MINUTES));
			a.setPatientId(p.getId());
			a.setActivityType(ActivityType.CALL_INBOUND);
			a.setSubject(booked ? "Inbound call · New patient appointment booked" : "Inbound call · Saved as lead");
			a.setPhone(p.getPhone());
			a.setCallOutcome(booked ? CallFlowService.OUTCOME_NEW_PATIENT : CallFlowService.OUTCOME_SAVED_LEAD);
			a.setCallReason(booked ? "New patient appointment" : "Asked about a provider or service");
			a.setCallerName(p.getFullName());
			a.setCallerType("NEW_CALLER");
			a.setBody(p.getReasonForVisit());
			a.setDurationSeconds(240 + rnd.nextInt(360));
			a.setPhoneLine("MAIN");
			a.setEhrSyncStatus(booked ? IntegrationService.SYNC_PENDING : IntegrationService.SYNC_NOT_LINKED);
			c.track("activity", activities.save(a).getId());
			touch(p, a.getCallOutcome(), a.getOccurredAt());
		}
		for (int d = 13; d >= 0; d--) {
			LocalDate day = LocalDate.now(zone()).minusDays(d);
			boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
			int count = weekend ? rnd.nextInt(2) : 3 + rnd.nextInt(4);
			for (int i = 0; i < count; i++) {
				int hour = 8 + rnd.nextInt(9);
				if (rnd.nextInt(3) == 0) {
					hour = 9 + rnd.nextInt(3); // morning rush
				}
				Instant at = day.atTime(hour, rnd.nextInt(60)).atZone(zone()).toInstant();
				if (at.isAfter(Instant.now())) {
					continue;
				}
				Activity a = new Activity();
				a.setActorUserId(c.principal.userId());
				a.setActorName(c.actor);
				a.setOccurredAt(at);
				int roll = rnd.nextInt(100);
				if (roll < 9) {
					List<Patient> pool = existing.stream().filter(x -> x.getCreatedAt().isBefore(at)).toList();
					if (pool.isEmpty()) {
						continue;
					}
					Patient p = pool.get(rnd.nextInt(pool.size()));
					a.setPatientId(p.getId());
					a.setActivityType(ActivityType.CALL_INBOUND);
					a.setSubject("Inbound call · Missed");
					a.setPhone(p.getPhone());
					a.setCallOutcome(CallFlowService.OUTCOME_MISSED);
					a.setCallerName(p.getFullName());
					a.setCallerType(PATIENT);
				} else if (roll < 26) {
					a.setActivityType(ActivityType.CALL_INBOUND);
					a.setSubject("Inbound call");
					a.setPhone(office.getPhone());
					a.setCallerName(office.getName());
					a.setCallerType("REFERRING_OFFICE");
					a.setBody("Checked the status of a referral.");
					a.setDurationSeconds(60 + rnd.nextInt(120));
				} else {
					boolean outbound = roll >= 72;
					List<Patient> pool = (outbound ? everyone : existing).stream()
							.filter(x -> x.getCreatedAt().isBefore(at))
							.filter(x -> !newCallers.contains(x) || !x.getCreatedAt().plus(1, ChronoUnit.HOURS).isAfter(at))
							.toList();
					if (pool.isEmpty()) {
						continue;
					}
					Patient p = pool.get(rnd.nextInt(pool.size()));
					String reason = outbound ? "Follow-up call" : reasons[rnd.nextInt(reasons.length)];
					String outcome = outbound ? (rnd.nextBoolean() ? "Reached" : "Voicemail") : outcomes[rnd.nextInt(outcomes.length)];
					a.setPatientId(p.getId());
					a.setActivityType(outbound ? ActivityType.CALL_OUTBOUND : ActivityType.CALL_INBOUND);
					a.setSubject((outbound ? "Outbound call · " : "Inbound call · ") + reason);
					a.setPhone(p.getPhone());
					a.setCallReason(outbound ? null : reason);
					a.setCallOutcome(outcome);
					a.setCallerName(p.getFullName());
					a.setCallerType(p.isLead() ? "LEAD" : PATIENT);
					String note = notes[rnd.nextInt(notes.length)];
					a.setBody(note.isEmpty() ? null : note);
					a.setDurationSeconds(45 + rnd.nextInt(420));
					a.setEhrSyncStatus(!outbound && rnd.nextInt(3) == 0 ? IntegrationService.SYNC_PENDING : IntegrationService.SYNC_NOT_LINKED);
				}
				a.setPhoneLine(rnd.nextInt(5) == 0 ? "REFERRAL" : "MAIN");
				Activity saved = activities.save(a);
				c.track("activity", saved.getId());
				if (saved.getPatientId() != null && !CallFlowService.OUTCOME_MISSED.equals(saved.getCallOutcome())) {
					patients.findById(saved.getPatientId()).ifPresent(p -> touch(p, saved.getCallOutcome(), saved.getOccurredAt()));
				}
			}
		}
	}

	private void touch(Patient p, String outcome, Instant at) {
		if (p.getLastContactAt() == null || p.getLastContactAt().isBefore(at)) {
			p.setLastContactAt(at);
			p.setCallOutcome(outcome);
			patients.save(p);
		}
	}

	private void text(Ctx c, Patient p, String dir, String body, String status, Instant at, boolean read) {
		PhoneMessage m = new PhoneMessage();
		m.setPatientId(p.getId());
		m.setPhone(p.getPhone());
		m.setPhoneNormalized(p.getPhoneNormalized());
		m.setContactName(p.getFullName());
		m.setDirection(dir);
		m.setBody(body);
		m.setStatus(status);
		m.setLine("MAIN");
		if ("OUT".equals(dir)) {
			m.setSentByUserId(c.principal.userId());
			m.setSentByName(c.actor);
		} else if (read) {
			m.setReadAt(at.plusSeconds(300));
		}
		PhoneMessage saved = messages.save(m);
		c.track("message", saved.getId());
		c.backdate("crm_messages", saved.getId(), at);
	}

	private void voicemail(Ctx c, Patient p, String phone, String name, String box, int secs, String transcript, Instant at, String status) {
		Voicemail v = new Voicemail();
		v.setPatientId(p == null ? null : p.getId());
		v.setFromPhone(phone);
		v.setPhoneNormalized(CrmSupport.normalizePhone(phone));
		v.setCallerName(p == null ? name : p.getFullName());
		v.setMailbox(box);
		v.setDurationSeconds(secs);
		v.setTranscript(transcript);
		v.setReceivedAt(at);
		v.setStatus(status);
		if ("HANDLED".equals(status)) {
			v.setHandledAt(at.plus(2, ChronoUnit.HOURS));
			v.setHandledByName(c.actor);
		}
		c.track("voicemail", voicemails.save(v).getId());
	}

	private void fax(Ctx c, String dir, String number, String party, Patient p, String subject, String docType, String status,
			boolean cover, String coverNote, List<String> lines, Instant at) {
		byte[] pdf = simplePdf(lines);
		Fax f = new Fax();
		f.setDirection(dir);
		f.setFaxNumber(number);
		f.setPartyName(party);
		f.setPatientId(p == null ? null : p.getId());
		f.setSubject(subject);
		f.setDocumentType(docType);
		f.setStatus(status);
		f.setCoverPage(cover);
		f.setCoverNote(coverNote);
		f.setFileName(subject.toLowerCase().replaceAll("[^a-z0-9]+", "-") + ".pdf");
		f.setContentType("application/pdf");
		f.setFileSize(pdf.length);
		f.setFileData(pdf);
		f.setPages(1 + (cover ? 1 : 0));
		f.setCreatedByName("IN".equals(dir) ? "Fax line" : c.actor);
		if ("FILED".equals(status)) {
			f.setFiledAt(at.plus(1, ChronoUnit.HOURS));
			f.setFiledByName(c.actor);
		}
		Fax saved = faxes.save(f);
		c.track("fax", saved.getId());
		c.backdate("crm_faxes", saved.getId(), at);
		if (p != null) {
			activity(c, p, ActivityType.SYSTEM, ("IN".equals(dir) ? "Fax filed: " + docType + " from " : "Fax queued to ") + party, subject, null, at);
		}
	}

	private void target(Ctx c, UUID userId, String who, String metric, int value, String label, LocalDate from, LocalDate to) {
		CrmTarget t = new CrmTarget();
		t.setUserId(userId);
		t.setAssigneeName(who);
		t.setMetric(metric);
		t.setTargetValue(BigDecimal.valueOf(value));
		t.setPeriodLabel(label);
		t.setPeriodStart(from);
		t.setPeriodEnd(to);
		t.setNotes("Sample data");
		t.setCreatedByName(c.actor);
		c.track("target", targets.save(t).getId());
	}

	@SuppressWarnings("unchecked")
	private void fillPhoneNumbers(UserPrincipal principal) {
		PhoneConfig cfg = phoneConfigs.findByOrganizationIdAndUserIdIsNull(support.orgId()).orElseGet(PhoneConfig::new);
		Map<String, Object> m = new LinkedHashMap<>(cfg.getConfig());
		if (isBlank(m.get("faxNumber"))) {
			m.put("faxNumber", "(972) 555-0108");
		}
		Object lines = m.get("lines");
		if (!(lines instanceof List<?> list) || list.stream().allMatch(l -> l instanceof Map<?, ?> lm && isBlank(lm.get("number")))) {
			m.put("lines", List.of(
					new LinkedHashMap<>(Map.of("id", "MAIN", "label", "Main line", "number", "(972) 555-0100")),
					new LinkedHashMap<>(Map.of("id", "REFERRAL", "label", "Referral line", "number", "(972) 555-0109"))));
		}
		if (isBlank(m.get("practiceName"))) {
			organizations.findById(support.orgId()).ifPresent(o -> m.put("practiceName", o.getName()));
		}
		Object queues = m.get("queues");
		if (queues instanceof List<?> ql) {
			List<Object> updated = new ArrayList<>();
			for (Object q : ql) {
				if (q instanceof Map<?, ?> qm && "Front desk".equals(qm.get("name"))
						&& (!(qm.get("members") instanceof List<?> mem) || mem.isEmpty())) {
					Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) qm);
					copy.put("members", List.of(principal.userId().toString()));
					updated.add(copy);
				} else {
					updated.add(q);
				}
			}
			m.put("queues", updated);
		}
		cfg.setConfig(m);
		phoneConfigs.save(cfg);
	}

	// ── time helpers ────────────────────────────────────────────────────

	private static ZoneId zone() {
		return ZoneId.systemDefault();
	}

	/** A clinic-day time: weekends roll forward (future) or back (past) to a weekday. */
	private static Instant clinic(int dayOffset, int hour, int minute) {
		LocalDate d = LocalDate.now(zone()).plusDays(dayOffset);
		while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
			d = dayOffset >= 0 ? d.plusDays(1) : d.minusDays(1);
		}
		return d.atTime(LocalTime.of(Math.min(hour, 23), minute)).atZone(zone()).toInstant();
	}

	private static Instant todayAt(int hour, int minute) {
		Instant t = LocalDate.now(zone()).atTime(hour, minute).atZone(zone()).toInstant();
		return t.isBefore(Instant.now()) ? Instant.now().plus(90, ChronoUnit.MINUTES) : t;
	}

	private static Instant nextHourFromNow(int hours) {
		ZonedDateTime z = ZonedDateTime.now(zone()).plusHours(hours).withMinute(0).withSecond(0).withNano(0);
		return z.toInstant();
	}

	private static boolean isBlank(Object o) {
		return o == null || String.valueOf(o).isBlank();
	}


	/** Minimal one-page PDF with Helvetica text lines (used for sample fax documents). */
	static byte[] simplePdf(List<String> lines) {
		StringBuilder content = new StringBuilder("BT /F1 18 Tf 72 720 Td ");
		boolean first = true;
		for (String line : lines) {
			String safe = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)").replaceAll("[^\\x20-\\x7E]", "-");
			if (first) {
				content.append("(").append(safe).append(") Tj /F1 12 Tf 0 -34 Td ");
				first = false;
			} else {
				content.append("(").append(safe).append(") Tj 0 -20 Td ");
			}
		}
		content.append("ET");
		String stream = content.toString();
		List<String> objs = List.of(
				"<< /Type /Catalog /Pages 2 0 R >>",
				"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
				"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
				"<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream",
				"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
		StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
		List<Integer> offsets = new ArrayList<>();
		for (int i = 0; i < objs.size(); i++) {
			offsets.add(pdf.length());
			pdf.append(i + 1).append(" 0 obj\n").append(objs.get(i)).append("\nendobj\n");
		}
		int xref = pdf.length();
		pdf.append("xref\n0 ").append(objs.size() + 1).append("\n0000000000 65535 f \n");
		for (int off : offsets) {
			pdf.append(String.format("%010d 00000 n \n", off));
		}
		pdf.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
		return pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
	}
}
