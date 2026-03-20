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
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import de.gematik.isik.mockserver.helper.OperationOutcomeUtils;
import lombok.SneakyThrows;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Schedule;
import org.hl7.fhir.r4.model.Slot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static de.gematik.isik.mockserver.helper.ResourceLoadingHelper.loadResourceAsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppointmentBookHandlerHelperTest {

	private AppointmentBookHandlerHelper helper;
	private IFhirResourceDao<Slot> slotDao;
	private IFhirResourceDao<Schedule> scheduleDao;
	private IFhirResourceDao<Patient> patientDao;
	private RequestDetails requestDetails;

	@BeforeEach
	public void setup() {
		DaoRegistry daoRegistry = Mockito.mock(DaoRegistry.class);
		slotDao = Mockito.mock(IFhirResourceDao.class);
		scheduleDao = Mockito.mock(IFhirResourceDao.class);
		patientDao = Mockito.mock(IFhirResourceDao.class);
		requestDetails = mock(RequestDetails.class);
		when(daoRegistry.getResourceDao(Slot.class)).thenReturn(slotDao);
		when(daoRegistry.getResourceDao(Schedule.class)).thenReturn(scheduleDao);
		when(daoRegistry.getResourceDao(Patient.class)).thenReturn(patientDao);

		helper = new AppointmentBookHandlerHelper(daoRegistry);
	}

	@Test
	void testIsSlotExistentWhenSlotExists() {
		String slotId = "existing-slot-id";
		Slot slot = new Slot();
		when(slotDao.read(
				argThat(id -> id.getIdPart().equals(slotId)),
				argThat(req -> req.equals(requestDetails))
		)).thenReturn(slot);

		boolean exists = helper.isSlotExistent(slotId, requestDetails);
		assertThat(exists).isTrue();
	}

	@Test
	void testIsSlotExistentWhenSlotDoesNotExist() {
		String slotId = "non-existing-slot-id";
		when(slotDao.read(
				argThat(id -> id.getIdPart().equals(slotId)),
				argThat(req -> req.equals(requestDetails))
		)).thenThrow(new ResourceNotFoundException("Slot not found"));

		boolean exists = helper.isSlotExistent(slotId, requestDetails);
		assertThat(exists).isFalse();
	}

	@Test
	void testIsScheduleExistentWhenScheduleExists() {
		String scheduleId = "existing-schedule-id";
		Schedule schedule = new Schedule();
		when(scheduleDao.read(
				argThat(id -> id.getIdPart().equals(scheduleId)),
				argThat(req -> req.equals(requestDetails))
		)).thenReturn(schedule);

		boolean exists = helper.isScheduleExistent(scheduleId, requestDetails);
		assertThat(exists).isTrue();
	}

	@Test
	void testIsScheduleExistentWhenScheduleDoesNotExist() {
		String scheduleId = "non-existing-schedule-id";
		when(scheduleDao.read(
				argThat(id -> id.getIdPart().equals(scheduleId)),
				argThat(req -> req.equals(requestDetails))
		)).thenThrow(new ResourceNotFoundException("Schedule not found"));

		boolean exists = helper.isScheduleExistent(scheduleId, requestDetails);
		assertThat(exists).isFalse();
	}

	@Test
	void testHasSlotFalse() {
		Appointment appointment = new Appointment();
		appointment.setSlot(null);
		assertThat(helper.hasSlot(appointment)).isFalse();

		appointment.setSlot(List.of());
		assertThat(helper.hasSlot(appointment)).isFalse();
	}

	@Test
	void testHasSlotTrue() {
		Appointment appointment = new Appointment();
		appointment.setSlot(List.of(new org.hl7.fhir.r4.model.Reference("Slot/Slot-1")));
		assertThat(helper.hasSlot(appointment)).isTrue();
	}

	@Test
	void testGetSlot() {
		String slotId = "existing-slot-id";
		Slot slot = new Slot();
		when(slotDao.read(
				argThat(id -> id.getIdPart().equals(slotId)),
				argThat(req -> req.equals(requestDetails))
		)).thenReturn(slot);

		Slot result = helper.getSlot(slotId, requestDetails);
		assertThat(result).isNotNull();
	}

	@Test
	void testGetPatient() {
		String patientId = "existing-patient-id";
		Patient patient = new Patient();
		when(patientDao.read(
				argThat(id -> id.getIdPart().equals(patientId)),
				argThat(req -> req.equals(requestDetails))
		)).thenReturn(patient);

		Patient result = helper.getPatient(patientId, requestDetails);
		assertThat(result).isNotNull();
	}

	@Test
	void testIsServiceTypeInvalid() {
		String json = loadResourceAsString("fhir-examples/invalid/invalid-appointment.json");
		Appointment appointment = FhirContext.forR4().newJsonParser().parseResource(Appointment.class, json);
		OperationOutcome outcome = new OperationOutcome();

		helper.validateServiceType(appointment, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Wrong CodeSystem for serviceType. Must be 'http://terminology.hl7.org/CodeSystem/service-type'."));
	}

	@Test
	void testIsStatusProposedInvalid() {
		String json = loadResourceAsString("fhir-examples/invalid/invalid-appointment.json");
		Appointment appointment = FhirContext.forR4().newJsonParser().parseResource(Appointment.class, json);
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStatusProposed(appointment, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Status is 'cancelled' but must be 'proposed'."));
	}

	@Test
	void testIsStartInFutureInvalid() {
		String json = loadResourceAsString("fhir-examples/invalid/invalid-appointment.json");
		Appointment appointment = FhirContext.forR4().newJsonParser().parseResource(Appointment.class, json);
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStartInFuture(appointment, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Start date must be in the future."));
	}

	@Test
	void testHasStartAndEndInvalid() {
		String json = loadResourceAsString("fhir-examples/invalid/invalid-appointment-end-missing.json");
		Appointment appointment = FhirContext.forR4().newJsonParser().parseResource(Appointment.class, json);
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStartAndEndPresent(appointment, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Start or end date are missing."));
	}

	@Test
	@SneakyThrows
	void testIsStartAndEndValidForSlotInvalid() {
		String json = loadResourceAsString("fhir-examples/invalid/invalid-appointment-with-start-and-end.json");
		Appointment appointment = FhirContext.forR4().newJsonParser().parseResource(Appointment.class, json);
		OperationOutcome outcome = new OperationOutcome();

		Slot slot = new Slot();
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
		Date slotStart = sdf.parse("2022-01-01T14:00:00.000+01:00");
		Date slotEnd = sdf.parse("2027-01-01T13:00:00.000+01:00");
		slot.setStart(slotStart);
		slot.setEnd(slotEnd);

		helper.validateStartAndEnd(appointment, slot, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Appointment times must be within or equal to the start and end times of the referenced slot (Appointment.slot)."));
	}

	@Test
	void testIsStatusFreeInReferencedSlotInvalid() {
		OperationOutcome outcome = new OperationOutcome();
		Slot slot = new Slot();
		slot.setStatus(Slot.SlotStatus.BUSY);

		helper.validateReferencedSlotFree(slot, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Status is 'busy' but must be 'free'."));
	}

	@Test
	void testIsReferencedPatientValidInvalid() {
		OperationOutcome outcome = new OperationOutcome();
		Patient patient = new Patient();
		patient.setActive(false);

		helper.validateReferencedPatientActive(patient, outcome);
		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome)).isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("The referenced Patient has 'active=false' but must be 'active=true'."));
	}

	@Test
	void testFindOverlappingSlots() {
		Appointment appointment = mock(Appointment.class);
		Reference scheduleReference = mock(Reference.class);
		RequestDetails requestDetails = mock(RequestDetails.class);

		Date appointmentStart = new Date(10000L);
		Date appointmentEnd = new Date(20000L);
		when(appointment.getStart()).thenReturn(appointmentStart);
		when(appointment.getEnd()).thenReturn(appointmentEnd);
		when(scheduleReference.getReference()).thenReturn("schedule-1");

		// Overlapping slot: starts before appointment and ends during the appointment.
		Slot overlappingSlot1 = new Slot();
		overlappingSlot1.setStart(new Date(5000L));
		overlappingSlot1.setEnd(new Date(15000L));
		overlappingSlot1.setStatus(Slot.SlotStatus.BUSY);

		// Overlapping slot: starts during the appointment and ends after the appointment.
		Slot overlappingSlot2 = new Slot();
		overlappingSlot2.setStart(new Date(15000L));
		overlappingSlot2.setEnd(new Date(25000L));
		overlappingSlot2.setStatus(Slot.SlotStatus.BUSY);

		// Overlapping slot: ends exactly at the appointment start.
		Slot overlappingSlot3 = new Slot();
		overlappingSlot3.setStart(new Date(0L));
		overlappingSlot3.setEnd(new Date(10000L));
		overlappingSlot3.setStatus(Slot.SlotStatus.BUSY);

		// Overlapping slot: starts exactly at the appointment end.
		Slot overlappingSlot4 = new Slot();
		overlappingSlot4.setStart(new Date(2000L));
		overlappingSlot4.setEnd(new Date(30000L));
		overlappingSlot4.setStatus(Slot.SlotStatus.BUSY);

		// Non-overlapping slot: ends before the appointment start.
		Slot nonOverlappingSlot1 = new Slot();
		nonOverlappingSlot1.setStart(new Date(0L));
		nonOverlappingSlot1.setEnd(new Date(9999L));
		nonOverlappingSlot1.setStatus(Slot.SlotStatus.FREE);

		// Non-overlapping slot: starts after the appointment end.
		Slot nonOverlappingSlot2 = new Slot();
		nonOverlappingSlot2.setStart(new Date(20001L));
		nonOverlappingSlot2.setEnd(new Date(30000L));
		nonOverlappingSlot2.setStatus(Slot.SlotStatus.FREE);

		// Overlapping FREE slot: start and end are between appointment start and end
		Slot overlappingFreeSlot = new Slot();
		overlappingFreeSlot.setStart(new Date(11000));
		overlappingFreeSlot.setEnd(new Date(19000));
		overlappingFreeSlot.setStatus(Slot.SlotStatus.FREE);

		// Simulate the DAO search result.
		List<IBaseResource> slotResources = Arrays.asList(
				overlappingSlot1,
				overlappingSlot2,
				overlappingSlot3,
				overlappingSlot4,
				nonOverlappingSlot1,
				nonOverlappingSlot2,
				overlappingFreeSlot
		);

		IBundleProvider slotBundle = mock(IBundleProvider.class);
		when(slotBundle.getAllResources()).thenReturn(slotResources);

		when(slotDao.search(any(SearchParameterMap.class), eq(requestDetails))).thenReturn(slotBundle);

		List<Slot> busyOverlappingSlots = helper.findBusyOverlappingSlots(appointment, scheduleReference, requestDetails);
		List<Slot> freeOverlappingSlots = helper.findFreeOverlappingSlots(appointment, scheduleReference, requestDetails);

		assertThat(busyOverlappingSlots)
				.as("Only overlapping slots should be returned")
				.containsExactlyInAnyOrder(overlappingSlot1, overlappingSlot2, overlappingSlot3, overlappingSlot4);

		assertThat(freeOverlappingSlots).contains(overlappingFreeSlot);
	}

	@Test
	void testCreateSlot() {
		Appointment appointment = mock(Appointment.class);
		Reference scheduleReference = mock(Reference.class);
		RequestDetails requestDetails = mock(RequestDetails.class);

		Date start = new Date();
		Date end = new Date(start.getTime() + 3600000L);
		when(appointment.getStart()).thenReturn(start);
		when(appointment.getEnd()).thenReturn(end);

		IIdType mockId = new IdType("Slot/123");
		DaoMethodOutcome mockOutcome = mock(DaoMethodOutcome.class);
		when(mockOutcome.getId()).thenReturn(mockId);
		when(slotDao.create(any(Slot.class), eq(requestDetails))).thenReturn(mockOutcome);

		helper.createSlot(appointment, scheduleReference, requestDetails);

		ArgumentCaptor<Slot> slotCaptor = ArgumentCaptor.forClass(Slot.class);
		verify(slotDao).create(slotCaptor.capture(), eq(requestDetails));
		Slot capturedSlot = slotCaptor.getValue();

		assertThat(capturedSlot.getStatus())
				.as("Slot status should be FREE (updateSlotToBusy transitions it later)")
				.isEqualTo(Slot.SlotStatus.FREE);
		assertThat(capturedSlot.getSchedule())
				.as("Schedule reference must be set correctly")
				.isEqualTo(scheduleReference);
		assertThat(capturedSlot.getStart())
				.as("Slot start time should match appointment start")
				.isEqualTo(start);
		assertThat(capturedSlot.getEnd())
				.as("Slot end time should match appointment end")
				.isEqualTo(end);
	}

	@Test
	@SneakyThrows
	void testGetOverlappingSlotDetails() {
		Slot slot1 = new Slot();
		Slot slot2 = new Slot();

		slot1.setId("slot1");
		slot2.setId("slot2");
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		Date start1 = sdf.parse("2026-02-20 10:00:00");
		Date end1 = sdf.parse("2026-02-20 10:30:00");
		Date start2 = sdf.parse("2026-02-20 10:31:00");
		Date end2 = sdf.parse("2026-02-20 11:00:00");
		slot1.setStart(start1);
		slot1.setEnd(end1);
		slot2.setStart(start2);
		slot2.setEnd(end2);

		String overlappingSlotDetails = helper.getOverlappingSlotDetails(List.of(slot1, slot2));

		assertThat(overlappingSlotDetails).isEqualTo(
				"slot1: Start: Fri Feb 20 10:00:00 CET 2026, End: Fri Feb 20 10:30:00 CET 2026\n" +
						"slot2: Start: Fri Feb 20 10:31:00 CET 2026, End: Fri Feb 20 11:00:00 CET 2026");
	}

	@Test
	void testCreateAppointmentCancelledApptId() {
		Appointment appointment = new Appointment();
		RequestDetails requestDetails = mock(RequestDetails.class);
		String cancelledApptId = "Appointment/123";

		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);
		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		helper.createAppointment(appointment, cancelledApptId, requestDetails);

		assertThat(appointment.getExtension())
				.as("Appointment should have at least one extension added")
				.isNotEmpty();
		Extension replacementExtension = appointment.getExtension().stream()
				.filter(ext -> "http://hl7.org/fhir/5.0/StructureDefinition/extension-Appointment.replaces".equals(ext.getUrl()))
				.findFirst()
				.orElse(null);
		assertThat(replacementExtension)
				.as("The replacement extension should be present")
				.isNotNull();
		assertThat(replacementExtension.getValue())
				.as("The extension value should be a Reference")
				.isInstanceOf(Reference.class);
		Reference ref = (Reference) replacementExtension.getValue();
		assertThat(ref.getReference())
				.as("The Reference value should match the cancelled appointment ID")
				.isEqualTo(cancelledApptId);
		verify(appointmentDao).create(appointment, requestDetails);
	}

	@Test
	void testCancelAppointment() {
		String cancelledApptId = "Appointment/123";
		RequestDetails requestDetails = mock(RequestDetails.class);

		Appointment cancelledAppointment = new Appointment();
		cancelledAppointment.setStatus(Appointment.AppointmentStatus.BOOKED);

		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);
		when(appointmentDao.read(argThat(id -> id.getIdPart().equals("123")), eq(requestDetails)))
				.thenReturn(cancelledAppointment);

		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		helper.cancelAppointment(cancelledApptId, requestDetails);

		assertThat(cancelledAppointment.getStatus())
				.as("Status should be updated to CANCELLED")
				.isEqualTo(Appointment.AppointmentStatus.CANCELLED);
		verify(appointmentDao).update(cancelledAppointment, requestDetails);
	}

	@Test
	void testIsCancelledAppointmentExistentTrue() {
		String cancelledApptId = "Appointment/123";
		RequestDetails requestDetails = mock(RequestDetails.class);

		Appointment cancelledAppointment = new Appointment();
		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);
		when(appointmentDao.read(argThat(id -> id.getIdPart().equals("123")), eq(requestDetails)))
				.thenReturn(cancelledAppointment);

		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		boolean existent = helper.isCancelledAppointmentExistent(cancelledApptId, requestDetails);

		assertThat(existent).isTrue();
	}

	@Test
	void testIsCancelledAppointmentExistentFalse() {
		String nonExistentApptId = "non-existent-id";
		RequestDetails requestDetails = mock(RequestDetails.class);
		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);

		when(appointmentDao.read(
				argThat(id -> id.getIdPart().equals(nonExistentApptId)),
				argThat(req -> req.equals(requestDetails))
		)).thenThrow(new ResourceNotFoundException("Schedule not found"));

		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		boolean existent = helper.isCancelledAppointmentExistent(nonExistentApptId, requestDetails);

		assertThat(existent).isFalse();
	}

	@Test
	void testIsAppointmentExistentTrue() {
		String appointmentId = "Appointment/existing-appt";
		RequestDetails requestDetails = mock(RequestDetails.class);

		Appointment appointment = new Appointment();
		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);
		when(appointmentDao.read(argThat(id -> id.getIdPart().equals("existing-appt")), eq(requestDetails)))
				.thenReturn(appointment);

		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		assertThat(helper.isAppointmentExistent(appointmentId, requestDetails)).isTrue();
	}

	@Test
	void testIsAppointmentExistentFalse() {
		String appointmentId = "Appointment/non-existing-appt";
		RequestDetails requestDetails = mock(RequestDetails.class);

		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);
		when(appointmentDao.read(argThat(id -> id.getIdPart().equals("non-existing-appt")), eq(requestDetails)))
				.thenThrow(new ResourceNotFoundException("Not found"));

		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		assertThat(helper.isAppointmentExistent(appointmentId, requestDetails)).isFalse();
	}

	@Test
	void testResolveOrCreatePatientWhenPatientExists() {
		Patient incomingPatient = new Patient();
		incomingPatient.setId("Patient/existing-patient");
		incomingPatient.setActive(true);

		Patient serverPatient = new Patient();
		when(patientDao.read(argThat(id -> id.getIdPart().equals("existing-patient")), eq(requestDetails)))
				.thenReturn(serverPatient);

		String result = helper.resolveOrCreatePatient(incomingPatient, requestDetails);

		assertThat(result).isEqualTo("Patient/existing-patient");
		verify(patientDao, never()).create(any(Patient.class), eq(requestDetails));
	}

	@Test
	void testResolveOrCreatePatientWhenPatientDoesNotExist() {
		Patient incomingPatient = new Patient();
		incomingPatient.setId("Patient/new-patient");
		incomingPatient.setActive(true);

		when(patientDao.read(argThat(id -> id.getIdPart().equals("new-patient")), eq(requestDetails)))
				.thenThrow(new ResourceNotFoundException("Not found"));

		DaoMethodOutcome daoOutcome = mock(DaoMethodOutcome.class);
		when(daoOutcome.getId()).thenReturn(new IdType("Patient/new-patient"));
		when(patientDao.create(any(Patient.class), eq(requestDetails))).thenReturn(daoOutcome);

		String result = helper.resolveOrCreatePatient(incomingPatient, requestDetails);

		assertThat(result).isEqualTo("Patient/new-patient");
		verify(patientDao).create(incomingPatient, requestDetails);
	}

	@Test
	void testResolveOrCreatePatientWithoutId() {
		Patient incomingPatient = new Patient();
		incomingPatient.setActive(true);

		DaoMethodOutcome daoOutcome = mock(DaoMethodOutcome.class);
		when(daoOutcome.getId()).thenReturn(new IdType("Patient/server-assigned-id"));
		when(patientDao.create(any(Patient.class), eq(requestDetails))).thenReturn(daoOutcome);

		String result = helper.resolveOrCreatePatient(incomingPatient, requestDetails);

		assertThat(result).isEqualTo("Patient/server-assigned-id");
		verify(patientDao).create(incomingPatient, requestDetails);
	}

	@Test
	void testFindPatientReferenceFound() {
		Appointment appointment = new Appointment();
		appointment.addParticipant()
				.setActor(new Reference("Practitioner/doc1"))
				.setStatus(Appointment.ParticipationStatus.ACCEPTED);
		appointment.addParticipant()
				.setActor(new Reference("Patient/pat1"))
				.setStatus(Appointment.ParticipationStatus.ACCEPTED);

		String result = helper.findPatientReference(appointment);

		assertThat(result).isEqualTo("Patient/pat1");
	}

	@Test
	void testFindPatientReferenceNotFound() {
		Appointment appointment = new Appointment();
		appointment.addParticipant()
				.setActor(new Reference("Practitioner/doc1"))
				.setStatus(Appointment.ParticipationStatus.ACCEPTED);

		String result = helper.findPatientReference(appointment);

		assertThat(result).isNull();
	}

	@Test
	void testUpdateSlotToBusy() {
		String slotId = "Slot/slot-1";
		Slot slot = new Slot();
		slot.setStatus(Slot.SlotStatus.FREE);

		when(slotDao.read(argThat(id -> id.getIdPart().equals("slot-1")), eq(requestDetails)))
				.thenReturn(slot);

		helper.updateSlotToBusy(slotId, requestDetails);

		assertThat(slot.getStatus()).isEqualTo(Slot.SlotStatus.BUSY);
		verify(slotDao).update(slot, requestDetails);
	}

	@Test
	void testUpdateSlotToBusyAlreadyBusy() {
		String slotId = "Slot/slot-1";
		Slot slot = new Slot();
		slot.setStatus(Slot.SlotStatus.BUSY);

		when(slotDao.read(argThat(id -> id.getIdPart().equals("slot-1")), eq(requestDetails)))
				.thenReturn(slot);

		assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> helper.updateSlotToBusy(slotId, requestDetails)))
				.isInstanceOf(ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException.class)
				.hasMessageContaining("no longer free");
		verify(slotDao, never()).update(any(Slot.class), eq(requestDetails));
	}

	@Test
	void testUpdateAppointmentWithCancelledApptId() {
		Appointment appointment = new Appointment();
		RequestDetails requestDetails = mock(RequestDetails.class);
		String cancelledApptId = "Appointment/456";

		DaoRegistry localDaoRegistry = mock(DaoRegistry.class);
		IFhirResourceDao<Appointment> appointmentDao = mock(IFhirResourceDao.class);
		when(localDaoRegistry.getResourceDao(Appointment.class)).thenReturn(appointmentDao);
		AppointmentBookHandlerHelper helper = new AppointmentBookHandlerHelper(localDaoRegistry);

		helper.updateAppointment(appointment, cancelledApptId, requestDetails);

		assertThat(appointment.getExtension()).isNotEmpty();
		Extension replacementExtension = appointment.getExtension().stream()
				.filter(ext -> "http://hl7.org/fhir/5.0/StructureDefinition/extension-Appointment.replaces".equals(ext.getUrl()))
				.findFirst()
				.orElse(null);
		assertThat(replacementExtension).isNotNull();
		Reference ref = (Reference) replacementExtension.getValue();
		assertThat(ref.getReference()).isEqualTo(cancelledApptId);
		verify(appointmentDao).update(appointment, requestDetails);
	}

	@Test
	void testValidateStartAndEndPresentSkipsForProposedStatus() {
		Appointment appointment = new Appointment();
		appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
		// No start/end set
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStartAndEndPresent(appointment, outcome);

		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome))
				.as("No error expected for proposed status without start/end per ISiK IG")
				.isFalse();
	}

	@Test
	void testValidateStartAndEndPresentSkipsForCancelledStatus() {
		Appointment appointment = new Appointment();
		appointment.setStatus(Appointment.AppointmentStatus.CANCELLED);
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStartAndEndPresent(appointment, outcome);

		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome))
				.as("No error expected for cancelled status without start/end per ISiK IG")
				.isFalse();
	}

	@Test
	void testValidateStartAndEndPresentSkipsForWaitlistStatus() {
		Appointment appointment = new Appointment();
		appointment.setStatus(Appointment.AppointmentStatus.WAITLIST);
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStartAndEndPresent(appointment, outcome);

		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome))
				.as("No error expected for waitlist status without start/end per ISiK IG")
				.isFalse();
	}

	@Test
	void testValidateStartAndEndPresentErrorsForBookedStatus() {
		Appointment appointment = new Appointment();
		appointment.setStatus(Appointment.AppointmentStatus.BOOKED);
		// No start/end set
		OperationOutcome outcome = new OperationOutcome();

		helper.validateStartAndEndPresent(appointment, outcome);

		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome))
				.as("Error expected for booked status without start/end")
				.isTrue();
		assertThat(outcome.getIssue())
				.anyMatch(issue -> issue.getDiagnostics().contains("Start or end date are missing."));
	}

	@Test
	void testValidateStartAndEndWithNullDatesDoesNotThrowNPE() {
		Appointment appointment = new Appointment();
		appointment.setStatus(Appointment.AppointmentStatus.PROPOSED);
		// start and end are null

		Slot slot = new Slot();
		slot.setStart(new Date());
		slot.setEnd(new Date(System.currentTimeMillis() + 3600000L));

		OperationOutcome outcome = new OperationOutcome();

		// This must NOT throw a NullPointerException
		helper.validateStartAndEnd(appointment, slot, outcome);

		assertThat(OperationOutcomeUtils.hasErrorIssue(outcome))
				.as("No error expected when start/end are null (skipped per ISiK IG)")
				.isFalse();
	}

	@Test
	void testFindOverlappingSlotsWithNullDatesReturnsEmptyList() {
		Appointment appointment = mock(Appointment.class);
		Reference scheduleReference = mock(Reference.class);
		RequestDetails requestDetails = mock(RequestDetails.class);

		when(appointment.getStart()).thenReturn(null);
		when(appointment.getEnd()).thenReturn(null);

		List<Slot> result =
				helper.findBusyOverlappingSlots(appointment, scheduleReference, requestDetails);

		assertThat(result)
				.as("Empty list expected when appointment has no start/end")
				.isEmpty();
	}
}
