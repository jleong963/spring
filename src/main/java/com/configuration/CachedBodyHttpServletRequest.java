package com.configuration;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Custom HTTP servlet request wrapper that caches the request body for multiple
 * reads.
 * 
 * <p>
 * <b>Problem it solves:</b>
 * </p>
 * In a standard HTTP request, the input stream can only be read once. This
 * becomes problematic
 * when multiple components (filters, interceptors, controllers) need to access
 * the request body.
 * For example, when validating a signature in a filter and then deserializing
 * the same body
 * in a controller with {@code @RequestBody}, the second read would fail with
 * "Required request body is missing".
 * 
 * <p>
 * <b>How it works:</b>
 * </p>
 * This wrapper reads and caches the entire request body into a byte array when
 * the wrapper is created.
 * Subsequent calls to {@code getInputStream()} or {@code getReader()} return
 * new instances that read
 * from the cached byte array, allowing the body to be read multiple times
 * throughout the request lifecycle.
 * 
 * <p>
 * <b>Use cases:</b>
 * </p>
 * <ul>
 * <li>Request signature validation in filters (e.g., HMAC, RSA signature
 * verification)</li>
 * <li>Request body logging for audit trails</li>
 * <li>Custom request validation before reaching the controller</li>
 * <li>Rate limiting based on request body content</li>
 * </ul>
 * 
 * <p>
 * <b>Usage example:</b>
 * </p>
 * 
 * <pre>
 * {@code
 * // In a servlet filter
 * CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
 * 
 * // Read body for signature validation
 * String body = cachedRequest.getBody();
 * boolean isValid = validateSignature(body, request.getHeader("SIGNATURE"));
 * 
 * // Pass wrapped request down the chain - controller can still read the body
 * chain.doFilter(cachedRequest, response);
 * }
 * </pre>
 * 
 * <p>
 * <b>Memory consideration:</b>
 * </p>
 * The entire request body is loaded into memory. For very large payloads (e.g.,
 * file uploads),
 * consider implementing a size limit or using streaming approaches instead.
 * 
 * @see HttpServletRequestWrapper
 * @see CustomOncePerRequestFilter
 * @author jleong963
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    /**
     * Cached byte array containing the entire request body.
     * This is populated once during wrapper construction and reused for all
     * subsequent reads.
     */
    private byte[] cachedBody;

    /**
     * Constructs a new CachedBodyHttpServletRequest by wrapping the original
     * request
     * and immediately reading and caching its body content.
     * 
     * @param request the original {@link HttpServletRequest} to wrap
     * @throws IOException if an I/O error occurs while reading the request body
     */
    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        // Cache the request body immediately when wrapper is created
        // This ensures the original input stream is read before it's closed or consumed
        // elsewhere
        InputStream requestInputStream = request.getInputStream();
        this.cachedBody = requestInputStream.readAllBytes();
    }

    /**
     * Returns a {@link ServletInputStream} that reads from the cached request body.
     * This method can be called multiple times, and each call returns a new stream
     * instance reading from the beginning of the cached body.
     * 
     * @return a new ServletInputStream backed by the cached body bytes
     */
    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(this.cachedBody);
    }

    /**
     * Returns a {@link BufferedReader} for reading the cached request body as
     * character data.
     * The reader uses UTF-8 encoding to interpret the cached bytes.
     * 
     * @return a new BufferedReader backed by the cached body bytes
     */
    @Override
    public BufferedReader getReader() {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
        return new BufferedReader(new InputStreamReader(byteArrayInputStream, StandardCharsets.UTF_8));
    }

    /**
     * Returns the entire cached request body as a UTF-8 encoded string.
     * This is a convenience method for accessing the body content directly
     * without needing to read from the input stream.
     * 
     * <p>
     * <b>Example usage:</b>
     * </p>
     * 
     * <pre>
     * {@code
     * CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
     * String jsonBody = cachedRequest.getBody();
     * // Validate signature using the body
     * boolean valid = signatureValidator.verify(jsonBody, signature);
     * }
     * </pre>
     * 
     * @return the cached request body as a string
     */
    public String getBody() {
        return new String(this.cachedBody, StandardCharsets.UTF_8);
    }

    /**
     * Custom {@link ServletInputStream} implementation that reads from a cached
     * byte array.
     * This allows the request body to be read multiple times by creating new
     * instances
     * of this class, each with its own position pointer into the shared byte array.
     * 
     * <p>
     * This inner class is package-private and only used internally by the wrapper.
     * </p>
     */
    private static class CachedBodyServletInputStream extends ServletInputStream {

        /**
         * Input stream backed by the cached body byte array.
         * Each instance of CachedBodyServletInputStream has its own stream
         * with an independent position pointer.
         */
        private final InputStream cachedBodyInputStream;

        /**
         * Constructs a new CachedBodyServletInputStream from a cached byte array.
         * 
         * @param cachedBody the byte array containing the cached request body
         */
        public CachedBodyServletInputStream(byte[] cachedBody) {
            this.cachedBodyInputStream = new ByteArrayInputStream(cachedBody);
        }

        /**
         * Indicates whether all data from the input stream has been read.
         * 
         * @return {@code true} if no more data is available to read, {@code false}
         *         otherwise
         */
        @Override
        public boolean isFinished() {
            try {
                return cachedBodyInputStream.available() == 0;
            } catch (IOException e) {
                return true;
            }
        }

        /**
         * Indicates whether the input stream is ready to be read without blocking.
         * Since the data is cached in memory, this always returns {@code true}.
         * 
         * @return {@code true} always, as reading from a byte array never blocks
         */
        @Override
        public boolean isReady() {
            return true;
        }

        /**
         * Sets the read listener for asynchronous I/O operations.
         * 
         * <p>
         * <b>Note:</b> This implementation does not support asynchronous reads
         * because the cached body is already fully loaded in memory.
         * </p>
         * 
         * @param readListener the read listener (not used)
         * @throws UnsupportedOperationException always, as async reads are not
         *                                       supported
         */
        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException("Asynchronous reads are not supported for cached request bodies");
        }

        /**
         * Reads the next byte of data from the cached input stream.
         * 
         * @return the next byte of data, or -1 if the end of the stream is reached
         * @throws IOException if an I/O error occurs
         */
        @Override
        public int read() throws IOException {
            return cachedBodyInputStream.read();
        }
    }
}