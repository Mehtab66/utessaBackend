package com.crm.crm_backend.operations.application;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.crm.crm_backend.operations.api.dto.OpsDtos.DraftRequest;
import com.crm.crm_backend.operations.api.dto.OpsDtos.DraftResponse;

/**
 * Writes first drafts of training documents and practice resources from a short request.
 * Drafts come from a built-in topic library (no outside AI service is called) and are always
 * marked as drafts that must be reviewed and approved before use.
 */
@Component
public class DraftWriter {

	public static final List<String> TRAINING_TYPES = List.of("guide", "sop", "check", "quiz", "script", "comp");
	public static final List<String> RESOURCE_TYPES = List.of("handout", "policy", "sop", "form", "letter", "faq", "check");

	private record Topic(Pattern re, String title, List<String> objectives, List<String> steps, List<String> dos, List<String> donts, List<String[]> quiz) {
	}

	private record Resource(Pattern re, String title, String intro, List<String> points, List<String> call, List<String[]> faq) {
	}

	private static Pattern p(String re) {
		return Pattern.compile(re, Pattern.CASE_INSENSITIVE);
	}

	private static final List<Topic> TOPICS = List.of(
			new Topic(p("check.?in|register|intake|insurance|eligib|copay"), "Patient check-in and insurance verification",
					List.of("Greet and identify every patient with two identifiers", "Verify insurance eligibility before the visit", "Collect copays and explain financial responsibility", "Update demographics and consent forms"),
					List.of("Greet the patient by name; confirm full name and date of birth", "Scan the photo ID and insurance card (front and back)", "Run the eligibility check and note copay, deductible and referral needs", "Review and update address, phone, pharmacy and emergency contact", "Have the patient sign any missing consent or privacy forms", "Collect the copay and give a receipt", "Mark the patient as arrived and let the clinical team know"),
					List.of("Use two identifiers every time", "Ask about insurance changes at every visit"), List.of("Don't discuss balances where others can hear", "Don't skip the eligibility check when it is busy"),
					List.<String[]>of(new String[] { "What two identifiers do we use?", "Full name and date of birth" }, new String[] { "When do we verify insurance?", "Before every visit" })),
			new Topic(p("phone|call|schedul|appointment|voicemail"), "Phone etiquette and scheduling",
					List.of("Answer within three rings with the standard greeting", "Schedule the right visit type and length", "Recognize calls that need the nurse right away"),
					List.of("Answer with the practice greeting and your name", "Verify the caller with two identifiers before sharing information", "Listen for the reason for the call and pick the right visit type", "Offer the first available time and confirm it back", "Send the confirmation text or email", "Transfer clinical questions to the nurse line"),
					List.of("Smile while you talk; callers hear it", "Repeat dates and times back"), List.of("Don't give medical advice", "Don't leave a caller on hold for more than a minute without checking back"),
					List.<String[]>of(new String[] { "A caller has chest pain right now. What do you do?", "Advise them to call 911 and alert the nurse" }, new String[] { "What must you do before sharing information?", "Verify identity with two identifiers" })),
			new Topic(p("hipaa|privacy|phi|confidential"), "HIPAA privacy basics",
					List.of("Explain what PHI is", "Apply the minimum necessary rule", "Report a possible breach the same day"),
					List.of("Access only the records you need for your job", "Verify identity before sharing any information", "Keep screens locked and paper out of sight", "Use only approved, secure email and texting for patient information", "Report lost devices or misdirected information to the privacy officer the same day"),
					List.of("Lock your screen when you step away", "Shred paper with patient information"), List.of("Don't look up friends, family or yourself", "Don't post about patients anywhere"),
					List.<String[]>of(new String[] { "What is PHI?", "Any health information that can identify a patient" }, new String[] { "Who do you tell about a possible breach?", "The privacy officer, the same day" })),
			new Topic(p("vital|blood pressure|\\bbp\\b|pulse|room"), "Rooming a patient and taking vitals",
					List.of("Room patients within 5 minutes of check-in", "Measure blood pressure accurately", "Recognize vitals that must be reported right away"),
					List.of("Call the patient by first name and confirm date of birth", "Clean your hands and explain what you will do", "Seat the patient with back supported, feet flat and arm at heart level", "Use the right cuff size; wait 5 minutes of rest before measuring", "Record BP, pulse, temperature, SpO2, weight and height", "Review medications and allergies", "Report critical values to the provider right away"),
					List.of("Use the right cuff size", "Recheck abnormal readings"), List.of("Don't measure BP over clothing", "Don't round readings"),
					List.<String[]>of(new String[] { "How long should the patient rest before a BP reading?", "5 minutes" }, new String[] { "What do you do with a critical value?", "Tell the provider right away" })),
			new Topic(p("ekg|ecg|12.?lead"), "12-lead EKG procedure",
					List.of("Place all 10 electrodes correctly", "Reduce artifact", "Hand off the tracing for provider review"),
					List.of("Verify the order and the patient with two identifiers", "Explain the test; provide privacy and a gown", "Prep skin: clean, dry, clip hair if needed", "Place limb leads and V1-V6 on the correct landmarks", "Ask the patient to lie still and breathe normally", "Record the tracing and check it for artifact", "Give the tracing to the provider and document"),
					List.of("Check lead placement twice", "Keep the patient warm to reduce shivering artifact"), List.of("Don't place limb leads over bone", "Don't leave a tracing unreviewed"),
					List.<String[]>of(new String[] { "How many electrodes does a 12-lead use?", "10" }, new String[] { "What causes most artifact?", "Movement, shivering and poor skin prep" })),
			new Topic(p("hand|infection|ppe|hygiene|steril|clean"), "Infection control and hand hygiene",
					List.of("Perform hand hygiene at the right moments", "Choose the right PPE", "Clean rooms between patients"),
					List.of("Clean hands before and after every patient contact", "Use gloves for contact with blood or body fluids; remove them and clean hands", "Wipe exam surfaces and equipment with an approved disinfectant", "Respect the disinfectant's wet contact time", "Dispose of sharps and biohazard waste in the right containers"),
					List.of("Clean hands even when you wore gloves", "Keep supplies covered"), List.of("Don't recap needles", "Don't reuse single-use items"),
					List.<String[]>of(new String[] { "Do gloves replace hand hygiene?", "No" }, new String[] { "Where do used needles go?", "Straight into a sharps container" })),
			new Topic(p("medicat|injection|vaccin|immuniz|refill"), "Medication administration",
					List.of("Apply the rights of medication administration", "Document correctly", "Respond to a reaction"),
					List.of("Check the order: right patient, drug, dose, route and time", "Confirm allergies with the patient", "Check the expiration date and lot number", "Prepare and give the medication per policy and your license scope", "Observe the patient for the required time", "Document drug, dose, route, site, lot and time"),
					List.of("Ask when an order is unclear", "Watch for reactions"), List.of("Don't give a medication you did not prepare", "Don't work outside your scope"),
					List.<String[]>of(new String[] { "Name three rights of medication administration.", "Right patient, right drug, right dose (also route and time)" })),
			new Topic(p("angry|upset|difficult|de-?escalat|complain"), "Handling upset patients",
					List.of("Stay calm and listen", "Use de-escalation phrases", "Know when to get help"),
					List.of("Move the conversation to a private area if possible", "Listen without interrupting; acknowledge feelings", "Restate the concern and what you can do", "Offer options and a time frame", "Get a supervisor if the patient stays upset or anyone feels unsafe"),
					List.of("Use a calm, low voice", "Thank the patient for telling you"), List.of("Don't argue or take it personally", "Don't promise what you can't deliver"),
					List.<String[]>of(new String[] { "What is the first step with an upset patient?", "Listen and acknowledge their feelings" })),
			new Topic(p("emergenc|code|cpr|fire|evacu"), "Emergency response",
					List.of("Know your role in a medical emergency", "Know evacuation routes"),
					List.of("Call for help and announce the emergency per policy", "Bring the emergency cart and AED", "Start CPR if trained and the patient is unresponsive and not breathing normally", "Call 911 and meet EMS at the entrance", "For fire: rescue, alarm, contain, evacuate"),
					List.of("Know where the AED is", "Practice drills"), List.of("Don't use elevators during a fire", "Don't leave a patient alone"),
					List.<String[]>of(new String[] { "Where is the AED?", "At the location posted in your office" }))
	);

