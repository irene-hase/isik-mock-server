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

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import de.gematik.isik.mockserver.helper.OperationOutcomeUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Schedule;
import org.hl7.fhir.r4.model.Slot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppointmentBookHandlerHelper {

	@Autowired
	private final DaoRegistry daoRegistry;

	public boolean isScheduleExistent(String scheduleId, RequestDetails requestDetails) {
		try {
			daoRegistry.getResourceDao(Schedule.class).read(new IdType(scheduleId), requestDetails);
			return true;
		} catch (ResourceNotFoundException e) {
			return false;
		}
	}

	public boolean isSlotExistent(String slotId, RequestDetails requestDetails) {
		try {
			daoRegistry.getResourceDao(Slot.class).read(new IdType(slotId), requestDetails);
			return true;
		} catch (ResourceNotFoundException e) {
			return false;
		}
	}

	public boolean hasSlot(Appointment incomingAppointment) {
		return incomingAppointment.getSlot() != null
				&& !incomingAppointment.getSlot().isEmpty();
	}

	public Patient getPatient(String patientId, RequestDetails requestDetails) {
		return daoRegistry.getResourceDao(Patient.class).read(new IdType(patientId), requestDetails);
	}

	public Slot getSlot(String slotId, RequestDetails requestDetails) {
		return daoRegistry.getResourceDao(Slot.class).read(new IdType(slotId), requestDetails);
	}

	public void validateServiceType(Appointment incomingAppointment, OperationOutcome outcome) {
		boolean isValid = incomingAppointment.getServiceType().stream()
				.anyMatch(code -> "http://terminology.hl7.org/CodeSystem/service-type"
						.equals(code.getCodingFirstRep().getSystem()));

		if (!isValid) {
			log.info(
					"Incoming Appointment: Wrong CodeSystem for serviceType. Must be 'http://terminology.hl7.org/CodeSystem/service-type'.");
			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.serviceType",
					"Wrong CodeSystem for serviceType. Must be 'http://terminology.hl7.org/CodeSystem/service-type'.");
		}
	}

	public void validateStatusProposed(Appointment incomingAppointment, OperationOutcome outcome) {
		String status = incomingAppointment.getStatus().toCode();
		if (!status.equals("proposed")) {
			log.info("Incoming Appointment: Status is '{}' but must be 'proposed'.", status);
			OperationOutcomeUtils.addIssue(
					outcome, "Appointment.status", String.format("Status is '%s' but must be 'proposed'.", status));
		}
	}

	public void validateStartInFuture(Appointment incomingAppointment, OperationOutcome outcome) {
		if (incomingAppointment.getStart() == null) {
			return; // Not applicable when start is not set (e.g., status=proposed per ISiK IG)
		}
		if (incomingAppointment.getStart().before(new Date())) {
			log.info("Incoming Appointment: Start date is not in the future.");
			OperationOutcomeUtils.addIssue(outcome, "Appointment.start", "Start date must be in the future.");
		}
	}

	/**
	 * Statuses for which start and end are not required per ISiK IG: "Das Feld ist in den meisten
	 * Fällen verpflichtend, nur für die Status 'proposed', 'cancelled', 'waitlist' existiert kein
	 * Wert."
	 */
	private static final Set<String> STATUSES_WITHOUT_REQUIRED_START_END = Set.of("proposed", "cancelled", "waitlist");

	public void validateStartAndEndPresent(Appointment incomingAppointment, OperationOutcome outcome) {
		if (incomingAppointment.getStatus() != null
				&& STATUSES_WITHOUT_REQUIRED_START_END.contains(
						incomingAppointment.getStatus().toCode())) {
			log.debug(
					"Skipping start/end presence validation: status '{}' does not require start/end per ISiK IG",
					incomingAppointment.getStatus().toCode());
			return;
		}
		if (incomingAppointment.getStart() == null || incomingAppointment.getEnd() == null) {
			log.info("Incoming Appointment: Start or end date are missing.");
			OperationOutcomeUtils.addIssue(
					outcome, "Appointment.start or Appointment.end", "Start or end date are missing.");
		}
	}

	public void validateReferencedPatientActive(Patient patient, OperationOutcome outcome) {
		if (!patient.getActive()) {
			log.info("The referenced Patient has 'active=false' but must be 'active=true'.");
			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.patient.active",
					"The referenced Patient has 'active=false' but must be 'active=true'.");
		}
	}

	public void validateStartAndEnd(Appointment incomingAppointment, Slot slot, OperationOutcome outcome) {
		Date appointmentStart = incomingAppointment.getStart();
		Date appointmentEnd = incomingAppointment.getEnd();
		if (appointmentStart == null || appointmentEnd == null) {
			return; // Not applicable when start/end are not set (e.g., status=proposed per ISiK IG)
		}
		Date slotStart = slot.getStart();
		Date slotEnd = slot.getEnd();

		boolean isStartValid = !appointmentStart.before(slotStart); // appointmentStart is after or equal to slotStart
		boolean isEndValid = !appointmentEnd.after(slotEnd); // appointmentEnd is before or equal to slotEnd

		if (!isStartValid || !isEndValid) {
			log.info(
					"Appointment times must be within or equal to the start and end times of the referenced slot (Appointment.slot). "
							+ "Appointment Start: {}, Appointment End: {}, Slot Start: {}, Slot End: {}",
					appointmentStart,
					appointmentEnd,
					slotStart,
					slotEnd);

			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.start or Appointment.end",
					String.format(
							"Appointment times must be within or equal to the start and end times of the referenced slot (Appointment.slot). "
									+ "Appointment Start: %s, Appointment End: %s, Slot Start: %s, Slot End: %s",
							appointmentStart, appointmentEnd, slotStart, slotEnd));
		}
	}

	public void validateReferencedSlotFree(Slot slot, OperationOutcome outcome) {
		String slotStatus = slot.getStatus().toCode();
		if (!slotStatus.equals("free")) {
			log.info("Incoming Appointment: Status of the referenced Slot is '{}' but must be 'free'.", slotStatus);
			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.slot.status",
					String.format("Status is '%s' but must be 'free'.", slotStatus));
		}
	}

	public void createSlot(
			Appointment incomingAppointment, Reference scheduleReference, RequestDetails requestDetails) {
		Slot newSlot = new Slot();
		newSlot.setStatus(Slot.SlotStatus.FREE);
		newSlot.setSchedule(scheduleReference);
		newSlot.setStart(incomingAppointment.getStart());
		newSlot.setEnd(incomingAppointment.getEnd());
		var result = daoRegistry.getResourceDao(Slot.class).create(newSlot, requestDetails);
		log.info("Slot successfully created. ID: {}", result.getId());
		incomingAppointment.addSlot(new Reference(result.getId()));
	}

	public List<Slot> findOverlappingSlots(
			Appointment incomingAppointment,
			Reference scheduleReference,
			RequestDetails requestDetails,
			Slot.SlotStatus desiredStatus) {
		List<Slot> overlappingSlots = new ArrayList<>();
		Date appointmentStart = incomingAppointment.getStart();
		Date appointmentEnd = incomingAppointment.getEnd();

		if (appointmentStart == null || appointmentEnd == null) {
			return overlappingSlots; // Cannot determine overlap without appointment times
		}

		SearchParameterMap paramMap = new SearchParameterMap();
		paramMap.add("schedule", new ReferenceParam(scheduleReference.getReference()));
		// Only consider slots starting before the appointment ends
		paramMap.add("start", new DateParam(ParamPrefixEnum.LESSTHAN, appointmentEnd));

		IBundleProvider slotBundle = daoRegistry.getResourceDao(Slot.class).search(paramMap, requestDetails);
		List<IBaseResource> slotResources = slotBundle.getAllResources();

		for (IBaseResource resource : slotResources) {
			Slot slot = (Slot) resource;
			Date slotStart = slot.getStart();
			Date slotEnd = slot.getEnd();

			// Check for overlap: slots overlap if they are not completely before or after the appointment
			// and match the desired status
			if (!(slotEnd.before(appointmentStart) || slotStart.after(appointmentEnd))
					&& slot.getStatus().equals(desiredStatus)) {
				overlappingSlots.add(slot);
			}
		}

		return overlappingSlots;
	}

	public List<Slot> findBusyOverlappingSlots(
			Appointment incomingAppointment, Reference scheduleReference, RequestDetails requestDetails) {
		return findOverlappingSlots(incomingAppointment, scheduleReference, requestDetails, Slot.SlotStatus.BUSY);
	}

	public List<Slot> findFreeOverlappingSlots(
			Appointment incomingAppointment, Reference scheduleReference, RequestDetails requestDetails) {
		return findOverlappingSlots(incomingAppointment, scheduleReference, requestDetails, Slot.SlotStatus.FREE);
	}

	public String getOverlappingSlotDetails(List<Slot> overlappingSlots) {
		return overlappingSlots.stream()
				.sorted(Comparator.comparing(Slot::getStart))
				.map(slot -> String.format("%s: Start: %s, End: %s", slot.getId(), slot.getStart(), slot.getEnd()))
				.collect(Collectors.joining("\n"));
	}

	public void createAppointment(
			Appointment incomingAppointment, String cancelledApptId, RequestDetails theRequestDetails) {
		if (cancelledApptId != null) {
			Extension apptReplacesExtension = new Extension(
					"http://hl7.org/fhir/5.0/StructureDefinition/extension-Appointment.replaces",
					new Reference(cancelledApptId));
			incomingAppointment.addExtension(apptReplacesExtension);
		}
		daoRegistry.getResourceDao(Appointment.class).create(incomingAppointment, theRequestDetails);
	}

	public void updateAppointment(
			Appointment incomingAppointment, String cancelledApptId, RequestDetails theRequestDetails) {
		if (cancelledApptId != null) {
			Extension apptReplacesExtension = new Extension(
					"http://hl7.org/fhir/5.0/StructureDefinition/extension-Appointment.replaces",
					new Reference(cancelledApptId));
			incomingAppointment.addExtension(apptReplacesExtension);
		}
		daoRegistry.getResourceDao(Appointment.class).update(incomingAppointment, theRequestDetails);
	}

	public void cancelAppointment(String cancelledApptId, RequestDetails requestDetails) {
		Appointment cancelledAppointment =
				daoRegistry.getResourceDao(Appointment.class).read(new IdType(cancelledApptId), requestDetails);
		cancelledAppointment.setStatus(Appointment.AppointmentStatus.CANCELLED);
		daoRegistry.getResourceDao(Appointment.class).update(cancelledAppointment, requestDetails);
	}

	public boolean isCancelledAppointmentExistent(String cancelledApptId, RequestDetails requestDetails) {
		try {
			daoRegistry.getResourceDao(Appointment.class).read(new IdType(cancelledApptId), requestDetails);
			return true;
		} catch (ResourceNotFoundException e) {
			return false;
		}
	}

	public boolean isAppointmentExistent(String appointmentId, RequestDetails requestDetails) {
		try {
			daoRegistry.getResourceDao(Appointment.class).read(new IdType(appointmentId), requestDetails);
			return true;
		} catch (ResourceNotFoundException e) {
			return false;
		}
	}

	public String resolveOrCreatePatient(Patient incomingPatient, RequestDetails requestDetails) {
		if (incomingPatient.hasId()) {
			String patientId =
					incomingPatient.getIdElement().toUnqualifiedVersionless().getValue();
			try {
				daoRegistry.getResourceDao(Patient.class).read(new IdType(patientId), requestDetails);
				log.info("Patient already exists on server: {}", patientId);
				return patientId;
			} catch (ResourceNotFoundException e) {
				log.info("Patient not found on server, creating: {}", patientId);
			}
		}
		DaoMethodOutcome outcome = daoRegistry.getResourceDao(Patient.class).create(incomingPatient, requestDetails);
		String createdId = outcome.getId().toUnqualifiedVersionless().getValue();
		log.info("Patient created on server: {}", createdId);
		return createdId;
	}

	public String findPatientReference(Appointment appointment) {
		return appointment.getParticipant().stream()
				.filter(p -> p.hasActor() && p.getActor().hasReference())
				.map(p -> p.getActor().getReference())
				.filter(ref -> ref.startsWith("Patient/"))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Atomically transitions a Slot from FREE to BUSY. Re-reads the current state before updating so
	 * that a concurrent {@code $book} request that already claimed the slot causes a conflict rather
	 * than a silent double-booking.
	 *
	 * @throws ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException if the slot is no
	 *     longer free at the time of update
	 */
	public void updateSlotToBusy(String slotReference, RequestDetails requestDetails) {
		Slot slot = daoRegistry.getResourceDao(Slot.class).read(new IdType(slotReference), requestDetails);
		if (slot.getStatus() != Slot.SlotStatus.FREE) {
			throw new ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException(String.format(
					"Slot %s is no longer free (current status: %s). It may have been booked by a concurrent request.",
					slotReference, slot.getStatus().toCode()));
		}
		slot.setStatus(Slot.SlotStatus.BUSY);
		daoRegistry.getResourceDao(Slot.class).update(slot, requestDetails);
		log.info("Slot {} status updated to BUSY", slotReference);
	}

	public String resolveOrCreateRelatedPerson(RelatedPerson incomingRelatedPerson, RequestDetails requestDetails) {
		if (incomingRelatedPerson.hasId()) {
			String relatedPersonId = incomingRelatedPerson
					.getIdElement()
					.toUnqualifiedVersionless()
					.getValue();
			try {
				daoRegistry.getResourceDao(RelatedPerson.class).read(new IdType(relatedPersonId), requestDetails);
				log.info("RelatedPerson already exists on server: {}", relatedPersonId);
				return relatedPersonId;
			} catch (ResourceNotFoundException e) {
				log.info("RelatedPerson not found on server, creating: {}", relatedPersonId);
			}
		}
		DaoMethodOutcome outcome =
				daoRegistry.getResourceDao(RelatedPerson.class).create(incomingRelatedPerson, requestDetails);
		String createdId = outcome.getId().toUnqualifiedVersionless().getValue();
		log.info("RelatedPerson created on server: {}", createdId);
		return createdId;
	}

	public String findRelatedPersonReference(Appointment appointment) {
		return appointment.getParticipant().stream()
				.filter(p -> p.hasActor() && p.getActor().hasReference())
				.map(p -> p.getActor().getReference())
				.filter(ref -> ref.startsWith("RelatedPerson/"))
				.findFirst()
				.orElse(null);
	}
}
