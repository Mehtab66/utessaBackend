package com.crm.crm_backend.assistant.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.crm.crm_backend.crm.api.dto.PatientDtos.PatientResponse;
import com.crm.crm_backend.crm.application.AppointmentService;
import com.crm.crm_backend.crm.application.CrmOverviewService;
import com.crm.crm_backend.crm.application.PatientService;
import com.crm.crm_backend.crm.application.PatientService.PatientFilter;
import com.crm.crm_backend.crm.application.TaskService;
import com.crm.crm_backend.crm.domain.LeadStatus;
import com.crm.crm_backend.crm.domain.Lifecycle;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.crm.crm_backend.workspace.application.WorkspaceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The assistant's tools. Every one is read-only and calls the same service the app's own pages use,
 * with the signed-in user's principal — so the assistant sees exactly what that user may see, and
 * the existing permission and tenant checks still apply. Lists are capped and trimmed to the fields
 * a question needs (minimum-necessary PHI).
 */
@Component
public class AssistantTools {

	private static final Logger log = LoggerFactory.getLogger(AssistantTools.class);
	private static final int LIST_CAP = 40;

	private final PatientService patients;
	private final TaskService tasks;
	private final AppointmentService appointments;
	private final CrmOverviewService overview;
	private final WorkspaceService workspace;
	private final ObjectMapper json;

	public AssistantTools(PatientService patients, TaskService tasks, AppointmentService appointments,
			CrmOverviewService overview, WorkspaceService workspace, ObjectMapper json) {
		this.patients = patients;
		this.tasks = tasks;
		this.appointments = appointments;
		this.overview = overview;
		this.workspace = workspace;
		this.json = json;
	}

	/* ── Definitions ─────────────────────────────────────────────────── */

	public List<Tool> definitions() {
		return List.of(
				tool("search_records",
						"Search the practice's patients, leads, opportunities and referral partners by name, phone, email or MRN. Use this first when the user names a person.",
						Map.of("query", prop("string", "Name, phone, email or MRN to look for (at least 2 characters).")),
						List.of("query")),
				tool("list_patients",
						"List patients or leads with filters. Use for questions like 'which leads are new', 'who has an overdue follow-up', 'leads not contacted'. Returns at most 40 rows plus the total count.",
						Map.of(
								"lifecycle", enumProp("LEAD = prospective patients, PATIENT = converted, INACTIVE.", "LEAD", "PATIENT", "INACTIVE"),
								"statuses", prop("string", "Comma-separated lead statuses: NEW, ATTEMPTING_CONTACT, CONTACTED, QUALIFIED, APPOINTMENT_SCHEDULED, SHOWED, CONVERTED, NOT_QUALIFIED, LOST."),
								"follow_up", enumProp("Filter by next follow-up date.", "overdue", "today", "none"),
								"query", prop("string", "Optional free-text filter (name, phone, email).")),
						List.of()),
				tool("get_patient",
						"Get one patient or lead in detail, with their 15 most recent activities (calls, texts, notes, status changes). Use the id from search_records or list_patients.",
						Map.of("patient_id", prop("string", "The patient's id (UUID).")),
						List.of("patient_id")),
				tool("list_tasks",
						"List CRM follow-up tasks (call backs, reminders).",
						Map.of(
								"bucket", enumProp("Which tasks.", "open", "overdue", "today", "upcoming", "done"),
								"mine", prop("boolean", "true = only tasks assigned to the signed-in user.")),
						List.of()),
				tool("list_appointments",
						"List appointments between two dates (inclusive). Defaults to today through 7 days ahead.",
						Map.of(
								"from_date", prop("string", "Start date, YYYY-MM-DD."),
								"to_date", prop("string", "End date, YYYY-MM-DD.")),
						List.of()),
				tool("crm_dashboard",
						"CRM headline numbers: lead counts, conversion, funnel, lead sources, pipeline, upcoming appointments, due tasks and hot leads.",
						Map.of(), List.of()),
				tool("my_workspace_summary",
						"The signed-in user's own day: their open workspace tasks, how many are due today or overdue, upcoming calendar events and team announcements.",
						Map.of(), List.of()));
	}

	/* ── Execution ───────────────────────────────────────────────────── */

	public ToolResultBlockParam run(UserPrincipal p, String tz, ToolUseBlock call) {
		ToolResultBlockParam.Builder result = ToolResultBlockParam.builder().toolUseId(call.id());
		try {
			Map<String, Object> in = call._input().convert(new TypeReference<Map<String, Object>>() { });
			Object out = switch (call.name()) {
				case "search_records" -> overview.search(p, str(in, "query"));
				case "list_patients" -> listPatients(p, in);
				case "get_patient" -> getPatient(p, in);
				case "list_tasks" -> capped(tasks.list(p, str(in, "bucket"), null, null, Boolean.TRUE.equals(in.get("mine")), tz));
				case "list_appointments" -> listAppointments(p, in, tz);
				case "crm_dashboard" -> overview.dashboard(p, tz);
				case "my_workspace_summary" -> workspace.summary(p, tz);
				default -> throw ApiException.badRequest("Unknown tool " + call.name());
			};
			return result.content(json.writeValueAsString(out)).build();
		} catch (ApiException e) {
			// Permission, validation and not-found errors: let the model explain them to the user.
			return result.content(e.getMessage()).isError(true).build();
		} catch (IllegalArgumentException e) {
			return result.content("Invalid input: " + e.getMessage()).isError(true).build();
		} catch (Exception e) {
			log.warn("Assistant tool {} failed", call.name(), e);
			return result.content("That lookup failed on the server.").isError(true).build();
		}
	}