	private static final List<Resource> RESOURCES = List.of(
			new Resource(p("cancel|no.?show|late arriv|reschedul"), "Cancellation and no-show policy",
					"We save time for every patient. When a visit is missed, another patient waits longer for care.",
					List.of("Please call at least 24 hours ahead to cancel or reschedule", "If you arrive more than 15 minutes late, we may need to reschedule you", "A fee may apply after a second no-show", "We make exceptions for emergencies"),
					List.of("Call our office to cancel or reschedule"), List.<String[]>of(new String[] { "Is there a fee for a first missed visit?", "No. A fee may apply after a second no-show." })),
			new Resource(p("financ|copay|balance|payment|bill|self.?pay|deductible"), "Financial responsibility",
					"This explains what you pay and when.",
					List.of("Copays are due at check-in", "You are responsible for deductibles, coinsurance and services your plan does not cover", "We bill your insurance as a courtesy; please keep your insurance information up to date", "Payment plans are available; ask the front desk"),
					List.of("Call our billing office with questions about a bill"), List.<String[]>of(new String[] { "Do you offer payment plans?", "Yes. Ask the front desk or billing office." })),
			new Resource(p("excuse|work note|school note|return to work|jury"), "Excuse letter", "",
					List.of("[Patient name] was seen at our practice on [date]", "[He/She/They] may return to [work/school] on [date]", "Restrictions: [none / list]"),
					List.of("Questions: call our office"), List.of()),
			new Resource(p("refill|prescription|pharmacy"), "Prescription refill policy",
					"We want you to have your medicines without gaps.",
					List.of("Ask for refills at least 3 business days before you run out", "Ask your pharmacy to send us the request, or use the patient portal", "Some medicines need a visit or lab work before we can refill them", "We do not refill controlled medicines after hours"),
					List.of("Call us if you have side effects or questions about a medicine"), List.<String[]>of(new String[] { "How long do refills take?", "Up to 3 business days." })),
			new Resource(p("telehealth|video visit|virtual"), "Telehealth visits",
					"A telehealth visit lets you see your provider by video.",
					List.of("Use a phone, tablet or computer with a camera", "Find a quiet, private place with good internet", "Have your medicines and recent readings ready", "Your visit is private and secure"),
					List.of("Call us if you cannot connect 5 minutes after your visit time"), List.<String[]>of(new String[] { "Do I need to download an app?", "We will send a link before your visit." })),
			new Resource(p("intake|new patient|first visit|registration"), "Your first visit",
					"Welcome to our practice. Here is how to get ready for your first visit.",
					List.of("Arrive 15 minutes early", "Bring your photo ID and insurance card", "Bring a list of all your medicines, including vitamins", "Bring any recent test results or records"),
					List.of("Call us if you need to reschedule"), List.<String[]>of(new String[] { "How long is the first visit?", "Plan for about an hour." }))
	);

