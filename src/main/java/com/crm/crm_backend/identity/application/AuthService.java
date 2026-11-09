package com.crm.crm_backend.identity.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.identity.api.dto.AuthResponse;
import com.crm.crm_backend.identity.api.dto.LoginRequest;
import com.crm.crm_backend.identity.api.dto.MeResponse;
import com.crm.crm_backend.identity.api.dto.ResendCodeRequest;
import com.crm.crm_backend.identity.api.dto.SignupRequest;
import com.crm.crm_backend.identity.api.dto.SignupResponse;
import com.crm.crm_backend.identity.api.dto.VerifyEmailRequest;
import com.crm.crm_backend.identity.domain.User;
import com.crm.crm_backend.identity.infrastructure.UserRepository;
import com.crm.crm_backend.onboarding.application.VerificationService;
import com.crm.crm_backend.organization.application.SlugService;
import com.crm.crm_backend.organization.domain.Membership;
import com.crm.crm_backend.organization.domain.OrgRole;
import com.crm.crm_backend.organization.domain.Organization;
import com.crm.crm_backend.organization.infrastructure.MembershipRepository;
import com.crm.crm_backend.organization.infrastructure.OrganizationRepository;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.security.AppProperties;
import com.crm.crm_backend.shared.security.JwtService;
import com.crm.crm_backend.shared.security.UserPrincipal;

@Service
public class AuthService {

	private static final Set<String> ALLOWED_SERVICES = Set.of("crm", "ops", "mkt", "tc");

	private final UserRepository userRepository;
	private final OrganizationRepository organizationRepository;
	private final MembershipRepository membershipRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final VerificationService verificationService;
	private final SlugService slugService;
	private final AppProperties appProperties;

	public AuthService(
			UserRepository userRepository,
			OrganizationRepository organizationRepository,
			MembershipRepository membershipRepository,
			PasswordEncoder passwordEncoder,
			JwtService jwtService,
			VerificationService verificationService,
			SlugService slugService,
			AppProperties appProperties
	) {
		this.userRepository = userRepository;
		this.organizationRepository = organizationRepository;
		this.membershipRepository = membershipRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.verificationService = verificationService;
		this.slugService = slugService;
		this.appProperties = appProperties;
	}

	@Transactional
	public SignupResponse signup(SignupRequest request) {
		String email = request.email().trim().toLowerCase();
		if (userRepository.existsByEmailIgnoreCase(email)) {
			throw ApiException.conflict("An account with this email already exists.");
		}

		User user = new User();
		user.setEmail(email);
		user.setFullName(request.fullName().trim());
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setPhone(blankToNull(request.phone()));
		user.setEmailVerified(false);
		userRepository.save(user);

		Instant trialStart = Instant.now();
		Organization org = new Organization();
		org.setName(request.organizationName().trim());
		org.setSlug(slugService.uniqueSlug(request.organizationName()));
		org.setTeamSizeBand(request.teamSizeBand());
		org.setTrialStartsAt(trialStart);
		org.setTrialEndsAt(trialStart.plus(appProperties.trial().days(), ChronoUnit.DAYS));
		org.setTermsAccepted(request.termsAccepted());
		org.setNewsOptIn(request.newsOptIn());
		org.setOnboardingDone(false);
		org.setEnabledServices(new HashSet<>(Set.of("crm", "ops", "mkt", "tc")));
		organizationRepository.save(org);

		Membership membership = new Membership();
		membership.setUserId(user.getId());
		membership.setOrganizationId(org.getId());
		membership.setRole(OrgRole.OWNER);
		membershipRepository.save(membership);

		verificationService.issueCode(email);

		boolean requiresDemo = request.teamSizeBand() != null && request.teamSizeBand() > 300;
		return new SignupResponse(
				"Account created. Check your email for a verification code.",
				email,
				requiresDemo
		);
	}

	@Transactional
	public AuthResponse verifyEmail(VerifyEmailRequest request) {
		String email = request.email().trim().toLowerCase();
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> ApiException.notFound("No account found for that email."));

		verificationService.verifyCode(email, request.code());
		user.setEmailVerified(true);
		userRepository.save(user);

		return issueAuth(user);
	}

	@Transactional
	public void resendCode(ResendCodeRequest request) {
		String email = request.email().trim().toLowerCase();
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> ApiException.notFound("No account found for that email."));
		if (user.isEmailVerified()) {
			throw ApiException.badRequest("Email is already verified.");
		}
		verificationService.issueCode(email);
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		String email = request.email().trim().toLowerCase();
		User user = userRepository.findByEmailIgnoreCase(email)
				.orElseThrow(() -> ApiException.unauthorized("Invalid email or password."));
		if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
			throw ApiException.unauthorized("Invalid email or password.");
		}
		return issueAuth(user);
	}

	@Transactional(readOnly = true)
	public MeResponse me(UserPrincipal principal) {
		User user = userRepository.findById(principal.userId())
				.orElseThrow(() -> ApiException.unauthorized("User not found."));
		Organization org = organizationRepository.findById(principal.organizationId())
				.orElseThrow(() -> ApiException.unauthorized("Organization not found."));
		Membership membership = membershipRepository.findByUserIdAndOrganizationId(user.getId(), org.getId())
				.orElseThrow(() -> ApiException.forbidden("You are not a member of this organization."));
		return toMe(user, org, membership.getRole().name());
	}

	@Transactional
	public MeResponse completeWelcome(UserPrincipal principal, String orgType, String ehrName, Set<String> services) {
		Organization org = organizationRepository.findById(principal.organizationId())
				.orElseThrow(() -> ApiException.notFound("Organization not found."));
		if (orgType != null && !orgType.isBlank()) {
			org.setOrgType(orgType.trim());
		}
		if (ehrName != null && !ehrName.isBlank()) {
			org.setEhrName(ehrName.trim());
		}
		if (services != null && !services.isEmpty()) {
			Set<String> cleaned = new HashSet<>();
			for (String service : services) {
				if (ALLOWED_SERVICES.contains(service)) {
					cleaned.add(service);
				}
			}
			if (!cleaned.isEmpty()) {
				org.setEnabledServices(cleaned);
			}
		}
		org.setOnboardingDone(true);
		organizationRepository.save(org);

		User user = userRepository.findById(principal.userId())
				.orElseThrow(() -> ApiException.unauthorized("User not found."));
		return toMe(user, org, principal.role());
	}

	private AuthResponse issueAuth(User user) {
		Membership membership = membershipRepository.findByUserId(user.getId())
				.orElseThrow(() -> ApiException.forbidden("No organization membership found."));
		Organization org = organizationRepository.findById(membership.getOrganizationId())
				.orElseThrow(() -> ApiException.notFound("Organization not found."));

		String token = jwtService.generateToken(
				user.getId(),
				org.getId(),
				user.getEmail(),
				membership.getRole().name(),
				user.isEmailVerified()
		);
		return new AuthResponse(token, toMe(user, org, membership.getRole().name()));
	}

	private MeResponse toMe(User user, Organization org, String role) {
		return new MeResponse(
				user.getId(),
				user.getEmail(),
				user.getFullName(),
				user.isEmailVerified(),
				org.getId(),
				org.getName(),
				role,
				org.getTrialStartsAt(),
				org.getTrialEndsAt(),
				org.isOnboardingDone(),
				org.getEnabledServices()
		);
	}

	private String blankToNull(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
