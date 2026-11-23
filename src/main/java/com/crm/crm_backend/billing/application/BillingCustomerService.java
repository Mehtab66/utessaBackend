package com.crm.crm_backend.billing.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.crm.crm_backend.billing.api.dto.BillingDtos.AddressDto;
import com.crm.crm_backend.billing.api.dto.BillingDtos.BillingDetailsRequest;
import com.crm.crm_backend.billing.api.dto.BillingDtos.CustomerResponse;
import com.crm.crm_backend.billing.domain.OperationType;
import com.crm.crm_backend.billing.domain.OrganizationSubscription;
import com.crm.crm_backend.billing.infrastructure.OrganizationSubscriptionRepository;
import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.UserPrincipal;
import com.stripe.model.Customer;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;

/**
 * One Stripe customer per organization: created once, stored, and reused for everything after.
 * Duplicates are prevented three ways: the stored id, a Stripe-side search by organization
 * metadata (in case our write was lost), and idempotency keys on creation.
 */
@Service
public class BillingCustomerService {

	private static final Logger log = LoggerFactory.getLogger(BillingCustomerService.class);

	private final StripeGateway gateway;
	private final BillingAccess access;
	private final IdempotencyService idempotency;
	private final OrganizationSubscriptionRepository subscriptions;
	private final SubscriptionSyncService sync;
	private final TransactionTemplate tx;

	public BillingCustomerService(StripeGateway gateway, BillingAccess access, IdempotencyService idempotency,
			OrganizationSubscriptionRepository subscriptions, SubscriptionSyncService sync, PlatformTransactionManager txManager) {
		this.gateway = gateway;
		this.access = access;
		this.idempotency = idempotency;
		this.subscriptions = subscriptions;
		this.sync = sync;
		this.tx = new TransactionTemplate(txManager);
	}

	/** POST /billing/customer — makes sure the organization has a Stripe customer. */
	public CustomerResponse create(UserPrincipal principal, String idempotencyKey, BillingDetailsRequest details) {
		UUID orgId = access.requireBillingAdmin(principal);
		BillingDetailsRequest d = details == null ? new BillingDetailsRequest(null, null, null, null) : details;
		return idempotency.execute(orgId, principal.userId(), idempotencyKey, OperationType.CREATE_CUSTOMER, d,
				CustomerResponse.class, ctx -> {
					String customerId = ensureCustomer(orgId, ctx, d);
					Customer c = gateway.retrieveCustomer(customerId);
					return new IdempotencyService.Result<>(toResponse(c), customerId);
				});
	}

	/** PUT /billing/customer — updates the billing details shown on invoices. */
	public CustomerResponse update(UserPrincipal principal, String idempotencyKey, BillingDetailsRequest details) {
		UUID orgId = access.requireBillingAdmin(principal);
		if (details == null) {
			throw ApiException.badRequest("Send the billing details to update.");
		}
		return idempotency.execute(orgId, principal.userId(), idempotencyKey, OperationType.UPDATE_CUSTOMER, details,
				CustomerResponse.class, ctx -> {
					String customerId = ensureCustomer(orgId, ctx, null);
					CustomerUpdateParams.Builder b = CustomerUpdateParams.builder();
					if (notBlank(details.name())) {
						b.setName(details.name().trim());
					}
					if (notBlank(details.email())) {
						b.setEmail(details.email().trim());
					}
					if (notBlank(details.phone())) {
						b.setPhone(details.phone().trim());
					}
					if (details.address() != null) {
						AddressDto a = details.address();
						b.setAddress(CustomerUpdateParams.Address.builder()
								.setLine1(trim(a.line1())).setLine2(trim(a.line2())).setCity(trim(a.city()))
								.setState(trim(a.state())).setPostalCode(trim(a.postalCode()))
								.setCountry(a.country() == null ? null : a.country().trim().toUpperCase())
								.build());
					}
					Customer c = gateway.updateCustomer(customerId, b.build(), ctx.stripeKey("customer-update"));
					return new IdempotencyService.Result<>(toResponse(c), customerId);
				});
	}

	/** GET /billing/customer */
	public CustomerResponse get(UserPrincipal principal) {
		UUID orgId = access.requireBillingAdmin(principal);
		String customerId = subscriptions.findByOrganizationId(orgId).map(OrganizationSubscription::getStripeCustomerId).orElse(null);
		if (customerId == null) {
			return new CustomerResponse(false, null, null, null, null);
		}
		return toResponse(gateway.retrieveCustomer(customerId));
	}

	/**
	 * Returns the organization's Stripe customer id, creating the customer if needed. Must be
	 * called inside an idempotent operation (which guarantees one billing change at a time).
	 */
	String ensureCustomer(UUID orgId, IdempotencyService.Context ctx, BillingDetailsRequest details) {
		String existing = subscriptions.findByOrganizationId(orgId).map(OrganizationSubscription::getStripeCustomerId).orElse(null);
		if (existing != null) {
			return existing;
		}
		String customerId = gateway.findCustomerForOrganization(orgId).map(Customer::getId).orElse(null);
		if (customerId != null) {
			log.info("Reusing existing Stripe customer {} for organization {}", customerId, orgId);
		} else {
			Organization org = access.organization(orgId);
			User owner = access.owner(orgId).orElse(null);
			CustomerCreateParams.Builder b = CustomerCreateParams.builder()
					.setName(details != null && notBlank(details.name()) ? details.name().trim() : org.getName())
					.setEmail(details != null && notBlank(details.email()) ? details.email().trim() : owner == null ? null : owner.getEmail())
					.putMetadata(StripeGateway.ORG_METADATA, orgId.toString())
					.putMetadata("organization_slug", org.getSlug());
			if (details != null && notBlank(details.phone())) {
				b.setPhone(details.phone().trim());
			}
			if (details != null && details.address() != null) {
				AddressDto a = details.address();
				b.setAddress(CustomerCreateParams.Address.builder()
						.setLine1(trim(a.line1())).setLine2(trim(a.line2())).setCity(trim(a.city()))
						.setState(trim(a.state())).setPostalCode(trim(a.postalCode()))
						.setCountry(a.country() == null ? null : a.country().trim().toUpperCase())
						.build());
			}
			customerId = gateway.createCustomer(b.build(), ctx.stripeKey("customer")).getId();
			log.info("Created Stripe customer {} for organization {}", customerId, orgId);
		}
		String id = customerId;
		sync.ensureRow(orgId, id);
		tx.executeWithoutResult(s -> {
			OrganizationSubscription row = subscriptions.lockByOrganizationId(orgId).orElseThrow();
			if (row.getStripeCustomerId() == null) {
				row.setStripeCustomerId(id);
				subscriptions.save(row);
			}
		});
		return subscriptions.findByOrganizationId(orgId).map(OrganizationSubscription::getStripeCustomerId).orElse(id);
	}

	private static CustomerResponse toResponse(Customer c) {
		AddressDto address = c.getAddress() == null ? null : new AddressDto(c.getAddress().getLine1(), c.getAddress().getLine2(),
				c.getAddress().getCity(), c.getAddress().getState(), c.getAddress().getPostalCode(), c.getAddress().getCountry());
		return new CustomerResponse(true, c.getName(), c.getEmail(), c.getPhone(), address);
	}

	private static boolean notBlank(String v) {
		return v != null && !v.isBlank();
	}

	private static String trim(String v) {
		return v == null || v.isBlank() ? null : v.trim();
	}
}