	public DraftResponse write(DraftRequest r) {
		return "TRAINING".equalsIgnoreCase(r.space()) ? training(r) : resource(r);
	}

	private DraftResponse training(DraftRequest r) {
		String prompt = r.prompt().trim();
		String type = TRAINING_TYPES.contains(r.type()) ? r.type() : "guide";
		String audience = blank(r.audience()) ? "All staff" : r.audience().trim();
		boolean shortLen = "short".equals(r.length());
		boolean longLen = "long".equals(r.length());
		Topic k = TOPICS.stream().filter(t -> t.re().matcher(prompt).find()).findFirst().orElse(null);
		String topic = k != null ? k.title() : capitalize(prompt.replaceAll("(?i)^(how to|create|write|make)\\s+", "").replaceAll("[.?!]+$", ""));
		List<String> steps = new ArrayList<>(k != null ? k.steps() : List.of("Prepare: review the policy and gather what you need", "Confirm the patient or task details before you start", "Do the task step by step, following practice policy", "Document what you did, when, and any issues", "Escalate anything unusual to your supervisor"));
		List<String> obj = new ArrayList<>(k != null ? k.objectives() : List.of("Explain why " + topic.toLowerCase(Locale.ROOT) + " matters for patients and the practice", "Complete the task correctly and safely", "Know when and how to escalate"));
		List<String[]> quiz = new ArrayList<>(k != null ? k.quiz() : List.<String[]>of(new String[] { "Why does this task matter?", "Patient safety, a good patient experience and compliance" }, new String[] { "Who do you escalate to?", "Your supervisor or the provider on duty" }));
		List<String> dos = k != null ? k.dos() : List.of("Follow the written policy every time", "Ask when unsure");
		List<String> donts = k != null ? k.donts() : List.of("Don't skip documentation", "Don't work outside your role");
		if (shortLen) {
			steps = steps.subList(0, Math.min(4, steps.size()));
			obj = obj.subList(0, Math.min(2, obj.size()));
			quiz = quiz.subList(0, Math.min(2, quiz.size()));
		}
		String scope = scopeFor(audience);
		String suffix = switch (type) {
			case "sop" -> " procedure";
			case "check" -> " checklist";
			case "quiz" -> " quiz";
			case "script" -> " script";
			case "comp" -> " competency sign-off";
			default -> "";
		};
		String title = topic + " - " + audience + suffix;
		List<String> L = new ArrayList<>();
		L.add("For: " + audience + "   |   Type: " + typeName(type) + "   |   Version 1.0   |   " + today());
		L.add("Draft - review and approve before use. Clinical content must be approved by the medical director or nurse manager.");
		switch (type) {
			case "guide", "sop" -> {
				h(L, "Purpose");
				L.add("This " + ("sop".equals(type) ? "procedure" : "training") + " teaches " + audience.toLowerCase(Locale.ROOT) + " how to handle " + topic.toLowerCase(Locale.ROOT) + " consistently and safely.");
				if ("sop".equals(type)) {
					h(L, "Scope");
					L.add("Applies to all " + audience.toLowerCase(Locale.ROOT) + " at every location.");
					h(L, "Responsibilities");
					L.add("- " + audience + ": follow this procedure and document each step.");
					L.add("- Supervisor: train, observe and sign off competency.");
				} else {
					h(L, "Learning objectives");
					obj.forEach(x -> L.add("- " + x));
				}
				h(L, "sop".equals(type) ? "Procedure" : "Key steps");
				for (int i = 0; i < steps.size(); i++) {
					L.add((i + 1) + ". " + steps.get(i));
				}
				h(L, "Do");
				dos.forEach(x -> L.add("- " + x));
				h(L, "Don't");
				donts.forEach(x -> L.add("- " + x));
				h(L, "Scope reminder");
				L.add(scope);
				if (longLen) {
					h(L, "Common mistakes");
					L.add("- Skipping verification steps when the clinic is busy");
					L.add("- Documenting at the end of the day instead of in real time");
					L.add("- Not escalating early");
				}
				if (Boolean.TRUE.equals(r.quiz())) {
					h(L, "Check your understanding");
					for (int i = 0; i < quiz.size(); i++) {
						L.add((i + 1) + ". " + quiz.get(i)[0]);
					}
					h(L, "Answer key");
					for (int i = 0; i < quiz.size(); i++) {
						L.add((i + 1) + ". " + quiz.get(i)[1]);
					}
				}
				if ("sop".equals(type)) {
					h(L, "Review");
					L.add("Reviewed yearly or when regulations change. Owner: practice manager.");
				}
			}
			case "check" -> {
				h(L, "Checklist");
				steps.forEach(x -> L.add("[ ] " + x));
				h(L, "Before you finish");
				L.add("[ ] Documentation complete");
				L.add("[ ] Issues escalated");
				h(L, "Scope reminder");
				L.add(scope);
			}
			case "quiz" -> {
				h(L, "Quiz");
				List<String[]> all = new ArrayList<>(quiz);
				all.add(new String[] { "True or false: you can skip a step when the clinic is busy.", "False" });
				for (int i = 0; i < all.size(); i++) {
					L.add((i + 1) + ". " + all.get(i)[0]);
					L.add("   Answer: ____________________");
				}
				h(L, "Answer key");
				for (int i = 0; i < all.size(); i++) {
					L.add((i + 1) + ". " + all.get(i)[1]);
				}
			}
			case "script" -> {
				h(L, "Opening");
				L.add("\"Hello, this is [your name] with [practice name]. Am I speaking with [patient name]? To protect your privacy, can you confirm your date of birth?\"");
				h(L, "Main points");
				for (int i = 0; i < Math.min(5, steps.size()); i++) {
					L.add((i + 1) + ". " + steps.get(i));
				}
				h(L, "If the patient has a clinical question");
				L.add("\"That's a great question for our nurse. Let me connect you, or I can have them call you back today.\"");
				h(L, "Closing");
				L.add("\"Is there anything else I can help you with today? Thank you for calling.\"");
			}
			default -> {
				h(L, "Skills to demonstrate");
				for (int i = 0; i < steps.size(); i++) {
					L.add((i + 1) + ". " + steps.get(i) + "    Met [ ]  Not yet [ ]");
				}
				h(L, "Sign-off");
				L.add("Employee: ______________________   Date: __________");
				L.add("Observed by: ___________________   Date: __________");
				L.add("Next review due: __________");
			}
		}
		if (Boolean.TRUE.equals(r.signature()) && !"comp".equals(type)) {
			h(L, "Acknowledgment");
			L.add("I have read and understand this training.");
			L.add("Name: ______________________   Signature: ______________________   Date: __________");
		}
		return new DraftResponse(title, String.join("\n", L), k != null);
	}

