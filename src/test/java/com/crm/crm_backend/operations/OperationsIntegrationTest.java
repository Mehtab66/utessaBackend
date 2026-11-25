package com.crm.crm_backend.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.crm.crm_backend.billing.BillingIntegrationTestBase;
import com.crm.crm_backend.billing.domain.BillingInterval;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.domain.SubscriptionItem;
import com.crm.crm_backend.billing.infrastructure.SubscriptionItemRepository;
import com.crm.crm_backend.operations.domain.Asset;
import com.crm.crm_backend.operations.infrastructure.AssetRepository;
import com.crm.crm_backend.shared.security.JwtService;
import com.crm.crm_backend.shared.security.SessionCookieService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/** Practice Operations end to end over HTTP: credentialing, compliance, vault, inventory and module gating. */
@AutoConfigureMockMvc
class OperationsIntegrationTest extends BillingIntegrationTestBase {

	@Autowired
	MockMvc mvc;
	@Autowired
	JwtService jwt;
	@Autowired
	ObjectMapper json;
	@Autowired
	AssetRepository assets;
	@Autowired
	SubscriptionItemRepository items;

	private Cookie session(UUID orgId, String role) {
		String token = jwt.generateToken(UUID.randomUUID(), orgId, role.toLowerCase() + "@example.test", role, true, false);
		return new Cookie(SessionCookieService.COOKIE_NAME, token);
	}

	private JsonNode body(MvcResult r) throws Exception {
		return json.readTree(r.getResponse().getContentAsString());
	}

	private String provider(UUID org, Cookie owner) throws Exception {
		String req = """
				{"firstName":"Ada","lastName":"Nguyen","jobTitle":"Cardiologist","category":"Provider","provider":true,
				 "status":"ACTIVE","npi":"1234567893","credentials":[]}
				""";
		MvcResult r = mvc.perform(post("/api/v1/providers").cookie(owner).contentType(MediaType.APPLICATION_JSON).content(req))
				.andExpect(status().isCreated()).andReturn();
		return body(r).get("id").asText();
	}

