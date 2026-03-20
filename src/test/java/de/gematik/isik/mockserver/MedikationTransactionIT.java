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
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static de.gematik.isik.mockserver.helper.ResourceLoadingHelper.loadResourceAsString;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ISiKMedikationTransaction and ISiKMedikationTransactionResponse conformance.
 *
 * <p>Verifies that the server correctly processes FHIR transaction bundles as required by the ISiK
 * Medikation Stufe 5 specification:
 * <ul>
 *   <li>Transaction bundles are accepted via POST to the server root</li>
 *   <li>Resources within the transaction are persisted atomically</li>
 *   <li>The response is a transaction-response bundle</li>
 *   <li>The response includes the ISiKMedikationTransactionResponse profile</li>
 *   <li>Each entry carries a fullUrl (absolute URL of the resource)</li>
 *   <li>Each entry carries a response with status, location (with /_history/), and optionally outcome</li>
 * </ul>
 *
 * @see <a href="https://simplifier.net/guide/isik-medikation-stufe-5/Einfuehrung/Artefakte/Datenobjekt_MedikationTransactionResponse">
 *     ISiKMedikationTransactionResponse</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class)
@ActiveProfiles("integrationtest")
@Slf4j
class MedikationTransactionIT {

    private static final int ONE_MINUTE = 60 * 1000;
    private static final String ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE =
            "https://gematik.de/fhir/isik/StructureDefinition/ISiKMedikationTransactionResponse";

    private IGenericClient ourClient;
    private static FhirContext ourCtx;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void beforeAll() {
        ourCtx = FhirContext.forR4();
        ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ourCtx.getRestfulClientFactory().setSocketTimeout(ONE_MINUTE);
    }

    @BeforeEach
    void beforeEach() {
        ourClient = ourCtx.newRestfulGenericClient(getServerUrl());
    }

    private String getServerUrl() {
        return String.format("http://localhost:%s/fhir/", port);
    }

    @Test
    void transactionBundleIsProcessedAndReturnsTransactionResponse() {
        // Build a minimal transaction bundle with a Medication resource
        Bundle transactionBundle = new Bundle();
        transactionBundle.setType(Bundle.BundleType.TRANSACTION);

        Medication medication = new Medication();
        medication.setStatus(Medication.MedicationStatus.ACTIVE);
        medication.getCode().addCoding()
                .setSystem("http://fhir.de/CodeSystem/bfarm/atc")
                .setCode("V03AB23")
                .setDisplay("Acetylcystein")
                .setVersion("2024");

        transactionBundle.addEntry()
                .setFullUrl("urn:uuid:a1b2c3d4-e5f6-4a90-abcd-ef1234567890")
                .setResource(medication)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.POST)
                .setUrl("Medication");

        // Execute transaction
        Bundle responseBundle = ourClient.transaction().withBundle(transactionBundle).execute();

        // Verify response is a transaction-response
        assertThat(responseBundle).isNotNull();
        assertThat(responseBundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTIONRESPONSE);
        assertThat(responseBundle.getEntry()).hasSize(1);

        // Verify the response includes the ISiKMedikationTransactionResponse profile
        assertThat(responseBundle.getMeta().getProfile())
                .extracting(CanonicalType::getValue)
                .contains(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);

        // Verify entry-level Must-Support fields per ISiKMedikationTransactionResponse
        Bundle.BundleEntryComponent entry = responseBundle.getEntryFirstRep();

        // entry.fullUrl MUST be an absolute URL of the resource
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must be populated (MS in ISiKMedikationTransactionResponse)")
                .isNotBlank();
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must be an absolute URL")
                .startsWith("http");
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must reference the Medication resource type")
                .contains("Medication/");
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must NOT contain /_history/ (that belongs in response.location)")
                .doesNotContain("/_history/");

        // entry.response.status MUST be present
        Bundle.BundleEntryResponseComponent entryResponse = entry.getResponse();
        assertThat(entryResponse.getStatus())
                .as("entry.response.status must be populated")
                .startsWith("201");

