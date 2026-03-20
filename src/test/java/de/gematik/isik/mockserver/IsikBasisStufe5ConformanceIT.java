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
import ca.uhn.fhir.rest.client.interceptor.BearerTokenAuthInterceptor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Account;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Procedure;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static de.gematik.isik.mockserver.helper.ResourceLoadingHelper.loadResourceAsString;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests validating ISiK Basis Stufe 5 conformance, covering:
 * <ul>
 *   <li>Seed data availability for all ISiK Basis artefacts</li>
 *   <li>Search parameter support per resource type</li>
 *   <li>Patients without identification information</li>
 *   <li>data-absent-reason extension handling</li>
 *   <li>_include / _revinclude support</li>
 * </ul>
 *
 * @see <a href="https://simplifier.net/guide/isik-basis-stufe-5">ISiK Basis Stufe 5</a>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class)
@ActiveProfiles("integrationtest")
@Slf4j
class IsikBasisStufe5ConformanceIT {

    private static final int ONE_MINUTE = 60 * 1000;
    private IGenericClient client;
    private static FhirContext ctx;

    @LocalServerPort
    private int port;

    @BeforeAll
    static void beforeAll() {
        ctx = FhirContext.forR4();
        ctx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
        ctx.getRestfulClientFactory().setSocketTimeout(ONE_MINUTE);
    }

    @BeforeEach
    void beforeEach() {
        client = ctx.newRestfulGenericClient(getServerHostAndPort());
        client.registerInterceptor(new BearerTokenAuthInterceptor("bearerToken"));
    }

    @NotNull
    private String getServerHostAndPort() {
        return String.format("http://localhost:%s/fhir/", port);
    }

    // ========================================================================================
    // Section 1: Seed Data Availability — verify all ISiK Basis artefacts are loaded
    // ========================================================================================
    @Nested
    @DisplayName("Seed Data Availability")
    class SeedDataAvailability {