	@Test
	void credentialingFlowsIntoProviderCompliance() throws Exception {
		UUID org = practice(1).getId();
		Cookie owner = session(org, "OWNER");
		String staffId = provider(org, owner);

		mvc.perform(get("/api/v1/operations/payers").cookie(owner)).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(12)));

		MvcResult created = mvc.perform(post("/api/v1/operations/enrollments").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"staffId\":\"" + staffId + "\",\"allPayers\":true,\"status\":\"Application submitted\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$", hasSize(12))).andReturn();
		JsonNode first = body(created).get(0);

		mvc.perform(put("/api/v1/operations/enrollments/" + first.get("id").asText()).cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"staffId\":\"" + staffId + "\",\"status\":\"Additional info requested\",\"note\":\"Need updated COI\",\"followUpOn\":\"" + LocalDate.now() + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.history", hasSize(2)))
				.andExpect(jsonPath("$.history[1].note").value("Need updated COI"));

		// A second "all payers" request skips payers that already have a record
		mvc.perform(post("/api/v1/operations/enrollments").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"staffId\":\"" + staffId + "\",\"allPayers\":true,\"status\":\"Not started\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$", hasSize(0)));

		// CME tracker row counts toward the goal
		MvcResult tabs = mvc.perform(get("/api/v1/operations/trackers?scope=PROVIDER").cookie(owner)).andExpect(status().isOk()).andReturn();
		String cme = null;
		for (JsonNode t : body(tabs)) {
			if ("cme".equals(t.get("code").asText())) {
				cme = t.get("id").asText();
			}
		}
		assertThat(cme).isNotNull();
		mvc.perform(post("/api/v1/operations/trackers/rows").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"tabId\":\"" + cme + "\",\"staffId\":\"" + staffId + "\",\"vals\":{\"title\":\"Heart failure update\",\"credits\":12,\"done\":\"" + LocalDate.now().minusMonths(2) + "\"}}"))
				.andExpect(status().isCreated());
		// Required tracker fields are enforced
		mvc.perform(post("/api/v1/operations/trackers/rows").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"tabId\":\"" + cme + "\",\"staffId\":\"" + staffId + "\",\"vals\":{\"credits\":2}}"))
				.andExpect(status().isBadRequest());

		MvcResult comp = mvc.perform(get("/api/v1/operations/compliance/providers").cookie(owner)).andExpect(status().isOk()).andReturn();
		JsonNode p = body(comp).get(0);
		assertThat(p.get("status").asText()).isEqualTo("Action needed");
		assertThat(p.get("cmeCredits").asDouble()).isEqualTo(12.0);
		assertThat(p.get("payersTotal").asInt()).isEqualTo(12);
		mvc.perform(get("/api/v1/operations/compliance/providers").cookie(owner))
				.andExpect(jsonPath("$[0].openItems[*].label", hasItem("State license not on file")))
				.andExpect(jsonPath("$[0].openItems[*].label", hasItem("Malpractice insurance not on file")));

		// Front desk has no credentialing access
		mvc.perform(get("/api/v1/operations/enrollments").cookie(session(org, "FRONT"))).andExpect(status().isForbidden());
	}

	@Test
	void vaultFilesAreStoredAndRestrictedToStaffAdmins() throws Exception {
		UUID org = practice(1).getId();
		Cookie owner = session(org, "OWNER");
		String staffId = provider(org, owner);
		MockMultipartFile file = new MockMultipartFile("file", "license.pdf", "application/pdf", "%PDF-1.4 test".getBytes());

		MvcResult up = mvc.perform(multipart("/api/v1/operations/files").file(file).cookie(owner)
						.param("space", "VAULT").param("staffId", staffId).param("category", "Licenses and certifications")
						.param("docType", "License copy").param("expiresOn", LocalDate.now().plusDays(30).toString()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.docType").value("License copy"))
				.andReturn();
		String id = body(up).get("id").asText();

		mvc.perform(get("/api/v1/operations/files/" + id + "/content").cookie(owner))
				.andExpect(status().isOk())
				.andExpect(content().bytes("%PDF-1.4 test".getBytes()));
		mvc.perform(get("/api/v1/operations/files?space=VAULT&staffId=" + staffId).cookie(owner)).andExpect(jsonPath("$", hasSize(1)));
		mvc.perform(get("/api/v1/operations/files?space=VAULT").cookie(session(org, "FRONT"))).andExpect(status().isForbidden());

		// Everyone can read training; front desk cannot write it
		mvc.perform(get("/api/v1/operations/folders?space=TRAINING").cookie(session(org, "FRONT"))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/operations/drafts").cookie(session(org, "FRONT")).contentType(MediaType.APPLICATION_JSON)
						.content("{\"space\":\"TRAINING\",\"prompt\":\"HIPAA basics\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/operations/drafts").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"space\":\"TRAINING\",\"prompt\":\"HIPAA privacy basics\",\"type\":\"guide\",\"audience\":\"Front desk\",\"quiz\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.matched").value(true))
				.andExpect(jsonPath("$.title").value("HIPAA privacy basics - Front desk"));
	}

	@Test
	void anyoneRequestsSuppliesAndAdminsReceiveIntoStock() throws Exception {
		UUID org = practice(1).getId();
		Cookie owner = session(org, "OWNER");
		Cookie front = session(org, "FRONT");

		MvcResult item = mvc.perform(post("/api/v1/operations/inventory/items").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Exam gloves (M)\",\"unit\":\"box\",\"qty\":2,\"reorderLevel\":4,\"price\":8.95}"))
				.andExpect(status().isCreated()).andReturn();
		String itemId = body(item).get("id").asText();
		mvc.perform(post("/api/v1/operations/inventory/items").cookie(front).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Nope\"}")).andExpect(status().isForbidden());

		MvcResult req = mvc.perform(post("/api/v1/operations/inventory/requests").cookie(front).contentType(MediaType.APPLICATION_JSON)
						.content("{\"itemId\":\"" + itemId + "\",\"qty\":3,\"priority\":\"Urgent\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("Requested"))
				.andExpect(jsonPath("$.estimate").value(26.85))
				.andReturn();
		String reqId = body(req).get("id").asText();

		mvc.perform(post("/api/v1/operations/inventory/requests/" + reqId + "/status").cookie(front).contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"Approved\"}")).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/operations/inventory/requests/" + reqId + "/status").cookie(owner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"Ordered\",\"poNumber\":\"PO-1\",\"vendorName\":\"Medline\"}")).andExpect(status().isOk());
		mvc.perform(post("/api/v1/operations/inventory/requests/" + reqId + "/status").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"Received\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.log", hasSize(3)));
		mvc.perform(get("/api/v1/operations/inventory/items").cookie(front)).andExpect(jsonPath("$[0].qty").value(5));
		// Closed requests stay closed
		mvc.perform(post("/api/v1/operations/inventory/requests/" + reqId + "/status").cookie(owner).contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"Cancelled\"}")).andExpect(status().isBadRequest());

		// Device passwords are encrypted at rest and shown only to admins
		MvcResult asset = mvc.perform(post("/api/v1/operations/inventory/assets").cookie(owner).contentType(MediaType.APPLICATION_JSON)
						.content("{\"assetType\":\"Computer\",\"name\":\"Front desk PC\",\"itUsername\":\"frontdesk\",\"itPassword\":\"S3cret!pass\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.hasPassword").value(true))
				.andReturn();
		String assetId = body(asset).get("id").asText();
		Asset stored = assets.findById(UUID.fromString(assetId)).orElseThrow();
		assertThat(stored.getItPasswordEnc()).isNotBlank().doesNotContain("S3cret");
		mvc.perform(post("/api/v1/operations/inventory/assets/" + assetId + "/password").cookie(owner))
				.andExpect(status().isOk()).andExpect(jsonPath("$.password").value("S3cret!pass"));
		mvc.perform(post("/api/v1/operations/inventory/assets/" + assetId + "/password").cookie(front)).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/operations/inventory/assets").cookie(front))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].itUsername").doesNotExist())
				.andExpect(jsonPath("$[0].hasPassword").value(false));
	}

	@Test
	void operationsApisNeedTheOperationsModule() throws Exception {
		UUID org = practice(1).getId();
		OrganizationSubscription row = billingRow(org, "cus_ops_" + UUID.randomUUID(), "sub_ops_" + UUID.randomUUID());
		row.setSubscriptionStatus("active");
		row.setBillingInterval(BillingInterval.MONTHLY);
		row.setPaidBillingStartedAt(Instant.now());
		subscriptions.saveAndFlush(row);
		SubscriptionItem crm = new SubscriptionItem();
		crm.setSubscriptionId(row.getId());
		crm.setModuleKey("crm");
		crm.setStripeSubscriptionItemId("si_ops_" + UUID.randomUUID());
		crm.setStripePriceId("price_crm_m");
		crm.setQuantity(1);
		crm.setBillingInterval(BillingInterval.MONTHLY);
		crm.setUnitAmount(3000);
		crm.setCurrency("usd");
		items.saveAndFlush(crm);

		Cookie owner = session(org, "OWNER");
		mvc.perform(get("/api/v1/operations/payers").cookie(owner)).andExpect(status().isPaymentRequired());
		mvc.perform(get("/api/v1/operations/inventory/items").cookie(owner)).andExpect(status().isPaymentRequired());
		mvc.perform(get("/api/v1/operations/folders?space=RESOURCES").cookie(owner)).andExpect(status().isPaymentRequired());
	}
}
