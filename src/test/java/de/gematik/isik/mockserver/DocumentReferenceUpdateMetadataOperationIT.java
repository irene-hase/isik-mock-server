package de.gematik.isik.mockserver;

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
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.Application;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static de.gematik.isik.mockserver.helper.ResourceLoadingHelper.loadResourceAsString;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class)
@ActiveProfiles("integrationtest")
class DocumentReferenceUpdateMetadataOperationIT {

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private FhirContext fhirContext;

	@Autowired
	private DaoRegistry daoRegistry;

	private String getServerUrl() {
		return "http://localhost:" + port + "/fhir/";
	}

	@ParameterizedTest
	@ValueSource(strings = {"fhir-examples/valid/valid-update-metadata-parameters.json", "fhir-examples/invalid/invalid-update-metadata-parameters-invalid-docstatus-code.json"})
	void testUpdateMetaData(String input) {
		String body = loadResourceAsString(input);
		String expectedDocStatus = ((Parameters) fhirContext.newJsonParser().parseResource(body))
				.getParameter().get(0).getValue().primitiveValue();
		String documentReferenceId = createDocumentReference(DocumentReference.ReferredDocumentStatus.PRELIMINARY);
		String url = String.format("%s/DocumentReference/%s/$update-metadata", getServerUrl(), documentReferenceId);

		HttpHeaders headers = new HttpHeaders();
		headers.add("Content-Type", "application/fhir+json");
		headers.add("Accept", "application/fhir+json");
		HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);

		ResponseEntity<String> response = restTemplate.exchange(
				url,
				HttpMethod.POST,
				requestEntity,
				String.class
		);

		boolean isValid = !input.contains("invalid");
		assertThat(response.getStatusCode().value()).isEqualTo(isValid ? 200 : 400);
		if(!isValid) {
			assertThat(response.getBody()).isNotNull();
			assertThat(response.getBody()).contains("Invalid docStatus value: 'unknown-code'");
		} else {
			assertThat(response.getBody()).isNotNull();
			assertThat(response.getBody()).contains("\"docStatus\":\"" + expectedDocStatus + "\"");
		}
	}

	@Test
	void testUpdateMetaData_EnteredInErrorHidesDocumentFromDefaultSearch() {
		String documentReferenceId = createDocumentReference(DocumentReference.ReferredDocumentStatus.PRELIMINARY);
		String updateUrl = String.format("%s/DocumentReference/%s/$update-metadata", getServerUrl(), documentReferenceId);
		String updateBody = loadResourceAsString("fhir-examples/valid/valid-update-metadata-parameters-entered-in-error.json");

		ResponseEntity<String> updateResponse = restTemplate.exchange(
				updateUrl,
				HttpMethod.POST,
				new HttpEntity<>(updateBody, createFhirHeaders()),
				String.class);

		assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateResponse.getBody()).isNotNull();
		assertThat(updateResponse.getBody())
			.contains("\"docStatus\":\"entered-in-error\"")
			.contains("\"status\":\"entered-in-error\"");

		ResponseEntity<String> defaultSearchResponse = restTemplate.exchange(
				String.format("%s/DocumentReference?_id=%s", getServerUrl(), documentReferenceId),
				HttpMethod.GET,
				new HttpEntity<>(createFhirHeaders()),
				String.class);

		assertThat(defaultSearchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		Bundle defaultSearchBundle =
				(Bundle) fhirContext.newJsonParser().parseResource(defaultSearchResponse.getBody());
		assertThat(defaultSearchBundle.getEntry()).isEmpty();

		ResponseEntity<String> explicitSearchResponse = restTemplate.exchange(
				String.format(
						"%s/DocumentReference?_id=%s&status=entered-in-error",
						getServerUrl(),
						documentReferenceId),
				HttpMethod.GET,
				new HttpEntity<>(createFhirHeaders()),
				String.class);

		assertThat(explicitSearchResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		Bundle explicitSearchBundle =
				(Bundle) fhirContext.newJsonParser().parseResource(explicitSearchResponse.getBody());
		assertThat(explicitSearchBundle.getEntry())
			.hasSize(1)
			.first()
			.satisfies(entry -> assertThat(entry.getResource().getIdElement().getIdPart()).isEqualTo(documentReferenceId));
	}

	private String createDocumentReference(DocumentReference.ReferredDocumentStatus docStatus) {
		DocumentReference documentReference = (DocumentReference) fhirContext
				.newJsonParser()
				.parseResource(loadResourceAsString("fhir-examples/valid/DocumentReference-Valid-Example.json"));
		documentReference.setId(UUID.randomUUID().toString());
		documentReference.setDocStatus(docStatus);
		documentReference.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
		documentReference.getIdentifierFirstRep().setValue("urn:uuid:" + UUID.randomUUID());
		documentReference.getMasterIdentifier().setValue("urn:uuid:" + UUID.randomUUID());

		IdType createdId = (IdType) daoRegistry
				.getResourceDao(DocumentReference.class)
				.create(documentReference, (ca.uhn.fhir.rest.api.server.RequestDetails) null)
				.getId();

		assertThat(createdId).isNotNull();
		return createdId.getIdPart();
	}

	private HttpHeaders createFhirHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.add("Content-Type", "application/fhir+json");
		headers.add("Accept", "application/fhir+json");
		return headers;
	}
}