	private Map<String, Object> listPatients(UserPrincipal p, Map<String, Object> in) {
		String lifecycle = str(in, "lifecycle");
		String statuses = str(in, "statuses");
		List<LeadStatus> statusList = statuses == null ? List.of()
				: Arrays.stream(statuses.split(",")).map(String::trim).filter(s -> !s.isEmpty())
						.map(s -> LeadStatus.valueOf(s.toUpperCase())).toList();
		List<PatientResponse> rows = patients.list(p, new PatientFilter(
				lifecycle == null ? null : Lifecycle.valueOf(lifecycle.toUpperCase()), statusList,
				null, null, null, null, str(in, "follow_up"), str(in, "query")));
		List<Map<String, Object>> compact = rows.stream().limit(LIST_CAP).map(this::summary).toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("total", rows.size());
		out.put("shown", compact.size());
		out.put("rows", compact);
		return out;
	}

	private Map<String, Object> getPatient(UserPrincipal p, Map<String, Object> in) {
		UUID id = UUID.fromString(String.valueOf(in.get("patient_id")));
		PatientResponse r = patients.get(p, id);
		Map<String, Object> out = summary(r);
		out.put("preferredName", r.preferredName());
		out.put("email", r.email());
		out.put("preferredContact", r.preferredContact());
		out.put("bestTimeToCall", r.bestTimeToCall());
		out.put("doNotContact", r.doNotContact());
		out.put("consentSms", r.consentSms());
		out.put("interestedService", r.interestedService());
		out.put("reasonForVisit", r.reasonForVisit());
		out.put("preferredProvider", r.preferredProvider());
		out.put("insurancePayer", r.insurancePayer());
		out.put("insuranceVerified", r.insuranceVerified());
		out.put("referralPartner", r.referralPartnerName());
		out.put("lostReason", r.lostReason());
		out.put("tags", r.tags());
		out.put("notes", r.notes());
		out.put("recentActivity", patients.timeline(p, id).stream().limit(15).map(a -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", a.type());
			m.put("subject", a.subject());
			m.put("body", a.body());
			m.put("callOutcome", a.callOutcome());
			m.put("by", a.actorName());
			return m;
		}).toList());
		return out;
	}

	private Map<String, Object> summary(PatientResponse r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.id());
		m.put("name", r.fullName());
		m.put("lifecycle", r.lifecycleStage());
		m.put("status", r.leadStatus());
		m.put("priority", r.priority());
		m.put("phone", r.phone());
		m.put("source", r.source());
		m.put("owner", r.ownerName());
		m.put("nextFollowUp", r.nextFollowUp());
		m.put("lastContactAt", r.lastContactAt());
		m.put("lastCallOutcome", r.callOutcome());
		m.put("createdAt", r.createdAt());
		return m;
	}

	private Object listAppointments(UserPrincipal p, Map<String, Object> in, String tz) {
		ZoneId zone = zone(tz);
		LocalDate from = date(in, "from_date", LocalDate.now(zone));
		LocalDate to = date(in, "to_date", from.plusDays(7));
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
		return capped(appointments.list(p, start, end, null, null, null));
	}

	private static <T> Map<String, Object> capped(List<T> rows) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("total", rows.size());
		out.put("rows", rows.size() > LIST_CAP ? new ArrayList<>(rows.subList(0, LIST_CAP)) : rows);
		return out;
	}

	/* ── Helpers ─────────────────────────────────────────────────────── */

	private static String str(Map<String, Object> in, String key) {
		Object v = in.get(key);
		return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
	}

	private static LocalDate date(Map<String, Object> in, String key, LocalDate fallback) {
		String v = str(in, key);
		return v == null ? fallback : LocalDate.parse(v);
	}

	static ZoneId zone(String tz) {
		try {
			return tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
		} catch (Exception e) {
			return ZoneId.systemDefault();
		}
	}

	private static Tool tool(String name, String description, Map<String, Object> props, List<String> required) {
		Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
		props.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
		return Tool.builder()
				.name(name)
				.description(description)
				.inputSchema(Tool.InputSchema.builder().properties(pb.build()).required(required).build())
				.build();
	}

	private static Map<String, Object> prop(String type, String description) {
		return Map.of("type", type, "description", description);
	}

	private static Map<String, Object> enumProp(String description, String... values) {
		return Map.of("type", "string", "description", description, "enum", List.of(values));
	}
}
