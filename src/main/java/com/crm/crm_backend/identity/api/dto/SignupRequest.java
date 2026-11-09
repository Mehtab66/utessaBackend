package com.crm.crm_backend.identity.api.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "Add your full name.")
		String fullName,

		@NotBlank(message = "Enter a valid work email.")
		@Email(message = "Enter a valid work email.")
		String email,

		@NotBlank(message = "Use a password of at least 12 characters.")
		@Size(min = 12, message = "Use a password of at least 12 characters.")
		String password,

		@NotBlank(message = "Add your practice name.")
		String organizationName,

		@NotNull(message = "Choose your team size.")
		@Min(value = 1, message = "Choose your team size.")
		Integer teamSizeBand,

		String phone,

		@AssertTrue(message = "Accept the terms and BAA to create your account.")
		boolean termsAccepted,

		boolean newsOptIn
) {
}
