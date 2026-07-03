package com.utilities;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Base64;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import lombok.Cleanup;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import com.service.RsaKeyCacheService;

@Component
public class Tool {

	private final RsaKeyCacheService rsaKeyCacheService;

	// @Lazy injects a deferred-resolution proxy so this constructor can complete
	// before RsaKeyCacheService (which itself depends on Tool) is fully built,
	// breaking that circular dependency. Nullable since no proxy can stand in
	// for a bean that isn't registered at all.
	public Tool(@Lazy @Nullable RsaKeyCacheService rsaKeyCacheService) {
		this.rsaKeyCacheService = rsaKeyCacheService;
	}

	public List<String> downloadFileFromSftp(Logger log, String host, String username, String password,
			String remote_path, String local_path, String knownHostsFilePath, String fingerprint) throws Throwable {
		List<String> downloadedFiles = new ArrayList<>();
		try {
			long todayEpoch = LocalDate.now()
					.atStartOfDay(ZoneId.systemDefault())
					.toEpochSecond();

			@Cleanup
			SSHClient sshClient = new SSHClient();// @Cleanup - automatically clean up resources when a method exits
			if (knownHostsFilePath != null && !knownHostsFilePath.isBlank()) {
				File knownHostsFile = new File(knownHostsFilePath);
				if (knownHostsFile.isAbsolute() && knownHostsFile.exists() && knownHostsFile.isFile()) {
					sshClient.loadKnownHosts(knownHostsFile);
					log.info("Using known hosts file as verifier.");
				}
			} else if (fingerprint != null && !fingerprint.isBlank()) {
				sshClient.addHostKeyVerifier(new HostKeyVerifier() {
					@Override
					public boolean verify(String hostname, int port, PublicKey key) {
						String actual = SecurityUtils.getFingerprint(key); // e.g. "aa:bb:cc:...".
						// Normalize both sides and compare
						String actualNorm = normalizeHexFingerprint(actual);
						String expectedNorm = normalizeHexFingerprint(fingerprint);
						if (actualNorm.isBlank() || expectedNorm.isBlank()) {
							return false;
						}
						// Use constant-time comparison
						return java.security.MessageDigest.isEqual(
								actualNorm.getBytes(java.nio.charset.StandardCharsets.UTF_8),
								expectedNorm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
					}

					@Override
					public List<String> findExistingAlgorithms(String hostname, int port) {
						return Collections.emptyList();
					}
				});
			} else {
				log.info("Not using any verifier.");
			}
			String[] host_split = host.split(":");
			if (host_split.length > 1) {
				sshClient.connect(host_split[0], Integer.parseInt(host_split[1]));
			} else {
				sshClient.connect(host);
			}
			sshClient.authPassword(username, password);
			@Cleanup
			SFTPClient sftpClient = sshClient.newSFTPClient();
			remote_path = Paths.get(remote_path).normalize().toString().replace("\\", "/");
			local_path = Paths.get(local_path).normalize().toString().replace("\\", "/");
			List<RemoteResourceInfo> remote_files = sftpClient.ls(remote_path);
			for (RemoteResourceInfo remote_resource : remote_files) {
				if (!remote_resource.isDirectory()) {
					String remoteFilePath = remote_path.concat("/").concat(remote_resource.getName());
					String safeFileName = Paths.get(remote_resource.getName()).getFileName().toString();
					File localFile = new File(local_path, safeFileName);
					if (remote_resource.getAttributes().getMtime() >= todayEpoch) {
						log.info("Downloading file: {} (size={} bytes)", remoteFilePath, remote_resource.getAttributes().getSize());
						sftpClient.get(remoteFilePath, localFile.getAbsolutePath());
						downloadedFiles.add(localFile.getAbsolutePath());
					} else {
						log.debug("Skipped old file: {}", remoteFilePath);
					}
				}
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		}
		return downloadedFiles;
	}

	/**
	 * Normalize colon-separated hex fingerprint to lower-case, no spaces (e.g.
	 * "aa:bb" -> "aa:bb")
	 */
	private static String normalizeHexFingerprint(String f) {
		if (f == null || f.isBlank())
			return "";
		// Remove non-hex characters and lowercase
		return f.replaceAll("[^0-9a-fA-F]", "").toLowerCase();
	}

	public String getTodayDateTimeInString() {
		String result = "";
		DateTimeFormatter formatter = new DateTimeFormatterBuilder()
				.appendPattern("yyyy-MM-dd") // Date part
				.optionalStart()
				.appendLiteral('T') // Try parsing with 'T'
				.optionalEnd()
				.optionalStart()
				.appendLiteral(' ') // Try parsing with space
				.optionalEnd()
				.optionalStart()
				.appendPattern("HH:mm:ss") // Time part
				.optionalEnd()
				.optionalStart()
				.appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true) // Handle 1-3 digit milliseconds
				.optionalEnd()
				.toFormatter();
		LocalDateTime now = LocalDateTime.now();
		result = formatter.format(now);
		result = result.contains("T") ? result.replace("T", "") : result;
		return result;
	}

	public List<String> saveUploadFileToPath(Logger log, List<MultipartFile> multipartFiles, String upload_path)
			throws Throwable {
		List<String> savedFiles = new ArrayList<>();
		try {
			if (multipartFiles != null && !multipartFiles.isEmpty() && upload_path != null && !upload_path.isBlank()) {
				Path uploadDir = Paths.get(upload_path);
				Files.createDirectories(uploadDir);
				for (MultipartFile multipartFile : multipartFiles) {
					if (multipartFile != null && !multipartFile.isEmpty()) {
						String originalFilename = Path.of(multipartFile.getOriginalFilename()).getFileName().toString(); // sanitize
																																																							// path
						Path destinationFile = uploadDir.resolve(originalFilename).normalize().toAbsolutePath();
						// Prevent path traversal attack
						if (destinationFile.getParent().equals(uploadDir.toAbsolutePath())) {
							Files.copy(multipartFile.getInputStream(), destinationFile, StandardCopyOption.REPLACE_EXISTING);
							savedFiles.add(destinationFile.toString());
						} else {
							log.info("Cannot store file outside, {} the target directory, {}.", destinationFile.getParent(),
									uploadDir.toAbsolutePath());
						}
					}
				}
			} else {
				log.info("No files to upload or upload path is invalid.");
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		}
		return savedFiles;
	}

	/**
	 * @param log       Logger instance used for error reporting
	 * @param classpath String representing the class path to scan
	 * 
	 * @return List<Path> containing paths to all regular files in the directory.
	 *         Returns an empty list if the resource size is 0.
	 * 
	 * @throws Throwable if any error occurs during file system operations. The
	 *                   error
	 *                   is logged with detailed stack trace information (including
	 *                   class name,
	 *                   line number, and error message) before being re-thrown.
	 */
	public List<Path> loadFileListFromClasspath(Logger log, String classpath) throws Throwable {
		try {
			ClassLoader classLoader = getClass().getClassLoader();
			URL resourceUrl = classLoader.getResource(classpath);

			if (resourceUrl == null) {
				log.error("Resource path not found: {}", classpath);
				return Collections.emptyList();
			}

			Path directoryPath = Paths.get(resourceUrl.toURI());

			return Files.walk(directoryPath, 1)
					.filter(Files::isRegularFile)
					.collect(Collectors.toList());
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}

	/**
	 * Reads the entire contents of a file into a String using BufferedReader.
	 * Supports loading from both the file system (development) and classpath (JAR).
	 * 
	 * @param log      Logger instance for error logging
	 * @param filePath Path to the file - can be absolute file system path or
	 *                 classpath-relative path
	 * @return String containing the entire file contents
	 * @throws Throwable if file is not found or any I/O error occurs
	 */
	public String readFileWithBufferedReader(Logger log, String filePath) throws Throwable {
		try {
			// Remove leading slash if present to normalize the path
			// ClassLoader.getResourceAsStream() doesn't work with leading slashes
			filePath = filePath.startsWith("/") ? filePath.substring(1) : filePath;

			// Create a File object to check if it exists on the file system
			File file = new File(filePath);

			// Initialize InputStream using try-with-resources to avoid Lombok @Cleanup and
			// ensure proper null-check
			try (InputStream inputStream = file.exists() ? new FileInputStream(file)
					: getClass().getClassLoader().getResourceAsStream(filePath)) {

				// Validate that the resource was found
				if (inputStream == null) {
					throw new FileNotFoundException("Resource not found in classpath: " + filePath); // BUG FIX: Should be
																																														// filePath, not inputStream
				}

				// Create BufferedReader with UTF-8 encoding for proper character handling
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

					// Variables for reading lines
					String line;
					StringBuilder lines = new StringBuilder(); // NOTE: StringBuilder is preferred over StringBuffer

					// Read file line by line and append to buffer
					while ((line = reader.readLine()) != null) {
						lines.append(line);
						// NOTE: This removes line breaks - original file formatting is lost
						// Consider appending System.lineSeparator() if line breaks are needed
					}

					// Return the complete file contents as a single string
					return lines.toString();
				}
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}

	/**
	 * Decodes a DER-encoded integer from a ByteBuffer.
	 * DER (Distinguished Encoding Rules) is a binary encoding format used in
	 * cryptography.
	 * 
	 * @param input ByteBuffer containing the DER-encoded data
	 * @return BigInteger representation of the decoded value
	 */
	private BigInteger derint(ByteBuffer input) {
		// Read the length of the integer value using the der() method
		// 0x02 is the DER tag for INTEGER type
		int len = der(input, 0x02);

		// Create a byte array to hold the integer value
		byte[] value = new byte[len];

		// Read the actual integer bytes from the buffer
		input.get(value);

		// Convert to BigInteger with positive sign (+1)
		// This ensures the value is treated as unsigned
		return new BigInteger(+1, value);
	}

	/**
	 * Reads and validates a DER tag and returns its length.
	 * 
	 * @param input ByteBuffer containing the DER-encoded data
	 * @param exp   Expected tag value to validate against
	 * @return Length of the data following the tag
	 * @throws IllegalArgumentException if tag doesn't match expected value or
	 *                                  length is invalid
	 */
	private int der(ByteBuffer input, int exp) {
		// Read the tag byte and convert to unsigned int
		int tag = input.get() & 0xFF;

		// Verify the tag matches what we expect (e.g., 0x30 for SEQUENCE, 0x02 for
		// INTEGER)
		if (tag != exp)
			throw new IllegalArgumentException("Unexpected tag");

		// Read the length byte
		int n = input.get() & 0xFF;

		// If length is less than 128, it's a short form (single byte length)
		if (n < 128)
			return n;

		// Long form: the lower 7 bits indicate how many bytes encode the length
		n &= 0x7F;

		// Validate that length is encoded in 1 or 2 bytes (common for RSA keys)
		if ((n < 1) || (n > 2))
			throw new IllegalArgumentException("Invalid length");

		// Read the actual length value from the next n bytes
		int len = 0;
		while (n-- > 0) {
			len <<= 8; // Shift left by 8 bits (1 byte)
			len |= input.get() & 0xFF; // Add the next byte
		}

		return len;
	}

	/**
	 * Skips over a DER-encoded element by reading its tag and length, then
	 * advancing the position.
	 * 
	 * @param input ByteBuffer containing the DER-encoded data
	 */
	private void skipDerElement(ByteBuffer input) {
		// Read tag
		input.get();

		// Read length
		int n = input.get() & 0xFF;
		int len;

		if (n < 128) {
			len = n;
		} else {
			n &= 0x7F;
			len = 0;
			while (n-- > 0) {
				len <<= 8;
				len |= input.get() & 0xFF;
			}
		}

		// Skip the content
		input.position(input.position() + len);
	}

	/**
	 * Detects if the encoded key is in PKCS#8 format by examining its structure.
	 * PKCS#8 structure: SEQUENCE { version, algorithm, privateKey }
	 * PKCS#1 structure: SEQUENCE { version, modulus, publicExponent, ... }
	 * 
	 * @param data Byte array containing the DER-encoded key
	 * @return true if PKCS#8 format, false if PKCS#1 format
	 */
	public boolean isPKCS8Format(byte[] data) {
		try {
			ByteBuffer buffer = ByteBuffer.wrap(data);

			// Read outer SEQUENCE tag and length
			if (der(buffer, 0x30) != buffer.remaining()) {
				return false;
			}

			// Read version
			@SuppressWarnings("unused")
			BigInteger version = derint(buffer);

			// In PKCS#8, after version (0), the next element is a SEQUENCE (algorithm
			// identifier)
			// In PKCS#1, after version (0), the next element is an INTEGER (modulus)
			int position = buffer.position();
			int nextTag = buffer.get(position) & 0xFF;

			// 0x30 = SEQUENCE tag (indicates PKCS#8)
			// 0x02 = INTEGER tag (indicates PKCS#1)
			return nextTag == 0x30;

		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Extracts PKCS#1 private key data from PKCS#8 envelope.
	 * PKCS#8 wraps PKCS#1 data inside: SEQUENCE { version, algorithm, OCTET STRING
	 * { PKCS#1 data } }
	 * 
	 * @param pkcs8Data Byte array containing PKCS#8 encoded key
	 * @return Byte array containing the inner PKCS#1 encoded key
	 */
	public byte[] extractPKCS1FromPKCS8(byte[] pkcs8Data) {
		ByteBuffer input = ByteBuffer.wrap(pkcs8Data);

		// Read outer SEQUENCE
		if (der(input, 0x30) != input.remaining()) {
			throw new IllegalArgumentException("Invalid PKCS#8 structure");
		}

		// Read and verify version (should be 0)
		if (!BigInteger.ZERO.equals(derint(input))) {
			throw new IllegalArgumentException("Unsupported PKCS#8 version");
		}

		// Skip algorithm identifier SEQUENCE
		skipDerElement(input);

		// Read OCTET STRING tag (0x04) which contains the PKCS#1 data
		int pkcs1Length = der(input, 0x04);

		// Extract PKCS#1 data
		byte[] pkcs1Data = new byte[pkcs1Length];
		input.get(pkcs1Data);

		return pkcs1Data;
	}

	/**
	 * Parses PKCS#1 format and returns RSAPrivateCrtKeySpec.
	 * 
	 * @param pkcs1Data Byte array containing PKCS#1 encoded key
	 * @return RSAPrivateCrtKeySpec containing all RSA key components
	 */
	public RSAPrivateCrtKeySpec parsePKCS1(byte[] pkcs1Data) {
		ByteBuffer input = ByteBuffer.wrap(pkcs1Data);

		// Validate DER structure: 0x30 is the SEQUENCE tag
		if (der(input, 0x30) != input.remaining()) {
			throw new IllegalArgumentException("Excess data in PKCS#1");
		}

		// Verify version is 0 (two-prime RSA key)
		if (!BigInteger.ZERO.equals(derint(input))) {
			throw new IllegalArgumentException("Unsupported PKCS#1 version");
		}

		// Extract RSA key components in order per PKCS#1 specification
		BigInteger modulus = derint(input); // n
		BigInteger publicExponent = derint(input); // e
		BigInteger privateExponent = derint(input); // d
		BigInteger prime1 = derint(input); // p
		BigInteger prime2 = derint(input); // q
		BigInteger exponent1 = derint(input); // d mod (p-1)
		BigInteger exponent2 = derint(input); // d mod (q-1)
		BigInteger coefficient = derint(input); // (inverse of q) mod p

		return new RSAPrivateCrtKeySpec(
				modulus, publicExponent, privateExponent,
				prime1, prime2, exponent1, exponent2, coefficient);
	}

	/**
	 * Signs a string input using SHA256 with RSA private key.
	 * Automatically detects and handles both PKCS#1 and PKCS#8 formats.
	 * 
	 * @param log   Logger instance for error logging
	 * @param input Plain text string to be signed
	 * @return Base64-encoded signature string
	 * @throws Throwable if any error occurs during key loading, parsing, or signing
	 */
	public String signSHA256RSA(Logger log, String signingKeyId, String input) throws Throwable {
		try {
			PrivateKey privateKey = null;

			// Try to get the public key from cache first
			if (rsaKeyCacheService != null && rsaKeyCacheService.hasPrivateKey(signingKeyId)) {
				log.debug("Using cached private key for key ID: {}", signingKeyId);
				privateKey = rsaKeyCacheService.getPrivateKey(signingKeyId);
			}
			if (privateKey != null) {
				// Initialize signature with SHA256withRSA algorithm
				java.security.Signature sig = java.security.Signature.getInstance("SHA256WithRSA");
				sig.initSign(privateKey);

				// Update signature with the input data (converted to UTF-8 bytes)
				sig.update(input.getBytes(StandardCharsets.UTF_8));

				// Generate the signature
				byte[] signatureBytes = sig.sign();

				// Return Base64-encoded signature
				return Base64.encodeBase64String(signatureBytes);
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
		return null;
	}

	/**
	 * Verifies a SHA256-RSA signature against plain text using an RSA public key.
	 * Loads the RSA public key from a file in the classpath based on the signing
	 * key ID.
	 * The filename pattern is: {signingKeyId}-rsa-public
	 * 
	 * @param log          Logger instance for error logging
	 * @param plainText    Original plain text that was signed
	 * @param signedValue  Base64-encoded signature to verify
	 * @param signingKeyId Key identifier used to construct the filename (e.g.,
	 *                     "spring" for "spring-rsa-public.pem")
	 * @return true if signature is valid, false otherwise
	 * @throws Throwable if any error occurs during key loading or verification
	 */
	public boolean verifySHA256RSA(Logger log, String plainText, String signedValue, String signingKeyId)
			throws Throwable {
		try {
			PublicKey publicKey = null;

			// Try to get the public key from cache first
			if (rsaKeyCacheService != null && rsaKeyCacheService.hasPublicKey(signingKeyId)) {
				log.debug("Using cached public key for key ID: {}", signingKeyId);
				publicKey = rsaKeyCacheService.getPublicKey(signingKeyId);
			}

			// If public key is still null, throw exception
			if (publicKey == null) {
				log.error("No public key found for signing key ID: {}", signingKeyId);
				throw new IllegalArgumentException("Public key not found for signing key ID: " + signingKeyId);
			}

			// Initialize signature verification with SHA256withRSA algorithm
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(publicKey);

			// Update signature with the plain text (converted to UTF-8 bytes)
			signature.update((plainText).getBytes(StandardCharsets.UTF_8));

			// Verify the signature and return the result
			return signature.verify(Base64.decodeBase64(signedValue));

		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}
}
