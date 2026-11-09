package com.crm.crm_backend.onboarding.infrastructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.crm.crm_backend.onboarding.domain.EmailVerification;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {
	Optional<EmailVerification> findFirstByEmailIgnoreCaseAndConsumedAtIsNullOrderByCreatedAtDesc(String email);
}
