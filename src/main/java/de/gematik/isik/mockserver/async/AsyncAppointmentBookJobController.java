package de.gematik.isik.mockserver.async;

/*-
 * #%L
 * isik-mock-server
 * %%
 * Copyright (C) 2025 - 2026 gematik GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * *******
 *
 * For additional notes and disclaimer from gematik and in case of changes
 * by gematik, find details in the "Readme" file.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import de.gematik.isik.mockserver.operation.AppointmentHandlerReturnObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AsyncAppointmentBookJobController {

	private final AsyncAppointmentBookJobService asyncAppointmentBookJobService;
	private final FhirContext fhirContext;

	private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");

	@GetMapping("/async-jobs/{jobId}")
	public ResponseEntity<String> getJobResult(@PathVariable("jobId") String jobId) {
		var optionalJob = asyncAppointmentBookJobService.getJob(jobId);
		if (optionalJob.isEmpty()) {
			return ResponseEntity.notFound().build();
		}

		CompletableFuture<AppointmentHandlerReturnObject> future = optionalJob.get();
		if (!future.isDone()) {
			// Note: The job is not finished yet
			return ResponseEntity.status(HttpStatus.ACCEPTED).build();
		}

		try {
			AppointmentHandlerReturnObject result = future.get();
			var parser = fhirContext.newJsonParser().setPrettyPrint(true);
			if (result.isOperationSuccessful()) {
				String jsonResponse = parser.encodeResourceToString(result.getAppointment());
				return ResponseEntity.status(result.getHttpStatusCode())
						.contentType(FHIR_JSON)
						.body(jsonResponse);
			} else {
				String jsonResponse = parser.encodeResourceToString(result.getOperationOutcome());
				return ResponseEntity.status(result.getHttpStatusCode())
						.contentType(FHIR_JSON)
						.body(jsonResponse);
			}
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
				return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
						.contentType(FHIR_JSON)
						.body(String.format("The async job with id '%s' was interrupted.", jobId));
			}

			log.error(
					"Internal error while processing the asynchronous job with id '{}': {}", jobId, e.getMessage(), e);

			OperationOutcome outcome = new OperationOutcome();
			outcome.addIssue()
					.setDiagnostics(String.format(
							"An internal error occurred while processing the asynchronous job with id '%s'.", jobId));
			var parser = fhirContext.newJsonParser().setPrettyPrint(true);
			String jsonResponse = parser.encodeResourceToString(outcome);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.contentType(FHIR_JSON)
					.body(jsonResponse);
		}
	}
}
