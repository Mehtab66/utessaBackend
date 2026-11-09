package com.crm.crm_backend.onboarding.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.onboarding.domain.EmailVerification;
import com.crm.crm_backend.onboarding.infrastructure.EmailVerificationRepository;
import com.crm.crm_backend.shared.exception.ApiException;

@Service
public class VerificationService {

	private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final EmailVerificationRepository verificationRepository;
	private final PasswordEncoder passwordEncoder;

	public VerificationService(EmailVerificationRepository verificationRepository, PasswordEncoder passwordEncoder) {
		this.verificationRepository = verificationRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public void issueCode(String email) {
		String code = String.format("%06d", RANDOM.nextInt(1_000_000));
		EmailVerification verification = new EmailVerification();
		verification.setEmail(email.trim().toLowerCase());
		verification.setCodeHash(passwordEncoder.encode(code));
		verification.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
		verificationRepository.save(verification);
		log.info("[DEV] Email verification code for {}: {}", email, code);
	}

	@Transactional
	public void verifyCode(String email, String code) {
		EmailVerification verification = verificationRepository
				.findFirstByEmailIgnoreCaseAndConsumedAtIsNullOrderByCreatedAtDesc(email.trim().toLowerCase())
				.orElseThrow(() -> ApiException.badRequest("No verification code found. Request a new one."));

		if (verification.isExpired()) {
			throw ApiException.badRequest("That code has expired. Request a new one.");
		}
		if (!passwordEncoder.matches(code, verification.getCodeHash())) {
			throw ApiException.badRequest("Incorrect verification code.");
		}
		verification.setConsumedAt(Instant.now());
		verificationRepository.save(verification);
	}
}
