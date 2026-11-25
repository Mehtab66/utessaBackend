package com.crm.crm_backend.timeclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.crm.crm_backend.billing.BillingIntegrationTestBase;
import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.domain.OrgRole;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.shared.security.JwtService;
import com.crm.crm_backend.shared.security.SessionCookieService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/** Time Clock end to end over HTTP: punching, corrections, approvals, time off, payroll and permissions. */
@AutoConfigureMockMvc
class TimeClockIntegrationTest extends BillingIntegrationTestBase {

	@Autowired
	MockMvc mvc;
	@Autowired
	JwtService jwt;
	@Autowired
	ObjectMapper json;
	@Autowired
	UserRepository users;
	@Autowired
	MembershipRepository memberships;

	UUID org;
	Person owner;
	Person manager;
	Person staff;
	/** Monday of last week (UTC) — a fully finished workweek. */
	LocalDate lastMonday;

	record Person(UUID id, Cookie session, String name) {
	}

	@BeforeEach
	void setUp() throws Exception {
		org = practice(1).getId();
		owner = person("OWNER", "Olivia Owner");
		manager = person("MANAGER", "Marco Manager");
		staff = person("FRONT", "Sam Staff");
		lastMonday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
		// Weekly pay periods in UTC so last week is exactly one period.
		send(owner, put("/api/v1/timeclock/settings"), """
				{"timezone":"UTC","payPeriod":"WEEKLY","weekStart":"MONDAY","weeklyOtHours":40,"dailyOtHours":0,"roundingMinutes":0,"allowManualEntries":true}
				""").andExpect(status().isOk()).andExpect(jsonPath("$.payPeriod").value("WEEKLY"));
	}

	private Person person(String role, String name) {
		User u = new User();
		u.setEmail(role.toLowerCase() + "-" + UUID.randomUUID() + "@example.test");
		u.setPasswordHash("x");
		u.setFullName(name);
		u.setEmailVerified(true);
		u = users.saveAndFlush(u);
		Membership m = new Membership();
		m.setUserId(u.getId());
		m.setOrganizationId(org);
		m.setRole(OrgRole.valueOf(role));
		memberships.saveAndFlush(m);
		String token = jwt.generateToken(u.getId(), org, u.getEmail(), role, true, false);
		return new Person(u.getId(), new Cookie(SessionCookieService.COOKIE_NAME, token), name);
	}

