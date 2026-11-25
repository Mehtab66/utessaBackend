package com.crm.crm_backend.operations.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.operations.domain.OpsFolder;
import com.crm.crm_backend.operations.domain.OpsSeedState;
import com.crm.crm_backend.operations.domain.Payer;
import com.crm.crm_backend.operations.domain.SpecialtyTemplate;
import com.crm.crm_backend.operations.domain.TrackerTab;
import com.crm.crm_backend.operations.infrastructure.OpsFolderRepository;
import com.crm.crm_backend.operations.infrastructure.OpsSeedStateRepository;
import com.crm.crm_backend.operations.infrastructure.PayerRepository;
import com.crm.crm_backend.operations.infrastructure.SpecialtyTemplateRepository;
import com.crm.crm_backend.operations.infrastructure.TrackerTabRepository;

/**
 * Creates each organization's starting configuration once: the payer list, the standard provider
 * and staff trackers, two specialty templates and the default Resources and Training folders.
 * Deleting any of them later never brings them back.
 */
@Service
public class OpsDefaultsService {

	private final OpsSeedStateRepository seedRepository;
	private final PayerRepository payerRepository;
	private final TrackerTabRepository tabRepository;
	private final SpecialtyTemplateRepository specialtyRepository;
	private final OpsFolderRepository folderRepository;
	private final EntityManager entityManager;

	public OpsDefaultsService(
			OpsSeedStateRepository seedRepository,
			PayerRepository payerRepository,
			TrackerTabRepository tabRepository,
			SpecialtyTemplateRepository specialtyRepository,
			OpsFolderRepository folderRepository,
			EntityManager entityManager
	) {
		this.seedRepository = seedRepository;
		this.payerRepository = payerRepository;
		this.tabRepository = tabRepository;
		this.specialtyRepository = specialtyRepository;
		this.folderRepository = folderRepository;
		this.entityManager = entityManager;
	}

	@Transactional(propagation = Propagation.REQUIRED)
	public void ensurePayers(UUID orgId) {
		lock(orgId, "defaults");
		OpsSeedState state = state(orgId);
		if (state.isPayers()) {
			return;
		}
		Object[][] rows = {
				{ "Medicare Part B", "Medicare", "Medicare", "PECOS (CMS-855I / 855R)", 60, 60 },
				{ "State Medicaid", "Medicaid", "Medicaid", "State Medicaid enrollment portal", 45, 36 },
				{ "Blue Cross Blue Shield", "BCBS", "Commercial", "Availity + CAQH ProView", 90, 36 },
				{ "Aetna", "Aetna", "Commercial", "Availity + CAQH ProView", 75, 36 },
				{ "UnitedHealthcare", "UHC", "Commercial", "UHC provider portal + CAQH ProView", 90, 36 },
				{ "Cigna Healthcare", "Cigna", "Commercial", "Cigna for HCP + CAQH ProView", 60, 36 },
				{ "Humana (incl. Medicare Advantage)", "Humana", "Medicare Advantage", "Availity + CAQH ProView", 60, 36 },
				{ "Wellcare Medicare Advantage", "Wellcare", "Medicare Advantage", "Payer portal", 60, 36 },
				{ "Medicaid managed care plan", "Medicaid MCO", "Medicaid managed care", "Payer portal + CAQH ProView", 60, 36 },
				{ "Molina Healthcare", "Molina", "Medicaid managed care", "Availity", 60, 36 },
				{ "TRICARE", "TRICARE", "Military", "Payer portal", 45, 36 },
				{ "Ambetter (Marketplace)", "Ambetter", "Marketplace", "Payer portal", 60, 36 }
		};
		int i = 0;
		for (Object[] r : rows) {
			Payer p = new Payer();
			p.setOrganizationId(orgId);
			p.setName((String) r[0]);
			p.setShortName((String) r[1]);
			p.setPayerType((String) r[2]);
			p.setMethod((String) r[3]);
			p.setTypicalDays((Integer) r[4]);
			p.setRecredMonths((Integer) r[5]);
			p.setSortOrder(++i);
			payerRepository.save(p);
		}
		state.setPayers(true);
		seedRepository.save(state);
	}