        // entry.response.location MUST be present and contain /_history/ per FHIR spec
        assertThat(entryResponse.getLocation())
                .as("entry.response.location must be populated (MS in ISiKMedikationTransactionResponse)")
                .isNotBlank();
        assertThat(entryResponse.getLocation())
                .as("entry.response.location must contain /_history/ per FHIR specification")
                .contains("/_history/");
    }

    @Test
    void transactionBundleWithMultipleResourcesIsProcessedAtomically() {
        // Build a transaction with both Medication and MedicationRequest
        Bundle transactionBundle = new Bundle();
        transactionBundle.setType(Bundle.BundleType.TRANSACTION);

        // Entry 1: Medication
        Medication medication = new Medication();
        medication.setStatus(Medication.MedicationStatus.ACTIVE);
        medication.getCode().addCoding()
                .setSystem("http://fhir.de/CodeSystem/bfarm/atc")
                .setCode("N02BE01")
                .setDisplay("Paracetamol")
                .setVersion("2024");

        transactionBundle.addEntry()
                .setFullUrl("urn:uuid:b2c3d4e5-f6a7-4901-bcde-f12345678901")
                .setResource(medication)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.POST)
                .setUrl("Medication");

        // Entry 2: MedicationRequest referencing the Medication via fullUrl
        MedicationRequest medicationRequest = new MedicationRequest();
        medicationRequest.setStatus(MedicationRequest.MedicationRequestStatus.ACTIVE);
        medicationRequest.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
        medicationRequest.setMedication(
                new org.hl7.fhir.r4.model.Reference("urn:uuid:b2c3d4e5-f6a7-4901-bcde-f12345678901"));
        medicationRequest.setSubject(
                new org.hl7.fhir.r4.model.Reference("Patient/Mustermann"));

        transactionBundle.addEntry()
                .setFullUrl("urn:uuid:c3d4e5f6-a7b8-4012-cdef-123456789012")
                .setResource(medicationRequest)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.POST)
                .setUrl("MedicationRequest");

        // Execute transaction
        Bundle responseBundle = ourClient.transaction().withBundle(transactionBundle).execute();

        // Verify response
        assertThat(responseBundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTIONRESPONSE);
        assertThat(responseBundle.getEntry()).hasSize(2);

        // All entries must conform to ISiKMedikationTransactionResponse Must-Support requirements
        for (Bundle.BundleEntryComponent entry : responseBundle.getEntry()) {
            // entry.response.status — 201 Created for new resources
            assertThat(entry.getResponse().getStatus()).startsWith("201");

            // entry.fullUrl — must be an absolute URL (MS)
            assertThat(entry.getFullUrl())
                    .as("entry.fullUrl must be populated for each entry")
                    .isNotBlank();
            assertThat(entry.getFullUrl())
                    .as("entry.fullUrl must be an absolute URL")
                    .startsWith("http");
            assertThat(entry.getFullUrl())
                    .as("entry.fullUrl must not contain /_history/ suffix")
                    .doesNotContain("/_history/");

            // entry.response.location — must contain /_history/ (MS)
            assertThat(entry.getResponse().getLocation())
                    .as("entry.response.location must be populated")
                    .isNotBlank();
            assertThat(entry.getResponse().getLocation())
                    .as("entry.response.location must contain /_history/ per FHIR spec")
                    .contains("/_history/");
        }
    }

    @Test
    void transactionBundleWithUpdateIsProcessed() {
        // First, create a Medication resource directly
        Medication medication = new Medication();
        medication.setId("med-update-test");
        medication.setStatus(Medication.MedicationStatus.ACTIVE);
        medication.getCode().addCoding()
                .setSystem("http://fhir.de/CodeSystem/bfarm/atc")
                .setCode("V03AB23")
                .setDisplay("Acetylcystein")
                .setVersion("2024");

        ourClient.update().resource(medication).execute();

        // Now update it via a transaction bundle (PUT)
        medication.setStatus(Medication.MedicationStatus.INACTIVE);

        Bundle transactionBundle = new Bundle();
        transactionBundle.setType(Bundle.BundleType.TRANSACTION);

        transactionBundle.addEntry()
                .setFullUrl(getServerUrl() + "Medication/med-update-test")
                .setResource(medication)
                .getRequest()
                .setMethod(Bundle.HTTPVerb.PUT)
                .setUrl("Medication/med-update-test");

        // Execute transaction
        Bundle responseBundle = ourClient.transaction().withBundle(transactionBundle).execute();

        // Verify response
        assertThat(responseBundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTIONRESPONSE);
        assertThat(responseBundle.getEntry()).hasSize(1);

        Bundle.BundleEntryComponent entry = responseBundle.getEntryFirstRep();

        // Should be 200 OK for update
        assertThat(entry.getResponse().getStatus()).startsWith("200");

        // entry.fullUrl must be populated and absolute (MS)
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must be populated for PUT transactions")
                .isNotBlank();
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must be an absolute URL")
                .startsWith("http");
        assertThat(entry.getFullUrl())
                .as("entry.fullUrl must reference the updated resource")
                .contains("Medication/med-update-test");

        // entry.response.location must contain /_history/ (MS)
        assertThat(entry.getResponse().getLocation())
                .as("entry.response.location must be populated")
                .isNotBlank();
        assertThat(entry.getResponse().getLocation())
                .as("entry.response.location must contain /_history/ per FHIR spec")
                .contains("/_history/");
    }

    @Test
    void transactionBundleViaRawPost() {
        // Test posting a transaction bundle via raw HTTP to the server root,
        // ensuring the DocumentPOSTInterceptor does not block it
        String body = loadResourceAsString("fhir-examples/valid/valid-medikation-transaction.json");

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/fhir+json");
        headers.add("Accept", "application/fhir+json");
        HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.exchange(
                getServerUrl(),
                HttpMethod.POST,
                requestEntity,
                String.class);

        // Transaction should be processed, not blocked
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        // Parse response as Bundle
        Bundle responseBundle = ourCtx.newJsonParser().parseResource(Bundle.class, response.getBody());
        assertThat(responseBundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTIONRESPONSE);

        // Verify profile tag
        assertThat(responseBundle.getMeta().getProfile())
                .extracting(CanonicalType::getValue)
                .contains(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);

        // Verify each entry has the required Must-Support fields
        assertThat(responseBundle.getEntry()).isNotEmpty();
        for (Bundle.BundleEntryComponent entry : responseBundle.getEntry()) {
            assertThat(entry.getFullUrl())
                    .as("entry.fullUrl must be populated in raw POST transaction response")
                    .isNotBlank();
            assertThat(entry.getFullUrl())
                    .as("entry.fullUrl must be an absolute URL")
                    .startsWith("http");

            assertThat(entry.getResponse()).isNotNull();
            assertThat(entry.getResponse().getStatus())
                    .as("entry.response.status must be populated")
                    .isNotBlank();
            assertThat(entry.getResponse().getLocation())
                    .as("entry.response.location must be populated")
                    .isNotBlank();
            assertThat(entry.getResponse().getLocation())
                    .as("entry.response.location must contain /_history/")
                    .contains("/_history/");
        }
    }
}



