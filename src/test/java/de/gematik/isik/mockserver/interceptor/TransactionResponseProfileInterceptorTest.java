package de.gematik.isik.mockserver.interceptor;

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

import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TransactionResponseProfileInterceptor}.
 *
 * <p>Verifies that the interceptor correctly enriches transaction-response bundles with the
 * ISiKMedikationTransactionResponse profile and populates {@code entry.fullUrl} from
 * {@code entry.response.location}, as required by the ISiK Medikation Stufe 5 specification.
 */
@ExtendWith(MockitoExtension.class)
class TransactionResponseProfileInterceptorTest {

    private static final String ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE =
            "https://gematik.de/fhir/isik/StructureDefinition/ISiKMedikationTransactionResponse";
    private static final String SERVER_BASE = "http://localhost:8080/fhir";

    private TransactionResponseProfileInterceptor interceptor;

    @Mock
    private RequestDetails requestDetails;

    @BeforeEach
    void setUp() {
        interceptor = new TransactionResponseProfileInterceptor();
    }

    // -------------------------------------------------------------------------
    // outgoingResponse — top-level dispatch
    // -------------------------------------------------------------------------

    @Test
    void outgoingResponse_ignoresNonBundleResources() {
        Patient patient = new Patient();
        interceptor.outgoingResponse(requestDetails, patient);
        // No exception — silently ignored
    }

    @Test
    void outgoingResponse_ignoresNonTransactionResponseBundles() {
        Bundle searchSet = new Bundle();
        searchSet.setType(Bundle.BundleType.SEARCHSET);

        interceptor.outgoingResponse(requestDetails, searchSet);

        assertThat(searchSet.getMeta().getProfile()).isEmpty();
    }

    @Test
    void outgoingResponse_enrichesTransactionResponseBundle() {
        when(requestDetails.getServerBaseForRequest()).thenReturn(SERVER_BASE);

        Bundle responseBundle = createTransactionResponseBundle(
                "Medication/123/_history/1", null);

        interceptor.outgoingResponse(requestDetails, responseBundle);

        // Profile should be added
        assertThat(responseBundle.getMeta().getProfile())
                .extracting(CanonicalType::getValue)
                .contains(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);

        // fullUrl should be populated
        assertThat(responseBundle.getEntryFirstRep().getFullUrl())
                .isEqualTo(SERVER_BASE + "/Medication/123");
    }

    // -------------------------------------------------------------------------
    // addProfileIfMissing
    // -------------------------------------------------------------------------

    @Nested
    class AddProfileIfMissingTests {

        @Test
        void addsProfileWhenMissing() {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);

            interceptor.addProfileIfMissing(bundle);

            assertThat(bundle.getMeta().getProfile())
                    .extracting(CanonicalType::getValue)
                    .containsExactly(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);
        }

        @Test
        void doesNotDuplicateProfileWhenAlreadyPresent() {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
            bundle.getMeta().addProfile(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);

            interceptor.addProfileIfMissing(bundle);

            assertThat(bundle.getMeta().getProfile())
                    .extracting(CanonicalType::getValue)
                    .containsExactly(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);
        }

