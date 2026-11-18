package com.crm.crm_backend.onboarding.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.crm.crm_backend.onboarding.domain.EmailVerification;
import com.crm.crm_backend.onboarding.infrastructure.EmailVerificationRepository;
import com.crm.crm_backend.shared.exception.ApiException;

import jakarta.mail.internet.MimeMessage;

@Service
public class VerificationService {

	private static final Logger log = LoggerFactory.getLogger(VerificationService.class);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final EmailVerificationRepository verificationRepository;
	private final PasswordEncoder passwordEncoder;
	private final JavaMailSender mailSender;

	@Value("${app.mail.from:info@utessa.com}")
	private String mailFrom;

	public VerificationService(
			EmailVerificationRepository verificationRepository,
			PasswordEncoder passwordEncoder,
			@Autowired(required = false) JavaMailSender mailSender
	) {
		this.verificationRepository = verificationRepository;
		this.passwordEncoder = passwordEncoder;
		this.mailSender = mailSender;
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

		sendEmail(email.trim().toLowerCase(), code);
	}

	private void sendEmail(String toEmail, String code) {
		if (mailSender == null) {
			log.warn("JavaMailSender bean not available; verification code email not sent to {}", toEmail);
			return;
		}

		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

			helper.setFrom(mailFrom, "Utessa");
			helper.setTo(toEmail);
			helper.setSubject("Your Utessa verification code: " + code);

			String htmlBody = """
				<!DOCTYPE html>
				<html>
				<head>
				  <meta charset="utf-8">
				  <title>Verification Code</title>
				</head>
				<body style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #f7f8fb; padding: 30px; margin: 0;">
				  <div style="max-width: 480px; margin: 0 auto; background: #ffffff; border-radius: 8px; padding: 32px; border: 1px solid #e5e7eb; box-shadow: 0 1px 3px rgba(0,0,0,0.05);">
				    <h2 style="color: #0f172a; margin-top: 0; font-size: 20px;">Verify your email</h2>
				    <p style="color: #475569; font-size: 14px; line-height: 1.5;">Welcome to Utessa. Please enter the following 6-digit verification code to complete your signup:</p>
				    <div style="text-align: center; margin: 28px 0;">
				      <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #0A7A82; background: #F0F9F8; padding: 14px 28px; border-radius: 6px; display: inline-block; font-family: monospace;">
				        """ + code + """
				      </span>
				    </div>
				    <p style="color: #64748b; font-size: 13px; line-height: 1.5;">This code will expire in 10 minutes. If you did not request this verification, you can safely ignore this email.</p>
				    <hr style="border: none; border-top: 1px solid #f1f5f9; margin: 24px 0;">
				    <p style="color: #94a3b8; font-size: 12px; margin: 0;">Utessa CRM &bull; Healthcare Practice Operations</p>
				  </div>
				</body>
				</html>
				""";

			helper.setText("Your Utessa verification code is: " + code + "\n\nThis code will expire in 10 minutes.", htmlBody);

			mailSender.send(message);
			log.info("Sent verification code email to {}", toEmail);
		} catch (Exception ex) {
			log.error("Failed to send verification code email to {}: {}", toEmail, ex.getMessage(), ex);
		}
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