	@Transactional
	public void ensureTrackers(UUID orgId) {
		lock(orgId, "defaults");
		OpsSeedState state = state(orgId);
		if (state.isTrackers()) {
			return;
		}
		int sort = 0;
		Map<String, Object> cmeGoal = new LinkedHashMap<>();
		cmeGoal.put("field", "credits");
		cmeGoal.put("years", 2);
		cmeGoal.put("need", 48);
		cmeGoal.put("label", "Credits needed per license renewal cycle (set to your state board's rule)");
		tab(orgId, "cme", "PROVIDER", "CME", "Education", "Courses and credits by provider, with certificates and what each credit counts toward", ++sort, cmeGoal,
				f("title", "Course / activity", "text", true),
				f("cat", "Credit type", "select", false, "AMA PRA Category 1", "AMA PRA Category 2", "AOA Category 1-A", "ANCC contact hours", "AAPA Category 1", "Other"),
				f("credits", "Credits", "number", true),
				f("done", "Completed", "date", true),
				f("toward", "Counts toward", "select", false, "State license renewal", "Board certification (MOC)", "DEA training (MATE Act)", "Hospital requirement", "Ethics / professional responsibility"),
				f("cert", "Certificate", "file", false));
		tab(orgId, "ebach", "PROVIDER", "Bachelors", "Education", "Undergraduate degree, school and primary-source verification", ++sort, null,
				f("degree", "Degree", "select", true, "BS", "BA", "BSN", "Other"),
				f("major", "Major / field", "text", false),
				f("school", "School", "text", true),
				f("grad", "Graduated", "date", false),
				f("ver", "Verified on", "date", false),
				f("verBy", "Verified with", "select", false, "National Student Clearinghouse", "School registrar", "Credentials verification organization"),
				f("dip", "Diploma / transcript", "file", false));
		tab(orgId, "emast", "PROVIDER", "Masters", "Education", "Graduate degree, for example MSN, MPAS or MPH", ++sort, null,
				f("degree", "Degree", "select", true, "MSN", "MPAS / MSPAS", "MPH", "MBA", "MS", "Other"),
				f("major", "Field / specialty", "text", false),
				f("school", "School", "text", true),
				f("grad", "Graduated", "date", false),
				f("ver", "Verified on", "date", false),
				f("verBy", "Verified with", "select", false, "National Student Clearinghouse", "School registrar", "Credentials verification organization"),
				f("dip", "Diploma / transcript", "file", false));
		tab(orgId, "edoc", "PROVIDER", "Doctorate", "Education", "Medical or professional school", ++sort, null,
				f("degree", "Degree", "select", true, "MD", "DO", "DNP", "PharmD", "DPT", "PhD", "Other"),
				f("school", "School", "text", true),
				f("grad", "Graduated", "date", false),
				f("ver", "Verified on", "date", false),
				f("verBy", "Verified with", "select", false, "AMA Physician Profile", "AOA Physician Profile", "ECFMG (international graduates)", "School registrar"),
				f("dip", "Diploma", "file", false));
		tab(orgId, "eres", "PROVIDER", "Residency & fellowship", "Education", "Internship, residency and fellowship programs", ++sort, null,
				f("kind", "Training", "select", true, "Internship", "Residency", "Fellowship"),
				f("spec", "Specialty", "text", true),
				f("inst", "Institution / program", "text", true),
				f("from", "Start", "date", false),
				f("to", "Completed", "date", false),
				f("ver", "Verified on", "date", false),
				f("cert", "Certificate", "file", false));
		tab(orgId, "mal", "PROVIDER", "Malpractice & claims", "Risk & quality", "Professional liability policies, limits, tail coverage and claims history", ++sort, null,
				f("carrier", "Carrier", "text", true),
				f("policy", "Policy #", "text", false),
				f("kind", "Coverage", "select", false, "Claims-made", "Occurrence"),
				f("limits", "Limits", "text", false),
				f("exp", "Expires", "expiry", true),
				f("tail", "Tail coverage", "yesno", false),
				f("claims", "Claims (last 10 yrs)", "number", false),
				f("coi", "Certificate of insurance", "file", false));
		tab(orgId, "peer", "PROVIDER", "Peer review (OPPE / FPPE)", "Risk & quality", "Ongoing and focused professional practice evaluations by facility", ++sort, null,
				f("type", "Review", "select", true, "OPPE", "FPPE: new privileges", "FPPE: for cause"),
				f("fac", "Facility", "text", false),
				f("from", "Period start", "date", false),
				f("to", "Period end", "date", false),
				f("rev", "Reviewer", "text", false),
				f("result", "Result", "select", false, "Meets expectations", "Needs follow-up", "Action plan", "Pending"),
				f("next", "Next due", "expiry", false),
				f("notes", "Notes", "text", false));
		Map<String, Object> base = f("base", "Base pay", "money", false);
		base.put("sensitive", true);
		tab(orgId, "con", "PROVIDER", "Contracts & compensation", "Employment", "Employment and contractor agreements, renewal dates and pay model", ++sort, null,
				f("kind", "Agreement", "select", true, "Employment", "Independent contractor", "Locum tenens", "Medical directorship", "Call coverage"),
				f("start", "Start", "date", false),
				f("end", "Renews / ends", "expiry", false),
				f("model", "Pay model", "select", false, "Salary", "wRVU productivity", "Salary + bonus", "Per diem", "Hourly"),
				base,
				f("nc", "Non-compete", "yesno", false),
				f("doc", "Signed contract", "file", false));
		tab(orgId, "onb", "PROVIDER", "Provider onboarding", "Employment", "Every step from signed offer to first patient, with owners and due dates", ++sort, null,
				f("task", "Step", "select", true, "Offer signed", "CAQH profile attested", "NPI and taxonomy confirmed", "Payer enrollments submitted", "Hospital privileges applied", "Malpractice bound", "EHR account and training", "Orientation complete", "First patient scheduled"),
				f("owner", "Owner", "text", false),
				f("due", "Due", "date", false),
				f("status", "Status", "select", false, "Not started", "In progress", "Waiting on provider", "Done"),
				f("notes", "Notes", "text", false));
		tab(orgId, "sonb", "STAFF", "Onboarding checklist", "Onboarding", "New-hire steps from offer to first shift, with owners and due dates", ++sort, null,
				f("task", "Step", "select", true, "Offer letter signed", "I-9 and W-4 complete", "Background check cleared", "Badge and keys issued", "Computer and system logins", "HIPAA training", "OSHA / bloodborne pathogens training", "Job shadowing complete", "30-day check-in"),
				f("owner", "Owner", "text", false),
				f("due", "Due", "date", false),
				f("status", "Status", "select", false, "Not started", "In progress", "Waiting on employee", "Done"),
				f("notes", "Notes", "text", false));
		tab(orgId, "seval", "STAFF", "Performance evaluations", "Reviews", "90-day and annual reviews with ratings and the next review date", ++sort, null,
				f("kind", "Review", "select", true, "90-day review", "Annual review", "Corrective action", "Other"),
				f("on", "Review date", "date", true),
				f("reviewer", "Reviewer", "text", false),
				f("rating", "Rating", "select", false, "Exceeds expectations", "Meets expectations", "Needs improvement", "Unsatisfactory"),
				f("next", "Next review due", "expiry", false),
				f("summary", "Summary", "text", false));
		state.setTrackers(true);
		seedRepository.save(state);
	}

