package de.gematik.isik.mockserver.operation;

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
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import de.gematik.isik.mockserver.helper.OperationOutcomeUtils;
import de.gematik.isik.mockserver.helper.ResourceLoadingHelper;
import de.gematik.isik.mockserver.interceptor.FhirValidationHandler;
import de.gematik.refv.commons.validation.ValidationResult;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Schedule;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentBookHandlerTest {

	private AppointmentBookHandler handler;
	private final FhirContext ctx = FhirContext.forR4();
	private DaoRegistry daoMock;

	@BeforeEach
	void setup() {
		daoMock = mock(DaoRegistry.class);
		AppointmentBookHandlerHelper appointmentBookHandlerHelper = new AppointmentBookHandlerHelper(daoMock);
		handler = new AppointmentBookHandler(appointmentBookHandlerHelper);
		handler.setCtx(FhirContext.forR4());
	}

	@Test
	void testHandleIncomingAppointmentNoAppointmentInParametersThrowsException() {
		Parameters parameters = new Parameters();
		String body = ctx.newJsonParser().encodeResourceToString(parameters);
		RequestDetails requestDetails = mock(RequestDetails.class);

		assertThatThrownBy(() -> handler.handleIncomingAppointment(body, requestDetails))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Could not find an Appointment resource in incoming Parameters");
	}

	@Test
	void testHandleIncomingAppointmentUnsupportedResourceTypeThrowsException() {
		DocumentReference documentReference = new DocumentReference();
		String body = ctx.newJsonParser().encodeResourceToString(documentReference);
		RequestDetails requestDetails = mock(RequestDetails.class);

		assertThatThrownBy(() -> handler.handleIncomingAppointment(body, requestDetails))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported resource type in incoming body: org.hl7.fhir.r4.model.DocumentReference");
	}

	@Test
	void testHandleIncomingAppointmentParametersMissingSlotAndScheduleThrowsException() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/invalid/invalid-booking-parameters-missing-slot-and-schedule.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);

		RequestDetails requestDetails = mock(RequestDetails.class);
		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);

		assertThatThrownBy(() -> handler.handleIncomingAppointment(body, requestDetails))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Slot is missing and could not find a Schedule Reference in incoming Parameters");
	}

	@Test
	void testIfInvalidAppointmentBookOperationLeadsToErrorsInOperationOutcome() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/invalid/invalid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Busy-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isFalse();
		assertThat(outcome.getHttpStatusCode()).isEqualTo(422);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome.getOperationOutcome())).isTrue();

		List<String> expectedMessages = List.of(
				"Start date must be in the future.",
				"Status is 'cancelled' but must be 'proposed'.",
				"Wrong CodeSystem for serviceType. Must be 'http://terminology.hl7.org/CodeSystem/service-type'.",
				"Status is 'busy' but must be 'free'.",
				"Appointment times must be within or equal to the start and end times of the referenced slot (Appointment.slot)."
		);
		boolean hasAllExpectedMessages = expectedMessages.stream()
				.allMatch(expectedMessage -> outcome.getOperationOutcome().getIssue().stream()
						.anyMatch(issue -> issue.getDiagnostics().contains(expectedMessage)));

		assertThat(hasAllExpectedMessages).isTrue();
	}

	@Test
	void testValidAppointmentBookOperation() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
	}

	@Test
	void testUnknownCancelledApptIdLeadsToIssueInOperationOutcome() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/invalid/invalid-appointment-rescheduling-parameters.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(appointmentDaoMock.read(
				argThat(id -> id.getIdPart().equals("Unknown-Appointment-ID")),
				argThat(req -> req.equals(requestDetails))
		)).thenThrow(new ResourceNotFoundException("Schedule not found"));

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isFalse();
		assertThat(outcome.getOperationOutcome().getIssue())
				.extracting("diagnostics")
				.contains("Appointment for cancellation with ID Appointment/Unknown-Appointment-ID not found (cancelled-appt-id)");
	}

	@Test
	void testCancelledApptIdAsUriIsExtractedCorrectly() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-rescheduling-parameters-with-uri.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(appointmentDaoMock.read(
				argThat(id -> id.getIdPart().equals("CANCELLED_APPT_ID")),
				eq(requestDetails)
		)).thenThrow(new ResourceNotFoundException("Not found"));

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isFalse();
		assertThat(outcome.getOperationOutcome().getIssue())
				.extracting("diagnostics")
				.contains("Appointment for cancellation with ID Appointment/CANCELLED_APPT_ID not found (cancelled-appt-id)");
	}

	@Test
	void testPatientParameterCreatesPatientAndAddsParticipant() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-booking-parameters-with-patient.json");
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);

		// First read call (resolveOrCreatePatient) throws ResourceNotFoundException
		// Second read call (checkPlausibility → getPatient) returns the active patient
		Patient activePatient = new Patient();
		activePatient.setId("Patient/InlinePatient");
		activePatient.setActive(true);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenThrow(new ResourceNotFoundException("Not found"))
				.thenReturn(activePatient);

		// After creation, return the patient id
		DaoMethodOutcome createOutcome = mock(DaoMethodOutcome.class);
		when(createOutcome.getId()).thenReturn(new IdType("Patient/InlinePatient"));
		when(patientDaoMock.create(any(Patient.class), eq(requestDetails)))
				.thenReturn(createOutcome);

		when(slotDaoMock.read(any(IdType.class), eq(requestDetails))).thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
		Appointment resultAppointment = outcome.getAppointment();
		assertThat(resultAppointment.getParticipant())
				.anyMatch(p -> p.getActor().getReference().contains("Patient/InlinePatient"));
	}

	@Test
	void testAppointmentWithExistingIdIsUpdated() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		// The fixture has id "Appointment-Book-Example" — simulate it already exists
		Appointment existingAppointment = new Appointment();
		when(appointmentDaoMock.read(
				argThat(id -> id.getIdPart().equals("Appointment-Book-Example")),
				eq(requestDetails)
		)).thenReturn(existingAppointment);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
		assertThat(outcome.isUpdate()).isTrue();
		assertThat(outcome.getAppointment().getIdElement().getIdPart()).isEqualTo("Appointment-Book-Example");
		verify(appointmentDaoMock).update(any(Appointment.class), eq(requestDetails));
	}

	@Test
	void testAppointmentWithNewIdIsCreated() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		// The fixture has id "Appointment-Book-Example" — simulate it does NOT exist
		when(appointmentDaoMock.read(
				argThat(id -> id.getIdPart().equals("Appointment-Book-Example")),
				eq(requestDetails)
		)).thenThrow(new ResourceNotFoundException("Not found"));

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
		assertThat(outcome.isUpdate()).isFalse();
		// id is preserved since it was provided but doesn't exist yet
		assertThat(outcome.getAppointment().getIdElement().getIdPart()).isEqualTo("Appointment-Book-Example");
		verify(appointmentDaoMock).create(any(Appointment.class), eq(requestDetails));
	}

	@Test
	void testAppointmentWithNoPatientParticipantReturnsError() {
		// Build Parameters with an Appointment that has no patient participant
		Appointment appointment = new Appointment();
		appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
		appointment.setStart(new java.util.Date(System.currentTimeMillis() + 86400000L));
		appointment.setEnd(new java.util.Date(System.currentTimeMillis() + 90000000L));
		appointment.addServiceType()
				.addCoding()
				.setSystem("http://terminology.hl7.org/CodeSystem/service-type")
				.setCode("177");
		appointment.addParticipant()
				.setActor(new org.hl7.fhir.r4.model.Reference("Practitioner/doc1"))
				.setStatus(Appointment.ParticipationStatus.ACCEPTED);
		appointment.addSlot(new org.hl7.fhir.r4.model.Reference("Slot/Free-Block"));

		String body = ctx.newJsonParser().encodeResourceToString(appointment);
		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);

		Slot freeSlot = new Slot();
		freeSlot.setStatus(Slot.SlotStatus.FREE);
		freeSlot.setStart(appointment.getStart());
		freeSlot.setEnd(appointment.getEnd());
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails))).thenReturn(freeSlot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isFalse();
		assertThat(outcome.getOperationOutcome().getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains(
						"No participant with a Patient reference found in the Appointment"));
	}

	@Test
	void testRelatedPersonParameterCreatesRelatedPersonAndAddsParticipant() {
		String body = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/valid-booking-parameters-with-related-person.json");
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);
		String patientBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<RelatedPerson> relatedPersonDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(daoMock.getResourceDao(RelatedPerson.class)).thenReturn(relatedPersonDaoMock);

		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails))).thenReturn(slot);

		// RelatedPerson not found, so it will be created
		when(relatedPersonDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenThrow(new ResourceNotFoundException("Not found"));
		DaoMethodOutcome relatedPersonCreateOutcome = mock(DaoMethodOutcome.class);
		when(relatedPersonCreateOutcome.getId()).thenReturn(new IdType("RelatedPerson/InlineRelatedPerson"));
		when(relatedPersonDaoMock.create(any(RelatedPerson.class), eq(requestDetails)))
				.thenReturn(relatedPersonCreateOutcome);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
		Appointment resultAppointment = outcome.getAppointment();
		assertThat(resultAppointment.getParticipant())
				.anyMatch(p -> p.getActor().getReference().contains("RelatedPerson/InlineRelatedPerson"));
	}

	@Test
	void testValidAppointmentReturnsHttpStatusCode201() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		// Simulate that the appointment does NOT exist yet -> create (201)
		when(appointmentDaoMock.read(
				argThat(id -> id.getIdPart().equals("Appointment-Book-Example")),
				eq(requestDetails)
		)).thenThrow(new ResourceNotFoundException("Not found"));

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
		assertThat(outcome.getHttpStatusCode()).isEqualTo(201);
	}

	@Test
	void testSemanticErrorReturnsHttpStatusCode422() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/invalid/invalid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Busy-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isFalse();
		assertThat(outcome.getHttpStatusCode()).isEqualTo(422);
	}

	@Test
	void testPendingEnabledSetsStatusToPending() {
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString("integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		// Simulate that the appointment does NOT exist yet
		when(appointmentDaoMock.read(
				argThat(id -> id.getIdPart().equals("Appointment-Book-Example")),
				eq(requestDetails)
		)).thenThrow(new ResourceNotFoundException("Not found"));

		// Enable pending mode
		handler.setPendingEnabled(true);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful()).isTrue();
		assertThat(outcome.getAppointment().getStatus()).isEqualTo(Appointment.AppointmentStatus.PENDING);
		assertThat(outcome.getHttpStatusCode()).isEqualTo(202);

		// Reset
		handler.setPendingEnabled(false);
	}

	@Test
	void testProposedAppointmentWithNoStartEndAndSlotSucceeds() {
		// ISiK IG: "Nur für die Status 'proposed', 'cancelled', 'waitlist' existiert kein Wert."
		// An Appointment with status=proposed and no start/end should be valid when a Slot is referenced.
		// The server should populate start/end from the referenced Slot.
		String body = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/valid-appointment-no-start-end.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString(
				"integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful())
				.as("Booking should succeed for proposed appointment without start/end when slot is referenced")
				.isTrue();

		Appointment resultAppointment = outcome.getAppointment();
		assertThat(resultAppointment.getStart())
				.as("Start should be populated from the referenced Slot")
				.isEqualTo(slot.getStart());
		assertThat(resultAppointment.getEnd())
				.as("End should be populated from the referenced Slot")
				.isEqualTo(slot.getEnd());
		assertThat(resultAppointment.getStatus())
				.as("Status should be changed to booked")
				.isEqualTo(Appointment.AppointmentStatus.BOOKED);
	}

	@Test
	void testProposedAppointmentWithNoStartEndAndNoSlotWithScheduleReturnsError() {
		// ISiK IG: start/end not required for proposed, but when there's no Slot reference
		// and a Schedule is provided, the server needs start/end to create a Slot.
		String body = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/invalid/invalid-booking-parameters-no-dates-no-slot.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/Patient-Mustermann.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(scheduleDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(new Schedule());

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful())
				.as("Booking should fail when no Slot is referenced and start/end are missing")
				.isFalse();
		assertThat(outcome.getOperationOutcome().getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains(
						"Start and end dates are required when no Slot is referenced and a Schedule is provided."));
	}

	@Test
	void testProfileValidationFailureReturnsErrorBeforeBooking() throws Exception {
		// When the FhirValidationHandler reports profile errors, the $book operation
		// should return early with the validation OperationOutcome and not persist.
		FhirValidationHandler mockValidationHandler = mock(FhirValidationHandler.class);
		handler.setFhirValidationHandler(mockValidationHandler);

		ValidationResult invalidResult = ValidationResult.createInstance(
				ResultSeverityEnum.ERROR, "Profile validation failed: missing required element", "Appointment");
		when(mockValidationHandler.validateResource(any(IBaseResource.class), anyString()))
				.thenReturn(invalidResult);

		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString(
				"integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful())
				.as("Booking should fail when profile validation reports errors")
				.isFalse();
		assertThat(outcome.getOperationOutcome()).isNotNull();
		assertThat(outcome.getHttpStatusCode()).isEqualTo(422);
	}

	@Test
	void testProfileValidationSuccessAllowsBookingToProceed() throws Exception {
		// When the FhirValidationHandler reports no errors, booking should proceed normally.
		FhirValidationHandler mockValidationHandler = mock(FhirValidationHandler.class);
		handler.setFhirValidationHandler(mockValidationHandler);

		ValidationResult validResult = new ValidationResult(List.of());
		when(mockValidationHandler.validateResource(any(IBaseResource.class), anyString()))
				.thenReturn(validResult);

		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString(
				"integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful())
				.as("Booking should succeed when profile validation passes")
				.isTrue();
	}

	@Test
	void testProfileValidationSkippedWhenHandlerNotAvailable() {
		// When FhirValidationHandler is not injected (null), profile validation
		// is skipped and booking proceeds with plausibility checks only.
		// The handler is not set in setup(), so it defaults to null.
		String body = ResourceLoadingHelper.loadResourceAsString("fhir-examples/valid/valid-appointment.json");
		String patientBody = ResourceLoadingHelper.loadResourceAsString(
				"integration-tests/valid/Patient-PatientinMusterfrau.json");
		Patient patient = (Patient) ctx.newJsonParser().parseResource(patientBody);
		String slotBody = ResourceLoadingHelper.loadResourceAsString(
				"fhir-examples/valid/Slot-Free-Block-Example.json");
		Slot slot = (Slot) ctx.newJsonParser().parseResource(slotBody);

		RequestDetails requestDetails = mock(RequestDetails.class);

		IFhirResourceDao<Patient> patientDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Appointment> appointmentDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Slot> slotDaoMock = mock(IFhirResourceDao.class);
		IFhirResourceDao<Schedule> scheduleDaoMock = mock(IFhirResourceDao.class);

		when(daoMock.getResourceDao(Patient.class)).thenReturn(patientDaoMock);
		when(daoMock.getResourceDao(Appointment.class)).thenReturn(appointmentDaoMock);
		when(daoMock.getResourceDao(Slot.class)).thenReturn(slotDaoMock);
		when(daoMock.getResourceDao(Schedule.class)).thenReturn(scheduleDaoMock);
		when(patientDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(patient);
		when(slotDaoMock.read(any(IdType.class), eq(requestDetails)))
				.thenReturn(slot);

		// Should succeed despite no profile validation handler
		AppointmentHandlerReturnObject outcome = handler.handleIncomingAppointment(body, requestDetails);

		assertThat(outcome.isOperationSuccessful())
				.as("Booking should succeed when FhirValidationHandler is not available")
				.isTrue();
	}
}