	private DraftResponse resource(DraftRequest r) {
		String prompt = r.prompt().trim();
		String type = RESOURCE_TYPES.contains(r.type()) ? r.type() : "handout";
		boolean shortLen = "short".equals(r.length());
		Resource k = RESOURCES.stream().filter(t -> t.re().matcher(prompt).find()).findFirst().orElse(null);
		String topic = k != null ? k.title() : capitalize(prompt.replaceAll("(?i)^(please\\s+)?(how to|create|write|make|draft)\\s+(a|an|the)?\\s*", "").replaceAll("[.?!]+$", ""));
		List<String> pts = new ArrayList<>(k != null ? k.points() : List.of("Explain what " + topic.toLowerCase(Locale.ROOT) + " means for patients", "List what the patient needs to do, step by step", "Say who to contact with questions"));
		List<String> call = k != null ? k.call() : List.of("Call our office with questions");
		List<String[]> faq = new ArrayList<>(k != null ? k.faq() : List.<String[]>of(new String[] { "Who do I call with questions?", "Call our office during business hours." }));
		if (shortLen) {
			pts = pts.subList(0, Math.min(3, pts.size()));
			faq = faq.subList(0, Math.min(1, faq.size()));
		}
		String lower = topic.toLowerCase(Locale.ROOT);
		String suffix = switch (type) {
			case "policy" -> lower.contains("policy") ? "" : " policy";
			case "sop" -> " procedure";
			case "form" -> " form";
			case "letter" -> lower.contains("letter") ? "" : " letter";
			case "faq" -> " FAQ";
			case "check" -> " checklist";
			default -> "";
		};
		String title = topic + suffix;
		List<String> L = new ArrayList<>();
		L.add("Type: " + typeName(type) + "   |   Version 1.0   |   " + today());
		L.add("Draft - review and approve before use.");
		switch (type) {
			case "handout" -> {
				if (k != null && !k.intro().isBlank()) {
					h(L, "What this is");
					L.add(k.intro());
				}
				h(L, "What you need to know");
				pts.forEach(x -> L.add("- " + x));
				if (!faq.isEmpty() && !shortLen) {
					h(L, "Common questions");
					faq.forEach(q -> {
						L.add(q[0]);
						L.add("   " + q[1]);
					});
				}
				h(L, "When to call us");
				call.forEach(x -> L.add("- " + x));
			}
			case "policy" -> {
				h(L, "Purpose");
				L.add(k != null && !k.intro().isBlank() ? k.intro() : "This policy explains how the practice handles " + lower + ".");
				h(L, "Policy");
				pts.forEach(x -> L.add("- " + x));
				h(L, "Procedure");
				L.add("1. Front desk explains this policy at check-in and in reminder messages");
				L.add("2. Staff document exceptions in the patient record");
				L.add("3. The practice manager reviews this policy every year");
			}
			case "sop" -> {
				h(L, "Purpose");
				L.add("How staff handle " + lower + ".");
				h(L, "Procedure");
				for (int i = 0; i < pts.size(); i++) {
					L.add((i + 1) + ". " + pts.get(i));
				}
				h(L, "Documentation");
				L.add("Record actions in the patient record.");
			}
			case "form" -> {
				h(L, "Patient information");
				L.add("Name: ______________________________   Date of birth: ____________");
				L.add("Phone: ______________________   Email: ______________________");
				h(L, topic);
				pts.forEach(x -> L.add("[ ] " + x));
				h(L, "Signature");
				L.add("I have read and understand this form.");
				L.add("Signature: ______________________________   Date: ____________");
			}
			case "letter" -> {
				L.add("");
				L.add("[Date]");
				L.add("");
				L.add("To whom it may concern,");
				L.add("");
				pts.forEach(x -> L.add(x + "."));
				L.add("");
				L.add("Please contact our office with any questions.");
				L.add("");
				L.add("Sincerely,");
				L.add("[Provider name, credentials]");
			}
			case "faq" -> {
				h(L, "Frequently asked questions");
				List<String[]> all = new ArrayList<>(faq);
				pts.stream().limit(3).forEach(x -> all.add(new String[] { "What should I know about this?", x + "." }));
				for (int i = 0; i < all.size(); i++) {
					L.add((i + 1) + ". " + all.get(i)[0]);
					L.add("   " + all.get(i)[1]);
				}
				h(L, "Still have questions?");
				call.forEach(x -> L.add("- " + x));
			}
			default -> {
				h(L, "Checklist");
				pts.forEach(x -> L.add("[ ] " + x));
			}
		}
		return new DraftResponse(title, String.join("\n", L), k != null);
	}