	@Transactional
	public void ensureSpecialties(UUID orgId) {
		lock(orgId, "defaults");
		OpsSeedState state = state(orgId);
		if (state.isSpecialties()) {
			return;
		}
		List<String> baseCreds = List.of("State license", "DEA registration", "Malpractice insurance", "CAQH ProView", "Board certification");
		specialty(orgId, "Cardiology", 1,
				folders("Clinical Staffing", List.of("Echo Techs"),
						"Diagnostic Equipment & Services", List.of("Cardiac Monitors", "Stress Testing", "Echo / Ultrasound Equipment"),
						"Medical Supplies", List.of(),
						"Facilities", List.of("Rent / Lease", "Cleaning & Janitorial", "Biohazard / Medical Waste"),
						"Business Services", List.of("IT & Phones", "Billing / RCM", "Answering Service")),
				concat(baseCreds, "NRC radioactive materials license"),
				List.of("Referral reason", "Chest pain or shortness of breath right now? (if yes, direct to 911)", "Prior cardiac history or procedures", "Referring provider", "Insurance"));
		specialty(orgId, "Orthopedics", 2,
				folders("Clinical Staffing", List.of("Physical Therapists", "X-ray Techs"),
						"Imaging Equipment", List.of("X-ray / C-arm Service", "MRI Service"),
						"DME & Bracing", List.of(),
						"Implants & Surgical Supplies", List.of(),
						"Facilities", List.of("Rent / Lease", "Cleaning & Janitorial"),
						"Business Services", List.of("IT & Phones", "Billing / RCM")),
				concat(baseCreds, "Fluoroscopy permit"),
				List.of("Injury or body area", "Imaging already done?", "Workers comp or auto claim?", "Referring provider", "Insurance"));
		state.setSpecialties(true);
		seedRepository.save(state);
	}