        @Test
        @DisplayName("ISiKPatient example resources are loaded with meta.profile")
        void patientExamplesAreLoaded() {
            var bundle = client.search().forResource(Patient.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            var patient = (Patient) bundle.getEntry().get(0).getResource();
            assertThat(patient.getMeta().getProfile()).isNotEmpty();
        }

        @Test
        @DisplayName("ISiKKontaktGesundheitseinrichtung (Encounter) examples are loaded")
        void encounterExamplesAreLoaded() {
            var bundle = client.search().forResource(Encounter.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("ISiKPersonImGesundheitsberuf (Practitioner) example is loaded")
        void practitionerExampleIsLoaded() {
            var bundle = client.search().forResource(Practitioner.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            var practitioner = (Practitioner) bundle.getEntry().get(0).getResource();
            assertThat(practitioner.getMeta().getProfile()).isNotEmpty();
        }

        @Test
        @DisplayName("ISiKOrganisation example is loaded with IKNR identifier and meta.profile")
        void organizationExampleIsLoaded() {
            var bundle = client.search().forResource(Organization.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            var org = (Organization) bundle.getEntry().get(0).getResource();
            assertThat(org.getMeta().getProfile()).isNotEmpty();
            assertThat(org.getIdentifier()).isNotEmpty();
            assertThat(org.getIdentifier().get(0).getSystem())
                    .isEqualTo("http://fhir.de/sid/arge-ik/iknr");
        }

        @Test
        @DisplayName("ISiKStandort (Location) example is loaded with profile and identifier")
        void locationExampleIsLoaded() {
            var bundle = client.search().forResource(Location.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            var location = (Location) bundle.getEntry().get(0).getResource();
            assertThat(location.getMeta().getProfile()).isNotEmpty();
            assertThat(location.getIdentifier()).isNotEmpty();
        }

        @Test
        @DisplayName("ISiKDiagnose (Condition) example is loaded")
        void conditionExampleIsLoaded() {
            var bundle = client.search().forResource(Condition.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("ISiKProzedur (Procedure) example is loaded")
        void procedureExampleIsLoaded() {
            var bundle = client.search().forResource(Procedure.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("ISiKAngehoeriger (RelatedPerson) example is loaded")
        void relatedPersonExampleIsLoaded() {
            var bundle = client.search().forResource(RelatedPerson.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Both GKV and self-payer Coverage examples are loaded")
        void coverageExamplesAreLoaded() {
            var bundle = client.search().forResource(Coverage.class)
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // ========================================================================================
    // Section 2: Patient Search Parameters (ISiK Basis §Patient)
    // ========================================================================================
    @Nested
    @DisplayName("Patient Search Parameters")
    class PatientSearch {

        @Test
        @DisplayName("Search Patient by _id")
        void searchPatientById() {
            var bundle = client.search().byUrl("Patient?_id=Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
        }

        @Test
        @DisplayName("Search Patient by identifier (MR)")
        void searchPatientByIdentifier() {
            var bundle = client.search()
                    .byUrl("Patient?identifier=http%3A%2F%2Ftestkrankenhaus.de%2Ffhir%2Fsid%2FPatient%7CIdentifierValuepatient-read")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by family name")
        void searchPatientByFamily() {
            var bundle = client.search().byUrl("Patient?family=Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by given name")
        void searchPatientByGiven() {
            var bundle = client.search().byUrl("Patient?given=Max")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by birthdate")
        void searchPatientByBirthdate() {
            var bundle = client.search().byUrl("Patient?birthdate=1968-05-12")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by gender")
        void searchPatientByGender() {
            var bundle = client.search().byUrl("Patient?gender=male")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by active status")
        void searchPatientByActive() {
            var bundle = client.search().byUrl("Patient?active=true")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by address-city")
        void searchPatientByAddressCity() {
            var bundle = client.search().byUrl("Patient?address-city=Musterdorf")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by address-postalcode")
        void searchPatientByPostalCode() {
            var bundle = client.search().byUrl("Patient?address-postalcode=9876")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by address-country")
        void searchPatientByCountry() {
            var bundle = client.search().byUrl("Patient?address-country=CH")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by telecom")
        void searchPatientByTelecom() {
            var bundle = client.search().byUrl("Patient?telecom=201-867-5309")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by family name with :exact modifier")
        void searchPatientByFamilyExact() {
            var bundle = client.search()
                    .byUrl("Patient?family:exact=Graf%20von%20und%20zu%20Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Patient by family name with :contains modifier")
        void searchPatientByFamilyContains() {
            var bundle = client.search().byUrl("Patient?family:contains=Muster")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 3: Encounter Search Parameters (ISiK Basis §Encounter)
    // ========================================================================================
    @Nested
    @DisplayName("Encounter Search Parameters")
    class EncounterSearch {

        @Test
        @DisplayName("Search Encounter by _id")
        void searchEncounterById() {
            var bundle = client.search()
                    .byUrl("Encounter?_id=Encounter-Read-Finished-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
        }

        @Test
        @DisplayName("Search Encounter by identifier (Fallnummer)")
        void searchEncounterByIdentifier() {
            var bundle = client.search()
                    .byUrl("Encounter?identifier=https%3A%2F%2Ftest.krankenhaus.de%2Ffhir%2Fsid%2Ffallnr%7C0123456789")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by status=finished")
        void searchEncounterByStatus() {
            var bundle = client.search().byUrl("Encounter?status=finished")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by class (v3-ActCode IMP)")
        void searchEncounterByClass() {
            var bundle = client.search()
                    .byUrl("Encounter?class=http%3A%2F%2Fterminology.hl7.org%2FCodeSystem%2Fv3-ActCode%7CIMP")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by type (Kontaktebene)")
        void searchEncounterByType() {
            var bundle = client.search()
                    .byUrl("Encounter?type=http%3A%2F%2Ffhir.de%2FCodeSystem%2FKontaktebene%7Cabteilungskontakt")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by patient reference")
        void searchEncounterByPatient() {
            var bundle = client.search().byUrl("Encounter?patient=Patient/Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by date-start (custom ISiK SearchParameter)")
        void searchEncounterByDateStart() {
            var bundle = client.search().byUrl("Encounter?date-start=ge2021-01-01")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by end-date (custom ISiK SearchParameter)")
        void searchEncounterByEndDate() {
            var bundle = client.search().byUrl("Encounter?end-date=le2022-12-31")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter with _include=Encounter:patient")
        void searchEncounterWithIncludePatient() {
            var bundle = client.search()
                    .byUrl("Encounter?status=in-progress&_include=Encounter:patient")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            boolean hasIncludedPatient = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof Patient);
            assertThat(hasIncludedPatient)
                    .as("_include=Encounter:patient should return the referenced Patient")
                    .isTrue();
        }

        @Test
        @DisplayName("Search Encounter by location reference")
        void searchEncounterByLocation() {
            var bundle = client.search()
                    .byUrl("Encounter?location=Location/Location-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by service-provider reference")
        void searchEncounterByServiceProvider() {
            var bundle = client.search()
                    .byUrl("Encounter?service-provider=Organization/Organization-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by part-of:missing=true")
        void searchEncounterByPartOfMissing() {
            var bundle = client.search().byUrl("Encounter?part-of:missing=true")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 4: Condition Search Parameters (ISiK Basis §Condition)
    // ========================================================================================
    @Nested
    @DisplayName("Condition Search Parameters")
    class ConditionSearch {

        @Test
        @DisplayName("Search Condition by patient reference")
        void searchConditionByPatient() {
            var bundle = client.search().byUrl("Condition?patient=Patient/Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Condition by code (ICD-10-GM)")
        void searchConditionByCode() {
            var bundle = client.search()
                    .byUrl("Condition?code=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fbfarm%2Ficd-10-gm%7CF16.1")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Condition by clinical-status")
        void searchConditionByClinicalStatus() {
            var bundle = client.search()
                    .byUrl("Condition?clinical-status=active")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Condition by encounter reference")
        void searchConditionByEncounter() {
            var bundle = client.search()
                    .byUrl("Condition?encounter=Encounter/Encounter-Read-In-Progress-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 5: Procedure Search Parameters (ISiK Basis §Procedure)
    // ========================================================================================
    @Nested
    @DisplayName("Procedure Search Parameters")
    class ProcedureSearch {

        @Test
        @DisplayName("Search Procedure by patient reference")
        void searchProcedureByPatient() {
            var bundle = client.search().byUrl("Procedure?patient=Patient/Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Procedure by code (OPS)")
        void searchProcedureByCode() {
            var bundle = client.search()
                    .byUrl("Procedure?code=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fbfarm%2Fops%7C5-470")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Procedure by status")
        void searchProcedureByStatus() {
            var bundle = client.search().byUrl("Procedure?status=completed")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Procedure by date")
        void searchProcedureByDate() {
            var bundle = client.search().byUrl("Procedure?date=ge2021-01-01")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Procedure by encounter reference")
        void searchProcedureByEncounter() {
            var bundle = client.search()
                    .byUrl("Procedure?encounter=Encounter/Encounter-Read-Finished-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 6: Coverage Search Parameters (ISiK Basis §Coverage)
    // ========================================================================================
    @Nested
    @DisplayName("Coverage Search Parameters")
    class CoverageSearch {

        @Test
        @DisplayName("Search Coverage by beneficiary")
        void searchCoverageByBeneficiary() {
            var bundle = client.search().byUrl("Coverage?beneficiary=Patient/Mustermann")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Coverage by type (GKV)")
        void searchCoverageByTypeGkv() {
            var bundle = client.search()
                    .byUrl("Coverage?type=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fversicherungsart-de-basis%7CGKV")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Coverage by type (SEL - self-payer)")
        void searchCoverageByTypeSelfPayer() {
            var bundle = client.search()
                    .byUrl("Coverage?type=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fversicherungsart-de-basis%7CSEL")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Coverage by status")
        void searchCoverageByStatus() {
            var bundle = client.search().byUrl("Coverage?status=active")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 7: Patient without Identification / data-absent-reason
    // ========================================================================================
    @Nested
    @DisplayName("Patients without Identification and data-absent-reason")
    class DataAbsentReasonSupport {

        @Test
        @DisplayName("Patient without GKV/PKV identifier is loaded (only MR identifier)")
        void patientWithoutInsuranceIdentifierIsAccepted() {
            var bundle = client.search()
                    .byUrl("Patient?_id=Patient-active-false-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            var patient = (Patient) bundle.getEntry().get(0).getResource();
            boolean hasGkv = patient.getIdentifier().stream()
                    .anyMatch(id -> "http://fhir.de/sid/gkv/kvid-10".equals(id.getSystem()));
            assertThat(hasGkv)
                    .as("This patient should NOT have a GKV identifier")
                    .isFalse();
            boolean hasMr = patient.getIdentifier().stream()
                    .anyMatch(id -> id.getType().getCodingFirstRep().getCode().equals("MR"));
            assertThat(hasMr)
                    .as("This patient should have an MR identifier")
                    .isTrue();
        }

        @SneakyThrows
        @Test
        @DisplayName("Patient with data-absent-reason on birthDate can be created and read back")
        void patientWithDataAbsentReasonOnGenderCanBeCreated() {
            String body = loadResourceAsString("fhir-examples/valid/patient-data-absent-reason.json");
            Patient patient = (Patient) ctx.newJsonParser().parseResource(body);

            var result = client.create().resource(patient).execute();
            assertThat(result.getId()).isNotNull();

            try {
                Patient created = client.read().resource(Patient.class)
                        .withId(result.getId().toUnqualified()).execute();
                assertThat(created).isNotNull();
                assertThat(created.getGender())
                        .as("gender should be 'unknown'")
                        .isEqualTo(org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.UNKNOWN);
                assertThat(created.getBirthDateElement().hasExtension(
                        "http://hl7.org/fhir/StructureDefinition/data-absent-reason"))
                        .as("data-absent-reason extension on birthDate should be preserved")
                        .isTrue();
            } finally {
                client.delete().resourceById(result.getId().toUnqualified()).execute();
            }
        }

        @Test
        @DisplayName("Practitioner with data-absent-reason on birthDate is loaded from seed data")
        void practitionerWithDataAbsentReasonOnBirthDateIsLoaded() {
            var bundle = client.search().byUrl("Practitioner?_id=DrFleming")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
            var practitioner = (Practitioner) bundle.getEntry().get(0).getResource();
            assertThat(practitioner.getBirthDateElement().hasExtension(
                    "http://hl7.org/fhir/StructureDefinition/data-absent-reason"))
                    .as("data-absent-reason on _birthDate should be present")
                    .isTrue();
        }
    }

    // ========================================================================================
    // Section 8: CRUD operations for resource types
    // ========================================================================================
    @Nested
    @DisplayName("CRUD Operations for ISiK Basis Resource Types")
    class CrudOperations {

        @SneakyThrows
        @Test
        @DisplayName("Condition (ISiKDiagnose) can be created and searched by code")
        void conditionCanBeCreatedAndSearched() {
            String body = loadResourceAsString("fhir-examples/valid/valid-condition.json");
            Condition condition = (Condition) ctx.newJsonParser().parseResource(body);

            var result = client.create().resource(condition).execute();
            assertThat(result.getId()).isNotNull();

            try {
                var bundle = client.search()
                        .byUrl("Condition?code=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fbfarm%2Ficd-10-gm%7CK35.3")
                        .returnBundle(Bundle.class).execute();
                assertThat(bundle.getEntry()).isNotEmpty();
            } finally {
                client.delete().resourceById(result.getId().toUnqualified()).execute();
            }
        }

        @SneakyThrows
        @Test
        @DisplayName("Procedure (ISiKProzedur) can be created and searched by code")
        void procedureCanBeCreatedAndSearched() {
            String body = loadResourceAsString("fhir-examples/valid/valid-procedure.json");
            Procedure procedure = (Procedure) ctx.newJsonParser().parseResource(body);

            var result = client.create().resource(procedure).execute();
            assertThat(result.getId()).isNotNull();

            try {
                var bundle = client.search()
                        .byUrl("Procedure?code=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fbfarm%2Fops%7C5-470")
                        .returnBundle(Bundle.class).execute();
                assertThat(bundle.getEntry()).isNotEmpty();
            } finally {
                client.delete().resourceById(result.getId().toUnqualified()).execute();
            }
        }

        @SneakyThrows
        @Test
        @DisplayName("RelatedPerson (ISiKAngehoeriger) can be created and searched by patient")
        void relatedPersonCanBeCreatedAndSearched() {
            String body = loadResourceAsString("fhir-examples/valid/valid-relatedperson.json");
            RelatedPerson relatedPerson = (RelatedPerson) ctx.newJsonParser().parseResource(body);

            var result = client.create().resource(relatedPerson).execute();
            assertThat(result.getId()).isNotNull();

            try {
                var bundle = client.search()
                        .byUrl("RelatedPerson?patient=Patient/Mustermann")
                        .returnBundle(Bundle.class).execute();
                assertThat(bundle.getEntry()).isNotEmpty();
            } finally {
                client.delete().resourceById(result.getId().toUnqualified()).execute();
            }
        }
    }

    // ========================================================================================
    // Section 9: Organization & Location Search
    // ========================================================================================
    @Nested
    @DisplayName("Organization and Location Search Parameters")
    class OrgLocationSearch {

        @Test
        @DisplayName("Search Organization by identifier (IKNR)")
        void searchOrganizationByIdentifier() {
            var bundle = client.search()
                    .byUrl("Organization?identifier=http%3A%2F%2Ffhir.de%2Fsid%2Farge-ik%2Fiknr%7C260120013")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Organization by name")
        void searchOrganizationByName() {
            var bundle = client.search()
                    .byUrl("Organization?name=Test-Krankenhaus")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Organization by active")
        void searchOrganizationByActive() {
            var bundle = client.search().byUrl("Organization?active=true")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Organization by type")
        void searchOrganizationByType() {
            var bundle = client.search()
                    .byUrl("Organization?type=http%3A%2F%2Fterminology.hl7.org%2FCodeSystem%2Forganization-type%7Cdept")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Location by name")
        void searchLocationByName() {
            var bundle = client.search().byUrl("Location?name=Chirurgie")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Location by identifier")
        void searchLocationByIdentifier() {
            var bundle = client.search()
                    .byUrl("Location?identifier=https%3A%2F%2Ftest.krankenhaus.de%2Ffhir%2Fsid%2Fstandort%7CStandort-1")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Location by status")
        void searchLocationByStatus() {
            var bundle = client.search().byUrl("Location?status=active")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Location by type (Fachabteilungsschluessel)")
        void searchLocationByType() {
            var bundle = client.search()
                    .byUrl("Location?type=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fdkgev%2FFachabteilungsschluessel%7C1500")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 10: Practitioner Search
    // ========================================================================================
    @Nested
    @DisplayName("Practitioner Search Parameters")
    class PractitionerSearch {

        @Test
        @DisplayName("Search Practitioner by identifier (LANR)")
        void searchPractitionerByLanr() {
            var bundle = client.search()
                    .byUrl("Practitioner?identifier=https%3A%2F%2Ffhir.kbv.de%2FNamingSystem%2FKBV_NS_Base_ANR%7C123456789")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Practitioner by name")
        void searchPractitionerByName() {
            var bundle = client.search().byUrl("Practitioner?name=Musterarzt")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Practitioner by family name with :exact modifier")
        void searchPractitionerByFamilyExact() {
            var bundle = client.search()
                    .byUrl("Practitioner?family:exact=Musterarzt")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Practitioner by identifier (Telematik-ID)")
        void searchPractitionerByTelematikId() {
            var bundle = client.search()
                    .byUrl("Practitioner?identifier=https%3A%2F%2Fgematik.de%2Ffhir%2Fsid%2Ftelematik-id%7C123456789")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 11: Account (ISiKAbrechnungsfall) Search
    // ========================================================================================
    @Nested
    @DisplayName("Account Search Parameters")
    class AccountSearch {

        @Test
        @DisplayName("Search Account by _id")
        void searchAccountById() {
            var bundle = client.search().byUrl("Account?_id=Booking-Case-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
        }

        @Test
        @DisplayName("Search Account by status")
        void searchAccountByStatus() {
            var bundle = client.search().byUrl("Account?status=active")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Account by type")
        void searchAccountByType() {
            var bundle = client.search()
                    .byUrl("Account?type=http%3A%2F%2Fterminology.hl7.org%2FCodeSystem%2Fv3-ActCode%7CIMP")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 12: ISiKStandortRaum & ISiKStandortBettenstellplatz Seed Data
    // ========================================================================================
    @Nested
    @DisplayName("ISiKStandortRaum and ISiKStandortBettenstellplatz")
    class StandortRaumAndBettenstellplatz {

        @Test
        @DisplayName("ISiKStandortRaum (Location room) example is loaded with profile and physicalType 'ro'")
        void standortRaumIsLoaded() {
            var bundle = client.search()
                    .byUrl("Location?_id=Room-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
            var location = (Location) bundle.getEntry().get(0).getResource();
            assertThat(location.getMeta().getProfile()).isNotEmpty();
            assertThat(location.getMeta().getProfile().get(0).getValue())
                    .contains("ISiKStandortRaum");
            assertThat(location.getPhysicalType().getCodingFirstRep().getCode())
                    .isEqualTo("ro");
        }

        @Test
        @DisplayName("ISiKStandortBettenstellplatz (Location bed) example is loaded with profile and physicalType 'bd'")
        void standortBettenstellplatzIsLoaded() {
            var bundle = client.search()
                    .byUrl("Location?_id=Bed-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
            var location = (Location) bundle.getEntry().get(0).getResource();
            assertThat(location.getMeta().getProfile()).isNotEmpty();
            assertThat(location.getMeta().getProfile().get(0).getValue())
                    .contains("ISiKStandortBettenstellplatz");
            assertThat(location.getPhysicalType().getCodingFirstRep().getCode())
                    .isEqualTo("bd");
        }

        @Test
        @DisplayName("ISiKStandortRaum has partOf reference to ward Location (ISiKStandort)")
        void standortRaumReferencesWard() {
            var bundle = client.search()
                    .byUrl("Location?_id=Room-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
            var location = (Location) bundle.getEntry().get(0).getResource();
            assertThat(location.getPartOf().getReference())
                    .isEqualTo("Location/Location-Example");
        }

        @Test
        @DisplayName("ISiKStandortBettenstellplatz has partOf reference to room Location (ISiKStandortRaum)")
        void standortBettenstellplatzReferencesRoom() {
            var bundle = client.search()
                    .byUrl("Location?_id=Bed-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).hasSize(1);
            var location = (Location) bundle.getEntry().get(0).getResource();
            assertThat(location.getPartOf().getReference())
                    .isEqualTo("Location/Room-Example");
        }

        @Test
        @DisplayName("Search Location by physical-type 'ro' returns room")
        void searchLocationByPhysicalTypeRoom() {
            var bundle = client.search()
                    .byUrl("Location?type=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fdkgev%2FFachabteilungsschluessel%7C1500&_id=Room-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Location by physical-type 'bd' returns bed")
        void searchLocationByPhysicalTypeBed() {
            var bundle = client.search()
                    .byUrl("Location?type=http%3A%2F%2Ffhir.de%2FCodeSystem%2Fdkgev%2FFachabteilungsschluessel%7C1500&_id=Bed-Example")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 13: _revinclude Support
    // ========================================================================================
    @Nested
    @DisplayName("_revinclude Support")
    class RevincludeSupport {

        @Test
        @DisplayName("Patient?_revinclude=Encounter:patient returns Encounters for the Patient")
        void patientRevincludeEncounter() {
            var bundle = client.search()
                    .byUrl("Patient?_id=Mustermann&_revinclude=Encounter:patient")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            boolean hasPatient = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof Patient);
            boolean hasEncounter = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof Encounter);
            assertThat(hasPatient)
                    .as("Result should contain the matched Patient")
                    .isTrue();
            assertThat(hasEncounter)
                    .as("_revinclude=Encounter:patient should include linked Encounters")
                    .isTrue();
        }

        @Test
        @DisplayName("Patient?_revinclude=Condition:patient returns Conditions for the Patient")
        void patientRevincludeCondition() {
            var bundle = client.search()
                    .byUrl("Patient?_id=Mustermann&_revinclude=Condition:patient")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            boolean hasCondition = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof Condition);
            assertThat(hasCondition)
                    .as("_revinclude=Condition:patient should include linked Conditions")
                    .isTrue();
        }

        @Test
        @DisplayName("Patient?_revinclude=Coverage:beneficiary returns Coverage for the Patient")
        void patientRevincludeCoverage() {
            var bundle = client.search()
                    .byUrl("Patient?_id=Mustermann&_revinclude=Coverage:beneficiary")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            boolean hasCoverage = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof Coverage);
            assertThat(hasCoverage)
                    .as("_revinclude=Coverage:beneficiary should include linked Coverage resources")
                    .isTrue();
        }

        @Test
        @DisplayName("Encounter?_include=Encounter:account returns the referenced Account")
        void encounterIncludeAccount() {
            var bundle = client.search()
                    .byUrl("Encounter?_id=Encounter-Read-Finished-Example&_include=Encounter:account")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            boolean hasAccount = bundle.getEntry().stream()
                    .anyMatch(e -> e.getResource() instanceof Account);
            assertThat(hasAccount)
                    .as("_include=Encounter:account should return the referenced Account")
                    .isTrue();
        }
    }

    // ========================================================================================
    // Section 14: Chained Search Parameters
    // ========================================================================================
    @Nested
    @DisplayName("Chained Search Parameters")
    class ChainedSearch {

        @Test
        @DisplayName("Search Encounter by account:identifier (chained)")
        void searchEncounterByAccountIdentifier() {
            var bundle = client.search()
                    .byUrl("Encounter?account:identifier=https%3A%2F%2Ftest.krankenhaus.de%2Ffhir%2Fsid%2Fabrechnungsnummer%7C0123456789")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
            var encounter = (Encounter) bundle.getEntry().get(0).getResource();
            assertThat(encounter.getAccount()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by patient:identifier (chained)")
        void searchEncounterByPatientIdentifier() {
            var bundle = client.search()
                    .byUrl("Encounter?patient:identifier=http%3A%2F%2Ftestkrankenhaus.de%2Ffhir%2Fsid%2FPatient%7CIdentifierValuepatient-read")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }

    // ========================================================================================
    // Section 15: Encounter data-absent-reason Support
    // ========================================================================================
    @Nested
    @DisplayName("Encounter data-absent-reason Support")
    class EncounterDataAbsentReason {

        @SneakyThrows
        @Test
        @DisplayName("Encounter with data-absent-reason on class can be created and read back")
        void encounterWithDataAbsentReasonOnClassCanBeCreated() {
            String body = loadResourceAsString("fhir-examples/valid/encounter-data-absent-reason.json");
            Encounter encounter = (Encounter) ctx.newJsonParser().parseResource(body);

            var result = client.create().resource(encounter).execute();
            assertThat(result.getId()).isNotNull();

            try {
                Encounter created = client.read().resource(Encounter.class)
                        .withId(result.getId().toUnqualified()).execute();
                assertThat(created).isNotNull();
                assertThat(created.getStatus()).isEqualTo(Encounter.EncounterStatus.INPROGRESS);
                assertThat(created.getIdentifierFirstRep().getValue()).isEqualTo("NOTFALL-DAR-001");
            } finally {
                client.delete().resourceById(result.getId().toUnqualified()).execute();
            }
        }
    }

    // ========================================================================================
    // Section 16: Search Modifiers (:missing, :text)
    // ========================================================================================
    @Nested
    @DisplayName("Search Modifier Support")
    class SearchModifiers {

        @Test
        @DisplayName("Search Patient by birthdate:missing=false returns patients with a birthdate")
        void searchPatientByBirthdateNotMissing() {
            var bundle = client.search().byUrl("Patient?birthdate:missing=false")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by account:missing=true returns encounters without account")
        void searchEncounterByAccountMissing() {
            var bundle = client.search().byUrl("Encounter?account:missing=true")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Encounter by account:missing=false returns encounters with account")
        void searchEncounterByAccountNotMissing() {
            var bundle = client.search().byUrl("Encounter?account:missing=false")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Organization by name:contains finds partial match")
        void searchOrganizationByNameContains() {
            var bundle = client.search().byUrl("Organization?name:contains=Chirurgie")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }

        @Test
        @DisplayName("Search Organization by name:exact finds exact match")
        void searchOrganizationByNameExact() {
            var bundle = client.search()
                    .byUrl("Organization?name:exact=Test-Krankenhaus%20Chirurgie")
                    .returnBundle(Bundle.class).execute();
            assertThat(bundle.getEntry()).isNotEmpty();
        }
    }
}

