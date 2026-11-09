package com.crm.crm_backend.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(
		@NotBlank @Email String email,
		@NotBlank @Size(min = 6, max = 6, message = "Enter the 6-digit code.") String code
) {
}