        @Test
        void preservesExistingProfilesWhileAddingOwn() {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
            bundle.getMeta().addProfile("http://example.org/SomeOtherProfile");

            interceptor.addProfileIfMissing(bundle);

            assertThat(bundle.getMeta().getProfile())
                    .extracting(CanonicalType::getValue)
                    .containsExactlyInAnyOrder(
                            "http://example.org/SomeOtherProfile",
                            ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);
        }
    }

    // -------------------------------------------------------------------------
    // populateEntryFullUrls
    // -------------------------------------------------------------------------

    @Nested
    class PopulateEntryFullUrlsTests {

        @Test
        void setsFullUrlFromAbsoluteLocation() {
            Bundle bundle = createTransactionResponseBundle(
                    "http://example.org/fhir/Medication/456/_history/2", null);

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            assertThat(bundle.getEntryFirstRep().getFullUrl())
                    .isEqualTo("http://example.org/fhir/Medication/456");
        }

        @Test
        void setsFullUrlFromRelativeLocationUsingServerBase() {
            when(requestDetails.getServerBaseForRequest()).thenReturn(SERVER_BASE);

            Bundle bundle = createTransactionResponseBundle(
                    "MedicationRequest/789/_history/1", null);

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            assertThat(bundle.getEntryFirstRep().getFullUrl())
                    .isEqualTo(SERVER_BASE + "/MedicationRequest/789");
        }

        @Test
        void doesNotOverwriteExistingFullUrl() {
            String existingFullUrl = "http://existing.example.org/Medication/111";
            Bundle bundle = createTransactionResponseBundle(
                    "Medication/999/_history/3", existingFullUrl);

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            assertThat(bundle.getEntryFirstRep().getFullUrl()).isEqualTo(existingFullUrl);
        }

        @Test
        void handlesEntryWithNoResponse() {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
            bundle.addEntry(); // entry without response

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            assertThat(bundle.getEntryFirstRep().getFullUrl()).isNullOrEmpty();
        }

        @Test
        void handlesEntryWithNullLocation() {
            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.getResponse().setStatus("201 Created");
            // location is null

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            assertThat(entry.getFullUrl()).isNullOrEmpty();
        }

        @Test
        void populatesFullUrlsForMultipleEntries() {
            when(requestDetails.getServerBaseForRequest()).thenReturn(SERVER_BASE);

            Bundle bundle = new Bundle();
            bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);

            addEntryWithLocation(bundle, "Medication/100/_history/1");
            addEntryWithLocation(bundle, "MedicationStatement/200/_history/1");
            addEntryWithLocation(bundle, "http://remote.example.org/Patient/300/_history/2");

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            assertThat(bundle.getEntry()).hasSize(3);
            assertThat(bundle.getEntry().get(0).getFullUrl())
                    .isEqualTo(SERVER_BASE + "/Medication/100");
            assertThat(bundle.getEntry().get(1).getFullUrl())
                    .isEqualTo(SERVER_BASE + "/MedicationStatement/200");
            assertThat(bundle.getEntry().get(2).getFullUrl())
                    .isEqualTo("http://remote.example.org/Patient/300");
        }

        @Test
        void handlesNullRequestDetails() {
            Bundle bundle = createTransactionResponseBundle(
                    "http://example.org/fhir/Medication/456/_history/1", null);

            interceptor.populateEntryFullUrls(bundle, null);

            // Should still work for absolute URLs
            assertThat(bundle.getEntryFirstRep().getFullUrl())
                    .isEqualTo("http://example.org/fhir/Medication/456");
        }

        @Test
        void handlesRelativeLocationWithNullServerBase() {
            when(requestDetails.getServerBaseForRequest()).thenReturn(null);

            Bundle bundle = createTransactionResponseBundle(
                    "Medication/456/_history/1", null);

            interceptor.populateEntryFullUrls(bundle, requestDetails);

            // Falls back to relative URL when server base is unknown
            assertThat(bundle.getEntryFirstRep().getFullUrl()).isEqualTo("Medication/456");
        }
    }

    // -------------------------------------------------------------------------
    // deriveFullUrl (static helper)
    // -------------------------------------------------------------------------

    @Nested
    class DeriveFullUrlTests {

        @ParameterizedTest
        @CsvSource({
            "http://localhost:8080/fhir/Medication/123/_history/1, http://localhost:8080/fhir/Medication/123",
            "https://example.org/fhir/Patient/abc/_history/42, https://example.org/fhir/Patient/abc",
            "http://server/fhir/MedicationRequest/1/_history/99, http://server/fhir/MedicationRequest/1"
        })
        void stripsHistorySuffixFromAbsoluteUrls(String location, String expectedFullUrl) {
            assertThat(TransactionResponseProfileInterceptor.deriveFullUrl(location, null))
                    .isEqualTo(expectedFullUrl);
        }

        @ParameterizedTest
        @CsvSource({
            "Medication/123/_history/1, http://base/fhir, http://base/fhir/Medication/123",
            "Patient/abc/_history/2, http://base/fhir/, http://base/fhir/Patient/abc"
        })
        void prependsServerBaseForRelativeUrls(String location, String serverBase, String expectedFullUrl) {
            assertThat(TransactionResponseProfileInterceptor.deriveFullUrl(location, serverBase))
                    .isEqualTo(expectedFullUrl);
        }

        @Test
        void returnsLocationWithoutHistoryWhenNoHistorySuffix() {
            assertThat(TransactionResponseProfileInterceptor.deriveFullUrl(
                    "http://example.org/fhir/Medication/123", null))
                    .isEqualTo("http://example.org/fhir/Medication/123");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void returnsNullForBlankOrNullLocation(String location) {
            assertThat(TransactionResponseProfileInterceptor.deriveFullUrl(location, SERVER_BASE))
                    .isNull();
        }

        @Test
        void returnsRelativePathWhenServerBaseIsNull() {
            assertThat(TransactionResponseProfileInterceptor.deriveFullUrl(
                    "Medication/123/_history/1", null))
                    .isEqualTo("Medication/123");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Bundle createTransactionResponseBundle(String location, String existingFullUrl) {
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);

        Bundle.BundleEntryComponent entry = bundle.addEntry();
        if (existingFullUrl != null) {
            entry.setFullUrl(existingFullUrl);
        }
        entry.getResponse()
                .setStatus("201 Created")
                .setLocation(location);

        return bundle;
    }

    private static void addEntryWithLocation(Bundle bundle, String location) {
        bundle.addEntry()
                .getResponse()
                .setStatus("201 Created")
                .setLocation(location);
    }
}