	@Transactional
	public void ensureLibrary(UUID orgId, String space) {
		lock(orgId, "defaults");
		OpsSeedState state = state(orgId);
		boolean training = "TRAINING".equals(space);
		if (training ? state.isTraining() : state.isResources()) {
			return;
		}
		if (training) {
			folder(orgId, space, null, "All staff", 1);
			folder(orgId, space, null, "New hire orientation", 2);
			folder(orgId, space, null, "Front desk", 3);
			folder(orgId, space, null, "Medical assistants", 4);
			folder(orgId, space, null, "Nurses (RN)", 5);
			folder(orgId, space, null, "LVN / LPN", 6);
			folder(orgId, space, null, "Providers", 7);
			folder(orgId, space, null, "Billing and coding", 8);
			UUID tech = folder(orgId, space, null, "Technicians", 9);
			folder(orgId, space, tech, "Imaging", 1);
			folder(orgId, space, tech, "Diagnostics", 2);
			state.setTraining(true);
		} else {
			UUID pat = folder(orgId, space, null, "Patients", 1);
			folder(orgId, space, pat, "Consent forms", 1);
			folder(orgId, space, pat, "Letters and excuses", 2);
			folder(orgId, space, pat, "Visit and test instructions", 3);
			folder(orgId, space, pat, "Insurance and payment", 4);
			folder(orgId, space, null, "Front desk and scheduling", 2);
			folder(orgId, space, null, "Billing and coding", 3);
			folder(orgId, space, null, "Clinical protocols", 4);
			folder(orgId, space, null, "HR and staff", 5);
			folder(orgId, space, null, "Policies and compliance", 6);
			folder(orgId, space, null, "Marketing and referrals", 7);
			UUID adm = folder(orgId, space, null, "Practice admin", 8);
			folder(orgId, space, adm, "Licenses and certificates", 1);
			folder(orgId, space, adm, "Insurance policies", 2);
			folder(orgId, space, adm, "Leases and facilities", 3);
			folder(orgId, space, null, "Templates and letterhead", 9);
			state.setResources(true);
		}
		seedRepository.save(state);
	}

