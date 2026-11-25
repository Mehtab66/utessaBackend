package com.crm.crm_backend.assistant.application;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.crm.crm_backend.shared.exception.ApiException;
import com.crm.crm_backend.shared.net.SystemTrust;
import com.crm.crm_backend.shared.security.UserPrincipal;

/**
 * "Ask Utessa AI": answers staff questions about their practice's CRM data with Claude.
 *
 * <p>Runs a tool-use loop on the Messages API in this backend (not Managed Agents), so patient data
 * only ever goes to the Claude API from here and stays under the practice's own Anthropic account
 * and BAA. The tools are read-only; the assistant can look things up but never changes a record.
 */
@Service
public class AssistantService {

	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
	private static final int MAX_STEPS = 8;
	private static final int MAX_HISTORY = 30;
	private static final int MAX_CHARS = 4000;

	private final AssistantTools tools;
	private final String model;
	private final AnthropicClient client; // null when no API key is configured

	public AssistantService(AssistantTools tools,
			@Value("${assistant.api-key:}") String apiKey,
			@Value("${assistant.model:claude-opus-5-5}") String model) {
		this.tools = tools;
		this.model = model;
		this.client = apiKey == null || apiKey.isBlank() ? null
				: AnthropicOkHttpClient.builder()
						.apiKey(apiKey.trim())
						// JDK + Windows/macOS trust store, same as Stripe and RingCentral calls
						.sslSocketFactory(SystemTrust.sslContext().getSocketFactory())
						.trustManager(SystemTrust.trustManager())
						.timeout(Duration.ofSeconds(120))
						.build();
	}

	public boolean configured() {
		return client != null;
	}

	public record ChatTurn(String role, String content) {
	}

	public record ChatReply(String reply, List<String> lookups) {
	}

	public ChatReply chat(UserPrincipal p, List<ChatTurn> history, String tz) {
		if (client == null) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"The AI assistant isn't set up yet. Ask your administrator to add an Anthropic API key.");
		}
		List<MessageParam> messages = toMessages(history);
		Set<String> lookups = new LinkedHashSet<>();

		for (int step = 0; step < MAX_STEPS; step++) {
			Message res = call(messages, tz);
			StopReason stop = res.stopReason().orElse(StopReason.END_TURN);

			if (StopReason.REFUSAL.equals(stop)) {
				return new ChatReply("I can't help with that one. Try asking about your patients, leads, tasks or schedule.", List.copyOf(lookups));
			}
			if (StopReason.TOOL_USE.equals(stop)) {
				messages.add(res.toParam());
				List<ContentBlockParam> results = new ArrayList<>();
				for (ContentBlock block : res.content()) {
					block.toolUse().ifPresent(use -> {
						lookups.add(use.name());
						results.add(ContentBlockParam.ofToolResult(tools.run(p, tz, use)));
					});
				}
				messages.add(MessageParam.ofUserOfContentBlockParams(results));
				continue;
			}

			String text = res.content().stream()
					.flatMap(b -> b.text().stream())
					.map(TextBlock::text)
					.collect(Collectors.joining("\n\n"))
					.trim();
			if (StopReason.MAX_TOKENS.equals(stop)) {
				text += "\n\n_(Answer cut short. Ask a narrower question for the rest.)_";
			}
			return new ChatReply(text.isEmpty() ? "I couldn't find an answer to that." : text, List.copyOf(lookups));
		}
		return new ChatReply("That question needed too many lookups. Try asking something narrower.", List.copyOf(lookups));
	}

	private Message call(List<MessageParam> messages, String tz) {
		MessageCreateParams.Builder params = MessageCreateParams.builder()
				.model(model)
				.maxTokens(16000L)
				.system(systemPrompt(tz))
				// Chat-style lookups: medium keeps answers quick; thinking stays adaptive (default).
				.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
				// If a safety classifier declines, let the API retry on its default fallback model.
				.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
				.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
				.messages(messages);
		tools.definitions().forEach(params::addTool);
		try {
			return client.messages().create(params.build());
		} catch (RateLimitException e) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The AI assistant is busy right now. Try again in a minute.");
		} catch (AnthropicServiceException e) {
			log.warn("Claude API error {}: {}", e.statusCode(), e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, "The AI service returned an error. Try again.");
		} catch (AnthropicException e) {
			log.warn("Claude API unreachable: {}", e.getMessage());
			throw new ApiException(HttpStatus.BAD_GATEWAY, "Couldn't reach the AI service. Check the server's internet connection.");
		}
	}

	private static List<MessageParam> toMessages(List<ChatTurn> history) {
		if (history == null || history.isEmpty()) {
			throw ApiException.badRequest("Ask a question first.");
		}
		List<ChatTurn> recent = history.size() > MAX_HISTORY ? history.subList(history.size() - MAX_HISTORY, history.size()) : history;
		List<MessageParam> out = new ArrayList<>();
		String expected = "user";
		for (ChatTurn t : recent) {
			String content = t.content() == null ? "" : t.content().trim();
			if (content.isEmpty()) {
				continue;
			}
			if (content.length() > MAX_CHARS) {
				content = content.substring(0, MAX_CHARS);
			}
			if (out.isEmpty() && !"user".equals(t.role())) {
				continue; // a trimmed history must start with the user
			}
			if (!expected.equals(t.role())) {
				throw ApiException.badRequest("The conversation is out of order. Start a new chat.");
			}
			out.add("user".equals(t.role()) ? MessageParam.ofUser(content) : MessageParam.ofAssistant(content));
			expected = "user".equals(expected) ? "assistant" : "user";
		}
		if (out.isEmpty() || "user".equals(expected)) {
			throw ApiException.badRequest("Ask a question first.");
		}
		return out;
	}

	private static String systemPrompt(String tz) {
		return """
				You are Utessa AI, the assistant inside Utessa, a CRM and operations app for medical practices. \
				The people asking are the practice's own staff: front desk, care coordinators, managers.

				How to work:
				- Answer from the practice's data by using your tools. Never guess or invent patients, numbers or dates; \
				if the tools don't show it, say you couldn't find it.
				- When a question names a person, look them up with search_records first, then get_patient for details.
				- You can only read data. If someone asks you to change, create, delete, call or text anything, \
				say you can't do that yet and tell them where in Utessa to do it (for example the patient's page in CRM, \
				Tasks, or the Calendar).
				- Share only the patient details the question needs. Don't repeat insurance IDs or other identifiers \
				unless asked.
				- Keep answers short and scannable: a one-line answer first, then a short list if there are several items. \
				Use names and dates people recognise, not internal ids.
				- If a tool says the user doesn't have access, explain that their role can't see that area.
				- For anything clinical (diagnosis, dosing, treatment), say that's for the clinician to decide.

				Today is %s. The user's time zone is %s; give times in it.
				""".formatted(LocalDate.now(AssistantTools.zone(tz)), tz == null || tz.isBlank() ? "the server's" : tz);
	}
}
