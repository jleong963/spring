package com.configuration;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableBatchProcessing // Enables batch processing
@EnableJdbcJobRepository // Batch 6 defaults to an in-memory job repository; this keeps it JDBC-backed (spring-batch.sql)
public class BatchJobConfig {

	private final JobRepository jobRepository;

	private final PlatformTransactionManager platformTransactionManager;

	public BatchJobConfig(JobRepository jobRepository, PlatformTransactionManager platformTransactionManager) {
		this.jobRepository = jobRepository;
		this.platformTransactionManager = platformTransactionManager;
	}

	/**
	 * Defines a sample batch job that executes a single step
	 * 
	 * Flow:
	 * 1. Creates a new job named "sampleJob"
	 * 2. Configures RunIdIncrementer to generate unique run IDs
	 * 3. Sets sampleStep() as the only step in the job
	 * 
	 * @return Job object representing the configured batch job
	 */
	@Bean(name = "sampleJob")
	Job sampleJob() {
		return new JobBuilder("sampleJob", jobRepository)
				.incrementer(new RunIdIncrementer())
				.start(sampleStep())
				.build();
	}

	/**
	 * Defines a sample batch step that executes a single tasklet
	 * 
	 * Flow:
	 * 1. Creates a new step named "sampleStep"
	 * 2. Configures the step with a tasklet and transaction manager(auto commit by
	 * spring if tasklet success run else rollback)
	 * 3. Builds and returns the configured step
	 * 
	 * @return Step object representing the configured batch step
	 */
	@Bean(name = "sampleStep") // Defines a step
	Step sampleStep() {
		return new StepBuilder("sampleStep", jobRepository)
				.tasklet(sampleTasklet(), platformTransactionManager) // Assigns tasklet and transaction manager
				.build();
	}

	/**
	 * Defines a sample tasklet that represents the actual work performed in the
	 * step
	 * 
	 * Flow:
	 * 1. Takes StepContribution and ChunkContext parameters
	 * 2. Currently just returns FINISHED status without doing any work
	 * 3. Can be enhanced to perform actual batch processing logic
	 * 
	 * @return Tasklet object that executes the step's business logic
	 */
	@Bean
	Tasklet sampleTasklet() {
		return (contribution, chunkContext) -> {
			// Add your batch processing logic here

			return RepeatStatus.FINISHED; // Indicates successful completion
		};
	}
}