	/** Default folders for one vendor's file cabinet. */
	@Transactional
	public void ensureVendorFolders(UUID orgId, UUID vendorId) {
		lock(vendorId, "vendor-folders");
		if (folderRepository.countByOrganizationIdAndSpaceAndVendorId(orgId, "VENDOR", vendorId) > 0) {
			return;
		}
		String[] names = { "Contracts", "BAA and compliance", "Invoices", "Insurance (COI)", "Quotes and proposals", "Correspondence", "Other" };
		int i = 0;
		for (String n : names) {
			OpsFolder f = new OpsFolder();
			f.setOrganizationId(orgId);
			f.setSpace("VENDOR");
			f.setVendorId(vendorId);
			f.setName(n);
			f.setLocked("Invoices".equals(n));
			f.setSortOrder(++i);
			folderRepository.save(f);
		}
	}

	/* ── helpers ─────────────────────────────────────────────────────── */

	/**
	 * Serializes default creation for one organization (or vendor) until the transaction ends, so two
	 * first page loads arriving at once cannot both create the defaults.
	 */
	private void lock(UUID id, String kind) {
		entityManager.createNativeQuery("select 1 from (select pg_advisory_xact_lock(hashtext(:k))) l")
				.setParameter("k", kind + ":" + id)
				.getSingleResult();
	}

	private OpsSeedState state(UUID orgId) {
		return seedRepository.findById(orgId).orElseGet(() -> {
			OpsSeedState s = new OpsSeedState();
			s.setOrganizationId(orgId);
			return s;
		});
	}

	@SafeVarargs
	private void tab(UUID orgId, String code, String scope, String name, String group, String desc, int sort, Map<String, Object> goal, Map<String, Object>... fields) {
		TrackerTab t = new TrackerTab();
		t.setOrganizationId(orgId);
		t.setCode(code);
		t.setScope(scope);
		t.setName(name);
		t.setGroupName(group);
		t.setDescription(desc);
		t.setFields(new ArrayList<>(List.of(fields)));
		t.setGoal(goal);
		t.setStandard(true);
		t.setSortOrder(sort);
		tabRepository.save(t);
	}

	static Map<String, Object> f(String id, String label, String type, boolean required, String... options) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("label", label);
		m.put("type", type);
		m.put("required", required);
		m.put("options", List.of(options));
		m.put("inTable", !"file".equals(type) && !"notes".equals(id) && !"summary".equals(id));
		return m;
	}

	private void specialty(UUID orgId, String name, int sort, List<Map<String, Object>> folders, List<String> creds, List<String> intake) {
		SpecialtyTemplate s = new SpecialtyTemplate();
		s.setOrganizationId(orgId);
		s.setName(name);
		s.setFolders(folders);
		s.setCredentials(new ArrayList<>(creds));
		s.setIntake(new ArrayList<>(intake));
		s.setSystemTemplate(true);
		s.setSortOrder(sort);
		specialtyRepository.save(s);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> folders(Object... pairs) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			String top = (String) pairs[i];
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", top);
			m.put("parent", null);
			out.add(m);
			for (String child : (List<String>) pairs[i + 1]) {
				Map<String, Object> c = new LinkedHashMap<>();
				c.put("name", child);
				c.put("parent", top);
				out.add(c);
			}
		}
		return out;
	}

	private static List<String> concat(List<String> a, String b) {
		List<String> out = new ArrayList<>(a);
		out.add(b);
		return out;
	}

	private UUID folder(UUID orgId, String space, UUID parent, String name, int sort) {
		OpsFolder f = new OpsFolder();
		f.setOrganizationId(orgId);
		f.setSpace(space);
		f.setParentId(parent);
		f.setName(name);
		f.setSortOrder(sort);
		return folderRepository.save(f).getId();
	}
}
