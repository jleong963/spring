package com.service.template;
import com.utilities.LogUtil;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import com.enums.ResponseCode;
import com.pojo.ApiResponse;
import com.pojo.template.Pojo;
import com.utilities.Tool;

@Service
public class SampleService {

	private final Tool tool;

	public SampleService(Tool tool) {
		this.tool = tool;
	}

	private final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();

	public String generateRandomIc() {
		// Format: XXXXX-XX-XXXX
		return String.format("%06d-%02d-%04d",
				threadLocalRandom.nextInt(900) + 100,
				threadLocalRandom.nextInt(100),
				threadLocalRandom.nextInt(10000));
	}

	public String generatePassword(int length) {
		String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
		String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		String DIGITS = "0123456789";
		String SPECIAL_CHARS = "!@#$%^&*()-_+=<>?";

		String allChars = LOWERCASE + UPPERCASE + DIGITS + SPECIAL_CHARS;

		return ThreadLocalRandom.current()
				.ints(length, 0, allChars.length())
				.mapToObj(allChars::charAt)
				.collect(StringBuilder::new, (sb, c) -> sb.append(c), (sb1, sb2) -> sb1.append(sb2))
				.toString();
	}

	public ApiResponse putTemplate(Logger log, int id, String ic, Pojo pojo) throws Throwable {
		try {
			switch (id) {
				case 1:
					return ApiResponse.builder()
							.resp_code(ResponseCode.FAILED.getResponse_code())
							.resp_msg(ResponseCode.FAILED.getResponse_desc())
							.datetime(tool.getTodayDateTimeInString())
							.build();
				default:
					return ApiResponse
							.builder()
							.resp_code(ResponseCode.SUCCESS.getResponse_code())
							.resp_msg(ResponseCode.SUCCESS.getResponse_desc())
							.datetime(tool.getTodayDateTimeInString())
							.pojo(pojo)
							.build();
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
			throw e;
		}
	}
}
