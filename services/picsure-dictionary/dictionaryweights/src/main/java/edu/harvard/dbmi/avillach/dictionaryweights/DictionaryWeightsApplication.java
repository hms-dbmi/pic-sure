package edu.harvard.dbmi.avillach.dictionaryweights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@SpringBootApplication
public class DictionaryWeightsApplication {

	private static final Logger LOG = LoggerFactory.getLogger(DictionaryWeightsApplication.class);

	@Autowired
	WeightingParser parser;

	@Autowired
	WeightUpdateCreator updateCreator;

	@Autowired
	WeightUpdateApplier updateApplier;

	@Value("${weights.filename}")
	private String weightingFileName;


	public static void main(String[] args) throws IOException {
		SpringApplication.run(DictionaryWeightsApplication.class, args);
	}

	@Bean
	public CommandLineRunner commandLineRunner() {
		return ignored -> {
			LOG.info("Starting weighting...");
			File file = new File(weightingFileName);
			if (!file.exists() || file.isDirectory()) {
				LOG.error("File not found {}", weightingFileName);
				System.exit(1);
			}

			LOG.info("Found weighting file. Parsing weights...");
			List<String> rawWeights = Files.readAllLines(file.toPath());
			List<Weight> weightings = parser.parseWeights(rawWeights);
			LOG.info("Found {} weights", weightings.size());
			if (weightings.isEmpty()) {
				LOG.warn("Since no weightings were parsed, exiting.");
				System.exit(1);
			}

			String update = updateCreator.createUpdate(weightings);
			updateApplier.applyUpdate(update);
			LOG.info("Done. Exiting");
			System.exit(0);
		};
	}

}