	private static String scopeFor(String audience) {
		String a = audience.toLowerCase(Locale.ROOT);
		if (a.matches(".*(front|desk|schedul|patient access|call center).*")) {
			return "Front desk staff do not give medical advice. Route clinical questions to the nurse and advise 911 for emergencies.";
		}
		if (a.matches(".*(medical assist|\\bma\\b|cma|rma).*")) {
			return "Medical assistants work under provider supervision and do not assess, triage or interpret results. Escalate to the nurse or provider.";
		}
		if (a.matches(".*(lvn|lpn|vocational|practical).*")) {
			return "LVNs and LPNs work within their scope under RN or provider supervision.";
		}
		return "Follow practice policy and work within your role.";
	}

	private static String typeName(String type) {
		return switch (type) {
			case "guide" -> "Training guide";
			case "sop" -> "Step-by-step procedure (SOP)";
			case "check" -> "Checklist";
			case "quiz" -> "Quiz with answer key";
			case "script" -> "Phone or patient script";
			case "comp" -> "Competency sign-off";
			case "handout" -> "Patient handout";
			case "policy" -> "Policy";
			case "form" -> "Form template";
			case "letter" -> "Letter template";
			case "faq" -> "FAQ";
			default -> type;
		};
	}

	private static void h(List<String> lines, String heading) {
		lines.add("");
		lines.add("# " + heading);
	}

	private static String today() {
		return LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	private static String capitalize(String s) {
		String t = s == null ? "" : s.trim();
		return t.isEmpty() ? "Untitled" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
	}
}
