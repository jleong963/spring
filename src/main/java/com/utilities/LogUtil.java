package com.utilities;

import org.slf4j.Logger;

/**
 * Centralized error logging helper.
 *
 * <p>
 * Replaces the boilerplate that was previously duplicated in every
 * {@code catch} block: it locates the stack frame belonging to the method that
 * caught the exception and logs the class, line number, exception type and
 * message. If no matching frame is found (e.g. the exception originated in
 * another thread) it falls back to logging the full stack trace so errors are
 * never silently lost.
 * </p>
 *
 * @author jleong963
 */
public final class LogUtil {

	private LogUtil() {
	}

	/**
	 * Logs the given throwable against the caller's stack frame.
	 *
	 * @param log the SLF4J logger to use
	 * @param e   the throwable to log
	 */
	public static void logError(Logger log, Throwable e) {
		// index 0 = getStackTrace, 1 = this method, 2 = the caller (the catch block)
		StackTraceElement[] callerStack = Thread.currentThread().getStackTrace();
		if (callerStack.length > 2 && e != null) {
			StackTraceElement caller = callerStack[2];
			for (StackTraceElement element : e.getStackTrace()) {
				if (caller.getClassName().equals(element.getClassName())
						&& caller.getMethodName().equals(element.getMethodName())) {
					log.error("Error in {} at line {}: {} - {}",
							element.getClassName(),
							element.getLineNumber(),
							e.getClass().getName(),
							e.getMessage());
					return;
				}
			}
		}
		// Fallback: matching frame not found - log the full throwable so it is not lost
		log.error("Error: {} - {}", e == null ? "null" : e.getClass().getName(),
				e == null ? "" : e.getMessage(), e);
	}
}
