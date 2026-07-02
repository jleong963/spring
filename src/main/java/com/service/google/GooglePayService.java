package com.service.google;
import com.utilities.LogUtil;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.configuration.GooglePayConfig;
import tools.jackson.databind.ObjectMapper;
import com.google.crypto.tink.apps.paymentmethodtoken.GooglePaymentsPublicKeysManager;
import com.google.crypto.tink.apps.paymentmethodtoken.PaymentMethodTokenRecipient;
import com.google.crypto.tink.apps.paymentmethodtoken.PaymentMethodTokenRecipient.Builder;
import com.modal.Email;
import com.pojo.Property;
import com.service.EmailService;
import com.utilities.Tool;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class GooglePayService {

	private final ObjectMapper objectMapper;

	private final Tool tool;

	private final Property property;

	private final EmailService emailService;

	private final MeterRegistry meterRegistry;

	private final Counter recoverCounter;

	private final RestTemplate restTemplate = new RestTemplate();

	private final GooglePayConfig googlePayConfig;

	public GooglePayService(ObjectMapper objectMapper, Tool tool, Property property, EmailService emailService,
			MeterRegistry meterRegistry, GooglePayConfig googlePayConfig) {
		this.objectMapper = objectMapper;
		this.tool = tool;
		this.property = property;
		this.emailService = emailService;
		this.meterRegistry = meterRegistry;
		this.recoverCounter = Counter.builder("firebase_service_failures_total")
				.description("Number of failed retries hitting @Recover")
				.register(this.meterRegistry);
		this.googlePayConfig = googlePayConfig;
	}

	@Retry(name = "decryptGooglePayToken")
	@CircuitBreaker(name = "decryptGooglePayToken", fallbackMethod = "fallbackDecryptGooglePayToken")
	public com.pojo.google.GooglePay decryptGooglePayToken(Logger log, com.pojo.google.GooglePay googlePay)
			throws Throwable {
		try {
			List<Path> paths = tool.loadFileListFromClasspath(log, googlePayConfig.getKey().getPath());
			Builder paymentMethodTokenRecipient = new PaymentMethodTokenRecipient.Builder()
					.fetchSenderVerifyingKeysWith(
							property.getSpring_profiles_active().equals("prod") ? GooglePaymentsPublicKeysManager.INSTANCE_PRODUCTION
									: GooglePaymentsPublicKeysManager.INSTANCE_TEST);
			if (!property.getSpring_profiles_active().equals("prod")) {
				paymentMethodTokenRecipient.recipientId("merchant:12345678901234567890");
			}
			// This guide applies only to protocolVersion = ECv2
			paymentMethodTokenRecipient.protocolVersion("ECv2");
			for (Path path : paths) {
				if (path.getFileName().endsWith("pk8")) {
					// Multiple private keys can be added to support graceful
					// key rotations.
					paymentMethodTokenRecipient
							.addRecipientPrivateKey(tool.readFileWithBufferedReader(log, path.toAbsolutePath().toString()));
				}
			}
			String decryptedMsg = paymentMethodTokenRecipient.build()
					.unseal(googlePay.getEncryptedMsg());
			googlePay = objectMapper.readerForUpdating(googlePay).readValue(decryptedMsg);
			return googlePay;
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}

	// Exception param must put as last param in fallback method
	public void fallbackDecryptGooglePayToken(Logger log, com.pojo.google.GooglePay googlePay, Throwable throwable) {
		log.info("Recover on decrypt google pay token start.");
		try {
			String error_detail = "";
			LogUtil.logError(log, throwable);
			// Increment Prometheus counter
			recoverCounter.increment();

			// Persist failure details

			// Trigger alerts (Ops team, monitoring system)
			if (property.getAlert_slack_webhook_url() != null && !property.getAlert_slack_webhook_url().isBlank()) {
				restTemplate.postForEntity(property.getAlert_slack_webhook_url(),
						java.util.Collections.singletonMap("text", error_detail), String.class);
			}
			if ((property.getAlert_support_email_to() != null && !property.getAlert_support_email_to().isBlank()) ||
					(property.getAlert_support_email_cc() != null && !property.getAlert_support_email_cc().isBlank()) ||
					(property.getAlert_support_email_bcc() != null && !property.getAlert_support_email_bcc().isBlank())) {
				String exceptionNotificationEmailTemplate = String.format(
						emailService.loadHtmlTemplate(log, "google_pay_decryption_exception.html"),
						property.getSpring_application_name(), property.getSpring_application_name(), error_detail,
						property.getSpring_application_name());
				Email email = Email.builder()
						.sender(property.getSpring_mail_sender())
						.replyTo(property.getAlert_support_email_replyTo())
						.receiver(property.getAlert_support_email_to())
						.cc(property.getAlert_support_email_cc())
						.bcc(property.getAlert_support_email_bcc())
						.subject("System Exception Error!!!")
						.body(exceptionNotificationEmailTemplate)
						.build();
				emailService.sendEmail(log, email);
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		} finally {
			log.info("Recover on decrypt google pay token end.");
		}
	}
}