	private org.springframework.test.web.servlet.ResultActions send(Person who, MockHttpServletRequestBuilder req, String body) throws Exception {
		req.cookie(who.session());
		if (body != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mvc.perform(req);
	}

	private JsonNode body(MvcResult r) throws Exception {
		return json.readTree(r.getResponse().getContentAsString());
	}

	private String shift(LocalDate day, int fromHour, int toHour, int breaks, String reason) {
		return """
				{"clockIn":"%sT%02d:00:00Z","clockOut":"%sT%02d:00:00Z","breakMinutes":%d,"reason":"%s"}
				""".formatted(day, fromHour, day, toHour, breaks, reason);
	}

	@Test
	void punchingInBreakAndOut() throws Exception {
		send(staff, get("/api/v1/timeclock/status"), null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OUT"));
		send(staff, post("/api/v1/timeclock/clock-in"), "{\"note\":\"Front desk\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("IN"))
				.andExpect(jsonPath("$.current.note").value("Front desk"));
		send(staff, post("/api/v1/timeclock/clock-in"), null).andExpect(status().isConflict());
		send(staff, post("/api/v1/timeclock/break/end"), null).andExpect(status().isConflict());
		send(staff, post("/api/v1/timeclock/break/start"), null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("BREAK"));
		send(staff, post("/api/v1/timeclock/break/start"), null).andExpect(status().isConflict());
		send(staff, post("/api/v1/timeclock/break/end"), null).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("IN"));

		// The manager sees them working; staff can't see the team board.
		MvcResult team = send(manager, get("/api/v1/timeclock/team/now"), null).andExpect(status().isOk()).andReturn();
		JsonNode row = null;
		for (JsonNode n : body(team)) {
			if (n.get("userId").asText().equals(staff.id().toString())) {
				row = n;
			}
		}
		assertThat(row).isNotNull();
		assertThat(row.get("state").asText()).isEqualTo("IN");
		send(staff, get("/api/v1/timeclock/team/now"), null).andExpect(status().isForbidden());

		send(staff, post("/api/v1/timeclock/clock-out"), null)
				.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("OUT"))
				.andExpect(jsonPath("$.today", hasSize(1)));
		send(staff, post("/api/v1/timeclock/clock-out"), null).andExpect(status().isConflict());
	}

	@Test
	void correctionsAreValidatedAndAudited() throws Exception {
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday, 9, 17, 30, ""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("reason")));
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday, 17, 9, 0, "Forgot to punch"))
				.andExpect(status().isBadRequest());
		MvcResult created = send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday, 9, 17, 30, "Forgot to punch"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.workedMinutes").value(450))
				.andExpect(jsonPath("$.source").value("MANUAL"))
				.andReturn();
		String id = body(created).get("id").asText();
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday, 16, 18, 0, "Overlapping"))
				.andExpect(status().isConflict());
		send(staff, post("/api/v1/timeclock/entries"), shift(LocalDate.now(ZoneOffset.UTC).plusDays(2), 9, 10, 0, "Future"))
				.andExpect(status().isBadRequest());

		// Staff can't touch someone else's time; the manager can.
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday, 9, 10, 0, "Not mine").replace("{", "{\"userId\":\"" + manager.id() + "\","))
				.andExpect(status().isForbidden());
		send(manager, put("/api/v1/timeclock/entries/" + id), """
				{"clockOut":"%sT18:00:00Z","reason":"Stayed late for inventory"}
				""".formatted(lastMonday)).andExpect(status().isOk()).andExpect(jsonPath("$.workedMinutes").value(510));

		send(staff, get("/api/v1/timeclock/entries/" + id + "/history"), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[1].actorName").value("Marco Manager"))
				.andExpect(jsonPath("$[1].reason").value("Stayed late for inventory"));

		send(staff, delete("/api/v1/timeclock/entries/" + id), null).andExpect(status().isBadRequest());
		send(staff, delete("/api/v1/timeclock/entries/" + id + "?reason=Duplicate shift"), null).andExpect(status().isNoContent());
	}

	@Test
	void weeklyOvertimeTimeOffAndApprovalWorkflow() throws Exception {
		for (int i = 0; i < 5; i++) {
			send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday.plusDays(i), 8, 17, 0, "Paper timesheet"))
					.andExpect(status().isCreated());
		}
		// Time off on Saturday: requested, approved, and paid on the timesheet.
		MvcResult req = send(staff, post("/api/v1/timeclock/pto"), """
				{"type":"VACATION","startDate":"%s","endDate":"%s","hours":4,"reason":"Family"}
				""".formatted(lastMonday.plusDays(5), lastMonday.plusDays(5))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING")).andReturn();
		String ptoId = body(req).get("id").asText();
		send(staff, post("/api/v1/timeclock/pto"), """
				{"type":"SICK","startDate":"%s","endDate":"%s","hours":8}
				""".formatted(lastMonday.plusDays(5), lastMonday.plusDays(6))).andExpect(status().isConflict());
		send(staff, post("/api/v1/timeclock/pto/" + ptoId + "/approve"), null).andExpect(status().isForbidden());
		send(manager, post("/api/v1/timeclock/pto/" + ptoId + "/approve"), "{\"note\":\"Enjoy\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

		String period = "?periodStart=" + lastMonday;
		send(staff, get("/api/v1/timeclock/timesheet" + period), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.periodStart").value(lastMonday.toString()))
				.andExpect(jsonPath("$.days", hasSize(7)))
				.andExpect(jsonPath("$.totals.workedMinutes").value(45 * 60))
				.andExpect(jsonPath("$.totals.overtimeMinutes").value(5 * 60))
				.andExpect(jsonPath("$.totals.ptoMinutes").value(4 * 60))
				.andExpect(jsonPath("$.totals.paidMinutes").value(49 * 60))
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.canSubmit").value(true));

		// Submit → staff can no longer edit; manager sends it back with a note; staff fixes and resubmits.
		send(staff, post("/api/v1/timeclock/timesheet/submit"), "{\"periodStart\":\"" + lastMonday + "\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday.plusDays(6), 9, 10, 0, "Sunday shift"))
				.andExpect(status().isConflict());
		String decision = "{\"userId\":\"" + staff.id() + "\",\"periodStart\":\"" + lastMonday + "\"%s}";
		send(manager, post("/api/v1/timeclock/timesheet/reject"), decision.formatted("")).andExpect(status().isBadRequest());
		send(manager, post("/api/v1/timeclock/timesheet/reject"), decision.formatted(",\"note\":\"Add Sunday\""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday.plusDays(6), 9, 10, 0, "Sunday shift"))
				.andExpect(status().isCreated());
		send(staff, post("/api/v1/timeclock/timesheet/submit"), "{\"periodStart\":\"" + lastMonday + "\"}")
				.andExpect(status().isOk());

		// Team list, then approval locks the period for everyone.
		send(manager, get("/api/v1/timeclock/team/timesheets" + period), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rows[?(@.userId=='" + staff.id() + "')].status").value("SUBMITTED"));
		send(manager, post("/api/v1/timeclock/timesheet/approve"), decision.formatted(""))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.decidedByName").value("Marco Manager"));
		send(manager, post("/api/v1/timeclock/entries"), shift(lastMonday.plusDays(6), 11, 12, 0, "Late add").replace("{", "{\"userId\":\"" + staff.id() + "\","))
				.andExpect(status().isConflict());

		// Payroll for that period, and its CSV.
		send(manager, get("/api/v1/timeclock/payroll?from=" + lastMonday + "&to=" + lastMonday.plusDays(6)), null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payPeriod").value(true))
				.andExpect(jsonPath("$.rows", hasSize(1)))
				.andExpect(jsonPath("$.rows[0].name").value("Sam Staff"))
				.andExpect(jsonPath("$.rows[0].overtimeMinutes").value(6 * 60))
				.andExpect(jsonPath("$.rows[0].timesheetStatus").value("APPROVED"));
		send(manager, get("/api/v1/timeclock/payroll.csv?from=" + lastMonday + "&to=" + lastMonday.plusDays(6)), null)
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"Sam Staff\"")))
				.andExpect(content().string(containsString("40.00,6.00,4.00,50.00")));
		send(staff, get("/api/v1/timeclock/payroll"), null).andExpect(status().isForbidden());
	}

	@Test
	void managersCannotApproveTheirOwnTimeButTheOwnerCan() throws Exception {
		send(manager, post("/api/v1/timeclock/entries"), shift(lastMonday, 9, 12, 0, "Meeting")).andExpect(status().isCreated());
		String mine = "{\"userId\":\"" + manager.id() + "\",\"periodStart\":\"" + lastMonday + "\"}";
		send(manager, post("/api/v1/timeclock/timesheet/approve"), mine).andExpect(status().isForbidden());
		send(owner, post("/api/v1/timeclock/timesheet/approve"), mine).andExpect(status().isOk());
		String own = "{\"userId\":\"" + owner.id() + "\",\"periodStart\":\"" + lastMonday + "\"}";
		send(owner, post("/api/v1/timeclock/timesheet/approve"), own).andExpect(status().isOk());
	}

	@Test
	void settingsArePermissionedAndValidated() throws Exception {
		send(staff, get("/api/v1/timeclock/settings"), null).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false));
		send(staff, put("/api/v1/timeclock/settings"), "{\"roundingMinutes\":15}").andExpect(status().isForbidden());
		send(manager, put("/api/v1/timeclock/settings"), "{\"roundingMinutes\":15}").andExpect(status().isForbidden());
		send(owner, put("/api/v1/timeclock/settings"), "{\"roundingMinutes\":7}").andExpect(status().isBadRequest());
		send(owner, put("/api/v1/timeclock/settings"), "{\"timezone\":\"Mars/Base\"}").andExpect(status().isBadRequest());
		send(owner, put("/api/v1/timeclock/settings"), "{\"payPeriod\":\"BIWEEKLY\",\"periodAnchor\":\"" + lastMonday.plusDays(2) + "\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.periodAnchor").value(lastMonday.toString()));
		send(owner, put("/api/v1/timeclock/settings"), "{\"allowManualEntries\":false}").andExpect(status().isOk());
		send(staff, post("/api/v1/timeclock/entries"), shift(lastMonday, 9, 10, 0, "Forgot")).andExpect(status().isForbidden());
		send(manager, get("/api/v1/timeclock/members"), null).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)));
		send(staff, get("/api/v1/timeclock/members"), null).andExpect(status().isForbidden());
	}
}
