package com.api.template;
import com.utilities.LogUtil;

// Rest controller import
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;

import com.enums.ResponseCode;
import com.fasterxml.jackson.annotation.JsonView;
import tools.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.pojo.template.Pojo;
import com.service.template.SampleService;
import com.utilities.RequestLoggingUtil;
import com.utilities.Tool;
import com.validation.Audit;
import com.validation.RateLimit;

import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.slf4j.Slf4j;

@Slf4j // Only use .info/error for technical/system logs
@RestController
public class RestSpringController {

	private static final Faker faker = new Faker();

	private final ObjectMapper objectMapper;

	private final Tool tool;

	private final SampleService sampleService;

	public RestSpringController(ObjectMapper objectMapper, Tool tool, SampleService sampleService) {
		this.objectMapper = objectMapper;
		this.tool = tool;
		this.sampleService = sampleService;
	}

	@Audit("POST-TEMPLATE")
	@RateLimit(headerName = "", pathVariable = "", requestBodyField = "")
	@PostMapping(value = "v1/template/post", consumes = { MediaType.APPLICATION_JSON }, produces = {
			MediaType.APPLICATION_JSON })
	@JsonView({ Pojo.Post.class }) // Which getter parameter should return within json
	@Transactional
	// @Permission(resource = "USERS", permissions = {PermissionType.READ})
	// @PreAuthorize("hasRole('ADMIN')")
	// @Validated - Triggers validation on parameter where annotation validation
	// apply with groups = {}.
	public ResponseEntity<com.pojo.ApiResponse> postTemplate(HttpServletRequest request,
			@RequestBody @Validated({ Pojo.Post.class }) Pojo pojo) throws Throwable {
		log.info("-Post template start-");
		try {
			RequestLoggingUtil.logRequestDetails(request, log);
			log.info("Request: " + objectMapper.writeValueAsString(pojo));

			return ResponseEntity.status(HttpStatus.OK).body(com.pojo.ApiResponse
					.builder()
					.resp_code(ResponseCode.SUCCESS.getResponse_code())
					.resp_msg(ResponseCode.SUCCESS.getResponse_desc())
					.datetime(tool.getTodayDateTimeInString())
					.pojo(Pojo.builder()
							.id(faker.number().randomDigit())
							.name(faker.name().fullName())
							.ic(sampleService.generateRandomIc())
							.dateOfBirth(faker.date().birthday().toString())
							.password(sampleService.generatePassword(5))
							.account_balance(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 999)))
							.build())
					.build());
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		} finally {
			log.info("-Post template end-");
		}
	}

	@Audit("GET-TEMPLATE")
	@RateLimit(headerName = "", pathVariable = "", requestBodyField = "")
	@GetMapping(value = "v1/template/get/{ic}", produces = { MediaType.APPLICATION_JSON })
	@JsonView({ Pojo.Get.class }) // Which getter parameter should return within json
	@Transactional
	// @Permission(resource = "USERS", permissions = {PermissionType.READ})
	// @PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<com.pojo.ApiResponse> getTemplate(HttpServletRequest request, @PathVariable @NotBlank String ic)
			throws Throwable {
		log.info("-Get template start-");
		try {
			RequestLoggingUtil.logRequestDetails(request, log);

			return ResponseEntity.status(HttpStatus.FOUND).body(com.pojo.ApiResponse
					.builder()
					.resp_code(ResponseCode.SUCCESS.getResponse_code())
					.resp_msg(ResponseCode.SUCCESS.getResponse_desc())
					.datetime(tool.getTodayDateTimeInString())
					.pojo(Pojo.builder()
							.id(faker.number().randomDigit())
							.name(faker.name().fullName())
							.ic(sampleService.generateRandomIc())
							.dateOfBirth(faker.date().birthday().toString())
							.password(sampleService.generatePassword(5))
							.account_balance(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 999)))
							.build())
					.build());
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		} finally {
			log.info("-Get template end-");
		}
	}

	@Audit("GET-ASYNC-TEMPLATE")
	@RateLimit(headerName = "", pathVariable = "", requestBodyField = "")
	@TimeLimiter(name = "getAsyncTemplate") // To control timeout of endpoint
	@GetMapping(value = "v1/template/get-async/{sleepMs}", produces = { MediaType.APPLICATION_JSON })
	@JsonView({ Pojo.Get.class }) // Which getter parameter should return within json
	@Transactional
	// @Permission(resource = "USERS", permissions = {PermissionType.READ})
	// @PreAuthorize("hasRole('ADMIN')")
	public CompletableFuture<ResponseEntity<com.pojo.ApiResponse>> getAsyncTemplate(HttpServletRequest request,
			@PathVariable long sleepMs) throws Throwable {
		log.info("-Get async template start-");
		try {
			RequestLoggingUtil.logRequestDetails(request, log);
			Thread.sleep(sleepMs);
			return CompletableFuture.supplyAsync(() -> {
				try {
					return ResponseEntity.status(HttpStatus.FOUND).body(com.pojo.ApiResponse
							.builder()
							.resp_code(ResponseCode.SUCCESS.getResponse_code())
							.resp_msg(ResponseCode.SUCCESS.getResponse_desc())
							.datetime(tool.getTodayDateTimeInString())
							.pojo(Pojo.builder()
									.id(faker.number().randomDigit())
									.name(faker.name().fullName())
									.ic(sampleService.generateRandomIc())
									.dateOfBirth(faker.date().birthday().toString())
									.password(sampleService.generatePassword(5))
									.account_balance(BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(0, 999)))
									.build())
							.build());
				} catch (Throwable e) {
					throw new CompletionException(e);
				}
			});
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		} finally {
			log.info("-Get async template end-");
		}
	}

	@Audit("PUT-TEMPLATE")
	@RateLimit(headerName = "", pathVariable = "", requestBodyField = "")
	@PutMapping(value = "v1/template/put/{id}/{ic}", consumes = { MediaType.APPLICATION_JSON }, produces = {
			MediaType.APPLICATION_JSON })
	@JsonView({ Pojo.Put.class }) // Which getter parameter should return within json
	@Transactional
	// @Permission(resource = "USERS", permissions = {PermissionType.READ})
	// @PreAuthorize("hasRole('ADMIN')")
	// @Validated - Triggers validation on parameter where annotation validation
	// apply with groups = {}.
	public ResponseEntity<com.pojo.ApiResponse> putTemplate(HttpServletRequest request, @PathVariable @NotBlank int id,
			@PathVariable @NotBlank String ic, @RequestBody @Validated({ Pojo.Put.class }) Pojo pojo) throws Throwable {
		log.info("-Put template start-");
		try {
			RequestLoggingUtil.logRequestDetails(request, log);
			log.info("Request: " + objectMapper.writeValueAsString(pojo));

			return ResponseEntity.status(HttpStatus.OK).body(sampleService.putTemplate(log, id, ic, pojo));
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		} finally {
			log.info("-Put template end-");
		}
	}

	@Audit("DELETE-TEMPLATE")
	@RateLimit(headerName = "", pathVariable = "", requestBodyField = "")
	@DeleteMapping(value = "v1/template/delete", produces = { MediaType.APPLICATION_JSON })
	@JsonView({ Pojo.Delete.class }) // Which getter parameter should return within json
	@Transactional
	// @Permission(resource = "USERS", permissions = {PermissionType.READ})
	// @PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<com.pojo.ApiResponse> deleteTemplate(HttpServletRequest request, @RequestParam int ic)
			throws Throwable {
		log.info("-Delete template start-");
		try {
			RequestLoggingUtil.logRequestDetails(request, log);

			return ResponseEntity.status(HttpStatus.RESET_CONTENT).body(com.pojo.ApiResponse
					.builder()
					.resp_code(ResponseCode.SUCCESS.getResponse_code())
					.resp_msg(ResponseCode.SUCCESS.getResponse_desc())
					.datetime(tool.getTodayDateTimeInString())
					.build());
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		} finally {
			log.info("-Delete template end-");
		}
	}
}
