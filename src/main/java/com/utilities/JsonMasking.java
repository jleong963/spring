package com.utilities;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Utility component for masking sensitive fields in JSON data.
 * This class provides functionality to mask specified fields in JSON structures
 * while preserving the original JSON structure and handling nested objects and
 * arrays.
 * Thread-safe implementation using ConcurrentHashMap for field storage.
 *
 * @author jleong94
 * @since 2025-11-06
 */
@Component
public class JsonMasking {

	/** ObjectMapper instance for JSON processing */
	private final ObjectMapper objectMapper;

	/** Thread-safe set of field names to be masked */
	private Set<String> fields2Mask = ConcurrentHashMap.newKeySet();

	/**
	 * Field names (lower-case) treated as card numbers. These are partially masked
	 * - keeping the first 8 and last 4 digits visible - rather than fully masked.
	 */
	private static final Set<String> CARD_FIELDS = Set.of("card_no", "cardno", "pan");

	/** Number of leading characters kept visible for a card number. */
	private static final int CARD_PREFIX_VISIBLE = 8;

	/** Number of trailing characters kept visible for a card number. */
	private static final int CARD_SUFFIX_VISIBLE = 4;

	/**
	 * Constructor injecting the shared Jackson ObjectMapper.
	 * 
	 * @param objectMapper Jackson ObjectMapper instance
	 */
	public JsonMasking(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
		// Seed a sensible default set of sensitive field names (stored lower-case).
		// Consumers can extend this set at runtime via addFields2Mask(...).
		this.fields2Mask.addAll(Set.of(
				"password", "pin", "otp", "cvv", "cvc", "card_no", "cardno", "pan",
				"secret", "token", "access-token", "refresh-token", "api-key"));
	}

	/**
	 * Adds multiple field names to the set of fields to be masked.
	 * 
	 * @param fields2Mask Set of field names to be added to masking set
	 */
	public void addFields2Mask(Set<String> fields2Mask) {
		this.fields2Mask.addAll(fields2Mask);
	}

	/**
	 * Masks sensitive fields in the provided JSON string.
	 * Includes detailed error logging with stack trace information.
	 *
	 * @param log        SLF4J Logger instance for error logging
	 * @param jsonString Input JSON string to be masked
	 * @return Masked JSON string
	 * @throws Throwable if any error occurs during JSON processing
	 */
	public String maskJson(Logger log, String jsonString) throws Throwable {
		try {
			JsonNode rootNode = objectMapper.readTree(jsonString);
			JsonNode maskedNode = maskNode(rootNode);
			return objectMapper.writeValueAsString(maskedNode);
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}

	/**
	 * Recursively masks a JsonNode based on its type (object or array).
	 * 
	 * @param node JsonNode to be masked
	 * @return Masked JsonNode
	 */
	private JsonNode maskNode(JsonNode node) {
		if (node.isObject()) {
			return maskObjectNode((ObjectNode) node);
		} else if (node.isArray()) {
			return maskArrayNode((ArrayNode) node);
		}
		return node;
	}

	/**
	 * Masks sensitive fields within an ObjectNode.
	 * Handles nested objects and arrays recursively while masking designated
	 * fields.
	 * 
	 * @param objectNode ObjectNode to be processed
	 * @return Masked ObjectNode
	 */
	private ObjectNode maskObjectNode(ObjectNode objectNode) {
		ObjectNode maskedObject = objectMapper.createObjectNode();

		for (Map.Entry<String, JsonNode> property : objectNode.properties()) {
			String fieldName = property.getKey();
			JsonNode fieldValue = property.getValue();

			if (shouldMaskField(fieldName)) {
				// Use the source fieldValue (maskedObject does not yet contain this key) and
				// guard against null/missing nodes to avoid a NullPointerException.
				String raw = (fieldValue == null || fieldValue.isNull()) ? "" : fieldValue.asString("");
				// Card numbers keep the first 8 and last 4 digits visible; everything else is
				// fully masked with asterisks of the same length.
				maskedObject.put(fieldName, isCardField(fieldName) ? maskCardNumber(raw) : "*".repeat(raw.length()));
			} else if (fieldValue.isObject()) {
				maskedObject.set(fieldName, maskObjectNode((ObjectNode) fieldValue));
			} else if (fieldValue.isArray()) {
				maskedObject.set(fieldName, maskArrayNode((ArrayNode) fieldValue));
			} else {
				maskedObject.set(fieldName, fieldValue);
			}
		}

		return maskedObject;
	}

	/**
	 * Masks elements within an ArrayNode.
	 * Recursively processes array elements that are objects or nested arrays.
	 * 
	 * @param arrayNode ArrayNode to be processed
	 * @return Masked ArrayNode
	 */
	private ArrayNode maskArrayNode(ArrayNode arrayNode) {
		ArrayNode maskedArray = objectMapper.createArrayNode();

		for (JsonNode element : arrayNode) {
			if (element.isObject()) {
				maskedArray.add(maskObjectNode((ObjectNode) element));
			} else if (element.isArray()) {
				maskedArray.add(maskArrayNode((ArrayNode) element));
			} else {
				maskedArray.add(element);
			}
		}

		return maskedArray;
	}

	/**
	 * Determines if a field should be masked based on its name.
	 * Field names are compared case-insensitively.
	 * 
	 * @param fieldName Name of the field to check
	 * @return true if the field should be masked, false otherwise
	 */
	private boolean shouldMaskField(String fieldName) {
		return this.fields2Mask.contains(fieldName.toLowerCase());
	}

	/**
	 * Determines if a field holds a card number (compared case-insensitively).
	 *
	 * @param fieldName Name of the field to check
	 * @return true if the field is a card-number field
	 */
	private boolean isCardField(String fieldName) {
		return CARD_FIELDS.contains(fieldName.toLowerCase());
	}

	/**
	 * Partially masks a card number, keeping the first {@value #CARD_PREFIX_VISIBLE}
	 * and last {@value #CARD_SUFFIX_VISIBLE} characters visible and replacing every
	 * character in between with an asterisk (e.g. "4111111111111234" becomes
	 * "41111111****1234"). Values too short to reveal both ends without overlap are
	 * fully masked instead, so a short value is never partially exposed.
	 *
	 * @param value the raw card number
	 * @return the partially masked card number
	 */
	private String maskCardNumber(String value) {
		if (value == null) {
			return "";
		}
		int length = value.length();
		if (length <= CARD_PREFIX_VISIBLE + CARD_SUFFIX_VISIBLE) {
			return "*".repeat(length);
		}
		return value.substring(0, CARD_PREFIX_VISIBLE)
				+ "*".repeat(length - CARD_PREFIX_VISIBLE - CARD_SUFFIX_VISIBLE)
				+ value.substring(length - CARD_SUFFIX_VISIBLE);
	}
}