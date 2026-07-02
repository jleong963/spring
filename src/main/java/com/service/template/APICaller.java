package com.service.template;
import com.utilities.LogUtil;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import com.pojo.Property;
import com.service.MTLSCertificationDetectionService;
import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.impl.SimpleTraceGenerator;

import lombok.Cleanup;

@Service
public class APICaller {

	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int SOCKET_TIMEOUT_MS = 5000;
	private static final int CONNECTION_REQUEST_TIMEOUT_MS = 5000;
	private static final int DEFAULT_HTTPS_PORT = 443;

	private final ObjectMapper objectMapper;

	private final MTLSCertificationDetectionService mTlsCertificationDetectionService;

	private final Property property;

	public APICaller(ObjectMapper objectMapper, MTLSCertificationDetectionService mTlsCertificationDetectionService,
			Property property) {
		this.objectMapper = objectMapper;
		this.mTlsCertificationDetectionService = mTlsCertificationDetectionService;
		this.property = property;
	}

	/**
	 * Synchronous HTTP client call with MTLS support
	 * 
	 * @param log Logger instance
	 * @return Response String
	 */
	protected String httpClientApi(Logger log) {
		String result = "";
		String URL = "";
		Object object = new Object();
		try {
			log.info("URL: " + URL);
			log.info("Request: " + objectMapper.writeValueAsString(object));
			if (URL != null && !URL.isBlank()) {
				URI uri = URI.create(URL);
				String host = uri.getHost();
				int port = uri.getPort() == -1 ? DEFAULT_HTTPS_PORT : uri.getPort();
				// Check if MTLS is required
				boolean mtls = mTlsCertificationDetectionService.isMTLSActive(host, port);
				Map<String, X509Certificate[]> certChains = mTlsCertificationDetectionService.loadClientCertChains(
						log,
						property.getServer_ssl_key_store(),
						property.getServer_ssl_key_store_password(),
						property.getServer_ssl_key_store_type());
				// Create SSL context with smart cert selection if needed
				boolean useSmartSelection = mtls && certChains.size() > 1;
				if (useSmartSelection) {
					log.info("MTLS active and multiple certs found — enabling smart selection");
				}
				SSLContext sslContext = mTlsCertificationDetectionService.createSSLContext(
						log,
						property.getServer_ssl_protocol(),
						property.getServer_ssl_key_store(),
						property.getServer_ssl_key_store_password(),
						property.getServer_ssl_key_store_type(),
						property.getServer_ssl_trust_store(),
						property.getServer_ssl_trust_store_password(),
						property.getServer_ssl_trust_store_type(),
						!useSmartSelection,
						null);
				// Enforce TLS versions + hostname verification
				SSLConnectionSocketFactory sslConnectionSocketFactory = new SSLConnectionSocketFactory(
						sslContext,
						property.getServer_ssl_enabled_protocols(),
						null,
						SSLConnectionSocketFactory.getDefaultHostnameVerifier());
				/*
				 * List<NameValuePair> params = new ArrayList<>();
				 * params.add(new BasicNameValuePair("", ));
				 */
				RequestConfig requestConfig = RequestConfig.custom()
						.setConnectTimeout(CONNECT_TIMEOUT_MS)// in miliseconds
						.setSocketTimeout(SOCKET_TIMEOUT_MS)// in miliseconds
						.setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS)// in miliseconds
						.build();
				@Cleanup
				CloseableHttpClient httpClient = HttpClients.custom()
						.setSSLSocketFactory(sslConnectionSocketFactory)
						.setSSLContext(sslContext)
						.setDefaultRequestConfig(requestConfig)
						// .setConnectionManager()
						.build();
				HttpPost httpRequest = new HttpPost(URL);
				/*
				 * HttpGet httpRequest = new HttpGet(URL);
				 * URI uri = new URIBuilder(httpRequest.getURI())
				 * .addParameters(params)
				 * .build();
				 * httpRequest.setURI(uri);
				 */
				// HttpPut httpRequest = new HttpPut(URL);
				// HttpDelete httpRequest = new HttpDelete(URL);
				httpRequest.setEntity(new StringEntity(objectMapper.writeValueAsString(object)));
				httpRequest.setHeader("Content-Type", "application/json; charset=UTF-8");
				for (Header header : httpRequest.getAllHeaders()) {
					log.info(header.getName() + "(Request): " + header.getValue());
				}
				@Cleanup
				CloseableHttpResponse httpResponse = httpClient.execute(httpRequest);
				for (Header header : httpResponse.getAllHeaders()) {
					log.info(header.getName() + "(Response): " + header.getValue());
				}
				HttpEntity entity = httpResponse.getEntity();
				log.info("HTTP Response code: " + httpResponse.getStatusLine().getStatusCode());
				try {
					if (entity != null) {
						String responseString = EntityUtils.toString(entity);
						log.info("Response: " + responseString);
						// Read the response JSON parameter value & put into object as new instance
						object = objectMapper.readValue(responseString, Object.class);
						// Read the response JSON parameter value & patch into existing object
						object = objectMapper.readerForUpdating(object).readValue(responseString);
					}
				} catch (Throwable e) {
					LogUtil.logError(log, e);
				}

			}
		} catch (SocketTimeoutException | ConnectTimeoutException e) {
			LogUtil.logError(log, e);
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		} finally {
			try {

			} catch (Throwable e) {
			}
		}
		return result;
	}

	/**
	 * Asynchronous HTTP client call with MTLS support
	 * 
	 * @param log Logger instance
	 * @return CompletableFuture with response Object
	 */
	protected CompletableFuture<Object> httpClientApiAsync(Logger log) {
		CompletableFuture<Object> result = new CompletableFuture<>();
		String URL = "";
		Object object = new Object();
		try {
			log.info("URL: " + URL);
			log.info("Request: " + objectMapper.writeValueAsString(object));
			if (URL != null && !URL.isBlank()) {
				URI uri = URI.create(URL);
				String host = uri.getHost();
				int port = uri.getPort() == -1 ? DEFAULT_HTTPS_PORT : uri.getPort();
				// Check if MTLS is required
				boolean mtls = mTlsCertificationDetectionService.isMTLSActive(host, port);
				Map<String, X509Certificate[]> certChains = mTlsCertificationDetectionService.loadClientCertChains(
						log,
						property.getServer_ssl_key_store(),
						property.getServer_ssl_key_store_password(),
						property.getServer_ssl_key_store_type());
				// Create SSL context with smart cert selection if needed
				boolean useSmartSelection = mtls && certChains.size() > 1;
				if (useSmartSelection) {
					log.info("MTLS active and multiple certs found — enabling smart selection");
				}
				SSLContext sslContext = mTlsCertificationDetectionService.createSSLContext(
						log,
						property.getServer_ssl_protocol(),
						property.getServer_ssl_key_store(),
						property.getServer_ssl_key_store_password(),
						property.getServer_ssl_key_store_type(),
						property.getServer_ssl_trust_store(),
						property.getServer_ssl_trust_store_password(),
						property.getServer_ssl_trust_store_type(),
						!useSmartSelection,
						null);
				/*
				 * List<NameValuePair> params = new ArrayList<>();
				 * params.add(new BasicNameValuePair("", ));
				 */
				RequestConfig requestConfig = RequestConfig.custom()
						.setConnectTimeout(CONNECT_TIMEOUT_MS)
						.setSocketTimeout(SOCKET_TIMEOUT_MS)
						.setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS)
						.build();
				@Cleanup
				CloseableHttpAsyncClient httpClient = HttpAsyncClients.custom()
						.setSSLContext(sslContext)
						.setDefaultRequestConfig(requestConfig)
						.build();
				httpClient.start();
				HttpPost httpRequest = new HttpPost(URL);
				/*
				 * HttpGet httpRequest = new HttpGet(URL);
				 * URI uri = new URIBuilder(httpRequest.getURI())
				 * .addParameters(params)
				 * .build();
				 * httpRequest.setURI(uri);
				 */
				// HttpPut httpRequest = new HttpPut(URL);
				// HttpDelete httpRequest = new HttpDelete(URL);
				httpRequest.setEntity(new StringEntity(objectMapper.writeValueAsString(object)));
				httpRequest.setHeader("Content-Type", "application/json; charset=UTF-8");
				for (Header header : httpRequest.getAllHeaders()) {
					log.info(header.getName() + "(Request): " + header.getValue());
				}
				httpClient.execute(httpRequest, new FutureCallback<HttpResponse>() {
					@Override
					public void completed(HttpResponse httpResponse) {
						try {
							for (Header header : httpResponse.getAllHeaders()) {
								log.info(header.getName() + "(Response): " + header.getValue());
							}
							log.info("HTTP Response code: " + httpResponse.getStatusLine().getStatusCode());
							HttpEntity entity = httpResponse.getEntity();
							String responseString = EntityUtils.toString(entity);
							log.info("Response: " + responseString);
							// Read the response JSON parameter value & put into object as new instance
							Object object = objectMapper.readValue(responseString, Object.class);
							// Read the response JSON parameter value & patch into existing object
							object = objectMapper.readerForUpdating(object).readValue(responseString);
							result.complete(object);
						} catch (Throwable e) {
							result.completeExceptionally(e);
							LogUtil.logError(log, e);
						}
					}

					@Override
					public void failed(Exception e) {
						result.completeExceptionally(e);
						LogUtil.logError(log, e);
					}

					@Override
					public void cancelled() {
						log.warn("Async HTTP request cancelled");
						result.cancel(true);
					}
				});
			}
		} catch (SocketTimeoutException | ConnectTimeoutException e) {
			result.completeExceptionally(e);
			LogUtil.logError(log, e);
		} catch (Throwable e) {
			result.completeExceptionally(e);
			LogUtil.logError(log, e);
		} finally {
			try {

			} catch (Throwable e) {
			}
		}
		return result;
	}

	/**
	 * ISO 8583 API call with proper resource management and length header handling
	 * 
	 * @param log Logger instance
	 * @return ISO response message or null if error occurs
	 */
	public IsoMessage iso8583Api(Logger log) {
		IsoMessage result = null;
		String ip = "";
		int port = 0;
		try {
			log.info("IP: " + ip);
			log.info("PORT: " + port);
			// Create a new MessageFactory
			MessageFactory<IsoMessage> requestFactory = new MessageFactory<>();
			requestFactory.setUseBinaryMessages(true); // Use binary messages for efficiency
			requestFactory.setAssignDate(true);
			requestFactory.setTraceNumberGenerator(new SimpleTraceGenerator((int) (System.currentTimeMillis() % 100000)));

			// Create a new ISO 8583 message
			IsoMessage request = requestFactory.newMessage(0x200);
			request.setValue(3, "000000", IsoType.NUMERIC, 6); // Processing Code (Sale)
			request.setValue(4, "10000", IsoType.NUMERIC, 12); // Transaction Amount (in cents)
			request.setValue(7, "0729075811", IsoType.NUMERIC, 10); // Transmission Date and Time (MMDDhhmmss)
			request.setValue(11, "123456", IsoType.NUMERIC, 6); // System Trace Audit Number
			request.setValue(41, "12345678", IsoType.ALPHA, 8); // Card Acceptor Terminal ID
			request.setValue(42, "EXTIOTECH", IsoType.ALPHA, 12); // Card Acceptor ID

			// Calculate and set the Message Authentication Code (MAC) if needed

			// Convert the message to byte array for transmission over the network
			log.info("Request: " + request.debugString());
			byte[] messageBytes = request.writeData();

			// Prepare message with length header (2 bytes)
			@Cleanup
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			@Cleanup
			DataOutputStream dos = new DataOutputStream(baos);
			dos.writeShort(messageBytes.length); // Write length header
			dos.write(messageBytes);
			byte[] messageWithHeader = baos.toByteArray();

			@Cleanup
			Socket socket = new Socket();
			// Set timeouts
			socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(SOCKET_TIMEOUT_MS);

			@Cleanup
			OutputStream outputStream = socket.getOutputStream();
			@Cleanup
			InputStream inputStream = socket.getInputStream();

			// Send message
			outputStream.write(messageWithHeader);
			outputStream.flush();

			// Read length header (2 bytes)
			byte[] lengthBytes = new byte[2];
			int headerBytesRead = inputStream.read(lengthBytes);
			if (headerBytesRead != 2) {
				log.error("Failed to read length header, got {} bytes", headerBytesRead);
				return result;
			}

			int messageLength = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
			log.info("Responded message length: {}", messageLength);

			// Read the actual message
			byte[] responseBytes = new byte[messageLength];
			int totalBytesRead = 0;
			while (totalBytesRead < messageLength) {
				int bytesRead = inputStream.read(responseBytes, totalBytesRead, messageLength - totalBytesRead);
				if (bytesRead == -1) {
					log.error("Unexpected end of stream, read {} of {} bytes", totalBytesRead, messageLength);
					return result;
				}
				totalBytesRead += bytesRead;
			}

			log.info("Successfully read {} bytes", totalBytesRead);

			// Parse response
			MessageFactory<IsoMessage> responseFactory = new MessageFactory<>();
			responseFactory.setUseBinaryMessages(true);
			result = responseFactory.parseMessage(responseBytes, 0);
			log.info("Response: {}", result.debugString());
		} catch (SocketTimeoutException | ConnectTimeoutException | SocketException e) {
			LogUtil.logError(log, e);
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		} finally {
			try {

			} catch (Throwable e) {
			}
		}
		return result;
	}
}
