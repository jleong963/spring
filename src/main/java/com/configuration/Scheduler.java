package com.configuration;
import com.utilities.LogUtil;

import java.util.Deque;
import java.util.UUID;

import org.jboss.logging.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import com.pojo.template.Pojo;
import com.service.template.SampleThreadService;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/*
 * Here will configure the trigger date time & it's parameter for each task created in class, BatchJobConfig
 * */
@Slf4j
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "2h") // To enable lock on scheduler
@ConditionalOnProperty(// Only create this bean if a specific property has a specific value.
		prefix = "spring.task.scheduling", name = "enabled", havingValue = "true", matchIfMissing = false)
public class Scheduler {

	private final JobOperator jobOperator;

	private final Job job;

	// @Qualifier("<bean name>") - To match with bean name for created job in
	// BatchJobConfig
	public Scheduler(JobOperator jobOperator, @Qualifier("sampleJob") Job job, SampleThreadService sampleThreadService) {
		this.jobOperator = jobOperator;
		this.job = job;
		this.sampleThreadService = sampleThreadService;
	}

	@Scheduled(cron = "0 */5 * * * *", zone = "Asia/Kuala_Lumpur")
	@SchedulerLock(name = "sampleTask", lockAtMostFor = "10m", lockAtLeastFor = "1m")
	@Async // Run on separate thread, non-blocking the scheduler
	public void sampleTask() {
		String uuid = UUID.randomUUID().toString();
		MDC.put("X-Request-ID", uuid);
		try {
			JobParameters parameters = new JobParametersBuilder()
					.addString("requestId", uuid)
					.addLong("timestamp", System.currentTimeMillis())
					.toJobParameters();
			JobExecution jobExecution = jobOperator.start(job, parameters);
			if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
				log.error("Job failed with exit status: {}", jobExecution.getExitStatus());
				// Send alert/notification
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		} finally {
			MDC.clear();
		}
	}

	private final SampleThreadService sampleThreadService;

	@Scheduled(cron = "0 */10 * * * *", zone = "Asia/Kuala_Lumpur")
	@SchedulerLock(name = "sampleTask2", lockAtMostFor = "10m", lockAtLeastFor = "1m")
	@Async // Run on separate thread, non-blocking the scheduler
	public void sampleTask2() {
		UUID xRequestId = UUID.randomUUID();
		MDC.put("X-Request-ID", xRequestId);
		log.info("Sample task 2 start.");
		try {
			Deque<Pojo> deque = sampleThreadService.addDummyRecord(1000);
			for (int i = 0; i < 2; i++) {
				sampleThreadService.processRecords(xRequestId, (i + 1), deque);
			}
		} catch (Throwable e) {
			LogUtil.logError(log, e);
		} finally {
			log.info("Sample task 2 end.");
			MDC.clear();
		}
	}
}
