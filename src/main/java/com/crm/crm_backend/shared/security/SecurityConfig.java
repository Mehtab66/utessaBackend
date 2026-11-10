package com.crm.crm_backend.shared.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

	private final JwtAuthFilter jwtAuthFilter;
	private final AppProperties appProperties;

	public SecurityConfig(JwtAuthFilter jwtAuthFilter, AppProperties appProperties) {
		this.jwtAuthFilter = jwtAuthFilter;
		this.appProperties = appProperties;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.cors(Customizer.withDefaults())
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health").permitAll()
						.requestMatchers(HttpMethod.POST,
								"/api/v1/auth/signup",
								"/api/v1/auth/login",
								"/api/v1/auth/verify-email",
								"/api/v1/auth/resend-code",
								"/api/v1/auth/logout"
						).permitAll()
						.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
						.anyRequest().authenticated()
				)
				.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		List<String> origins = appProperties.cors().allowedOrigins();
		config.setAllowedOrigins(origins != null ? origins : List.of("http://localhost:5173"));
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setAllowCredentials(true);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
