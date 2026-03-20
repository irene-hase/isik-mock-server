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
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import de.gematik.isik.mockserver.helper.OperationOutcomeUtils;
import de.gematik.isik.mockserver.helper.ValidationResultFilter;
import de.gematik.isik.mockserver.interceptor.FhirValidationHandler;
import de.gematik.refv.commons.validation.ValidationResult;
import de.gematik.refv.commons.validation.ValidationResultToOperationOutcomeConverter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.hl7.fhir.r4.model.Slot;
import org.hl7.fhir.r4.model.UriType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.text.MessageFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@Setter
@RequiredArgsConstructor
public class AppointmentBookHandler {

	@Autowired
	private FhirContext ctx;

	@Value("${isik.appointment.book.pending-enabled:false}")
	private boolean pendingEnabled;

	@Autowired(required = false)
	private FhirValidationHandler fhirValidationHandler;

	@Autowired
	private final AppointmentBookHandlerHelper appointmentBookHandlerHelper;

	private record AppointmentExtractionData(
			Appointment appointment,
			Reference scheduleReference,
			String cancelledApptId,
			Patient patient,
			RelatedPerson relatedPerson) {}

	public AppointmentHandlerReturnObject handleIncomingAppointment(String body, RequestDetails theRequestDetails) {
		var incomingResource = EncodingEnum.detectEncoding(body).newParser(ctx).parseResource(body);
		var extractionData = extractAppointmentData(incomingResource);
		Appointment incomingAppointment = extractionData.appointment();
		Reference scheduleReference = extractionData.scheduleReference();
		String cancelledApptId = extractionData.cancelledApptId();
		Patient incomingPatient = extractionData.patient();
		RelatedPerson incomingRelatedPerson = extractionData.relatedPerson();

		// Resolve or create patient from the 'patient' parameter if provided
		if (incomingPatient != null) {
			String resolvedPatientRef =
					appointmentBookHandlerHelper.resolveOrCreatePatient(incomingPatient, theRequestDetails);
			String existingPatientRef = appointmentBookHandlerHelper.findPatientReference(incomingAppointment);
			if (existingPatientRef == null) {
				incomingAppointment
						.addParticipant()
						.setActor(new Reference(resolvedPatientRef))
						.setStatus(Appointment.ParticipationStatus.ACCEPTED);
			}
		}

		// Resolve or create RelatedPerson from the 'related-person' parameter if provided
		if (incomingRelatedPerson != null) {
			String resolvedRelatedPersonRef =
					appointmentBookHandlerHelper.resolveOrCreateRelatedPerson(incomingRelatedPerson, theRequestDetails);
			String existingRelatedPersonRef =
					appointmentBookHandlerHelper.findRelatedPersonReference(incomingAppointment);
			if (existingRelatedPersonRef == null) {
				incomingAppointment
						.addParticipant()
						.setActor(new Reference(resolvedRelatedPersonRef))
						.setStatus(Appointment.ParticipationStatus.ACCEPTED);
			}
		}

		OperationOutcome outcome = checkPlausibility(incomingAppointment, cancelledApptId, theRequestDetails);

		// Return early if plausibility check found errors to avoid further processing
		// on an invalid appointment (e.g., missing start/end would cause NPE in slot creation)
		if (OperationOutcomeUtils.hasErrorIssue(outcome)) {
			return new AppointmentHandlerReturnObject(null, false, outcome);
		}

		// Populate start/end from the referenced Slot when not set
		// (valid for proposed status per ISiK IG: start/end are not required for
		// proposed/cancelled/waitlist)
		if (appointmentBookHandlerHelper.hasSlot(incomingAppointment)) {
			populateStartAndEndFromSlot(incomingAppointment, theRequestDetails);
		}

		if (!appointmentBookHandlerHelper.hasSlot(incomingAppointment)) {
			log.info("Incoming Appointment: Slot is missing, resolving from Schedule");
			resolveOrCreateSlotFromSchedule(incomingAppointment, scheduleReference, outcome, theRequestDetails);
		}

		if (OperationOutcomeUtils.hasErrorIssue(outcome)) {
			return new AppointmentHandlerReturnObject(null, false, outcome);
		}

		// Determine whether to create or update based on existing id
		boolean isUpdate = false;
		if (incomingAppointment.hasId()
				&& appointmentBookHandlerHelper.isAppointmentExistent(
						incomingAppointment
								.getIdElement()
								.toUnqualifiedVersionless()
								.getValue(),
						theRequestDetails)) {
			isUpdate = true;
			log.info(
					"Appointment with id {} already exists, will update instead of create",
					incomingAppointment.getId());
		} else if (!incomingAppointment.hasId()) {
			incomingAppointment.setId(UUID.randomUUID().toString());
		}

		if (pendingEnabled) {
			incomingAppointment.setStatus(Appointment.AppointmentStatus.PENDING);
		} else {
			incomingAppointment.setStatus(Appointment.AppointmentStatus.BOOKED);
		}

		// Validate the fully-enriched Appointment against the ISiKTermin profile.
		// The FhirValidationInterceptor skips operation calls (/$), so we validate here
		// after all enrichment (start/end population, status change) is complete.
		OperationOutcome profileOutcome = validateAppointmentProfile(incomingAppointment);
		if (profileOutcome != null && OperationOutcomeUtils.hasErrorIssue(profileOutcome)) {
			return new AppointmentHandlerReturnObject(null, false, profileOutcome);
		}

		if (isUpdate) {
			appointmentBookHandlerHelper.updateAppointment(incomingAppointment, cancelledApptId, theRequestDetails);
		} else {
			appointmentBookHandlerHelper.createAppointment(incomingAppointment, cancelledApptId, theRequestDetails);
		}

		if (cancelledApptId != null) {
			appointmentBookHandlerHelper.cancelAppointment(cancelledApptId, theRequestDetails);
		}

		// Mark referenced slots as busy
		if (appointmentBookHandlerHelper.hasSlot(incomingAppointment)) {
			for (Reference slotRef : incomingAppointment.getSlot()) {
				appointmentBookHandlerHelper.updateSlotToBusy(slotRef.getReference(), theRequestDetails);
			}
		}

		int httpStatusCode = pendingEnabled ? 202 : (isUpdate ? 200 : 201);
		return new AppointmentHandlerReturnObject(incomingAppointment, true, null, isUpdate, httpStatusCode);
	}

	@Async
	public CompletableFuture<AppointmentHandlerReturnObject> handleIncomingAppointmentAsync(
			String body, RequestDetails theRequestDetails) {
		// Use SystemRequestDetails for async execution because the original HTTP
		// RequestDetails may reference a completed/closed servlet request by the time
		// the async thread processes this.
		var asyncRequestDetails = new SystemRequestDetails(theRequestDetails);
		AppointmentHandlerReturnObject result = handleIncomingAppointment(body, asyncRequestDetails);
		return CompletableFuture.completedFuture(result);
	}

	private AppointmentExtractionData extractAppointmentData(Object incomingResource) {
		if (incomingResource instanceof Appointment appointment) {
			return new AppointmentExtractionData(appointment, null, null, null, null);
		}
		if (incomingResource instanceof Parameters incomingParameters) {
			Appointment appointment = incomingParameters.getParameter().stream()
					.filter(p -> "appt-resource".equals(p.getName()))
					.findFirst()
					.map(p -> (Appointment) p.getResource())
					.orElseThrow(() -> new IllegalArgumentException(
							"Could not find an Appointment resource in incoming Parameters"));
			Reference scheduleReference = incomingParameters.hasParameter("schedule")
					? (Reference) incomingParameters.getParameter("schedule").getValue()
					: null;
			String cancelledApptId = extractCancelledApptId(incomingParameters);
			Patient patient = incomingParameters.getParameter().stream()
					.filter(p -> "patient".equals(p.getName()))
					.findFirst()
					.map(p -> (Patient) p.getResource())
					.orElse(null);
			RelatedPerson relatedPerson = incomingParameters.getParameter().stream()
					.filter(p -> "related-person".equals(p.getName()))
					.findFirst()
					.map(p -> (RelatedPerson) p.getResource())
					.orElse(null);
			return new AppointmentExtractionData(
					appointment, scheduleReference, cancelledApptId, patient, relatedPerson);
		}
		throw new IllegalArgumentException("Unsupported resource type in incoming body: "
				+ incomingResource.getClass().getName());
	}

	private String extractCancelledApptId(Parameters parameters) {
		if (!parameters.hasParameter("cancelled-appt-id")) {
			return null;
		}
		var paramValue = parameters.getParameter("cancelled-appt-id").getValue();
		if (paramValue instanceof UriType uriType) {
			return uriType.getValue();
		}
		if (paramValue instanceof Reference reference) {
			return reference.getReference();
		}
		return null;
	}

	private OperationOutcome validateAppointmentProfile(Appointment incomingAppointment) {
		if (fhirValidationHandler == null) {
			log.debug("FhirValidationHandler not available, skipping profile validation for Appointment/$book");
			return null;
		}
		try {
			String appointmentJson = ctx.newJsonParser().encodeResourceToString(incomingAppointment);
			ValidationResult validationResult =
					fhirValidationHandler.validateResource(incomingAppointment, appointmentJson);
			ValidationResult filteredResult = ValidationResultFilter.filter(validationResult);

			if (!filteredResult.isValid()) {
				log.info("Incoming Appointment failed ISiK profile validation");
				return new ValidationResultToOperationOutcomeConverter(ctx).toOperationOutcome(filteredResult);
			}
			log.info("Incoming Appointment passed ISiK profile validation");
		} catch (Exception e) {
			log.warn("Profile validation could not be performed, proceeding without: {}", e.getMessage());
		}
		return null;
	}

	private void populateStartAndEndFromSlot(Appointment incomingAppointment, RequestDetails requestDetails) {
		if (incomingAppointment.getStart() != null && incomingAppointment.getEnd() != null) {
			return;
		}
		String slotReference = incomingAppointment.getSlot().getFirst().getReference();
		Slot slot = appointmentBookHandlerHelper.getSlot(slotReference, requestDetails);
		if (incomingAppointment.getStart() == null && slot.getStart() != null) {
			log.info("Populating Appointment.start from referenced Slot: {}", slot.getStart());
			incomingAppointment.setStart(slot.getStart());
		}
		if (incomingAppointment.getEnd() == null && slot.getEnd() != null) {
			log.info("Populating Appointment.end from referenced Slot: {}", slot.getEnd());
			incomingAppointment.setEnd(slot.getEnd());
		}
	}

	/**
	 * Resolves or creates Slots for an Appointment based on the referenced Schedule. Per ISiK IG: "Im
	 * Falle dass ein Appointment keine Referenz auf ein oder mehrere Slots enthält, MUSS der Server
	 * die benötigten Slots auf Basis der Referenz auf Schedule, sowie dem Start- und Endzeitpunkt im
	 * Appointment ermitteln."
	 */
	private void resolveOrCreateSlotFromSchedule(
			Appointment incomingAppointment,
			Reference scheduleReference,
			OperationOutcome outcome,
			RequestDetails requestDetails) {
		if (scheduleReference == null) {
			throw new IllegalArgumentException(
					"Slot is missing and could not find a Schedule Reference in incoming Parameters");
		}

		if (!appointmentBookHandlerHelper.isScheduleExistent(scheduleReference.getReference(), requestDetails)) {
			log.info("Schedule with ID : {} not found", scheduleReference);
			OperationOutcomeUtils.addIssue(
					outcome,
					"Parameters.schedule",
					MessageFormat.format("Schedule with reference: {0} not found", scheduleReference.getReference()));
			return;
		}

		if (incomingAppointment.getStart() == null || incomingAppointment.getEnd() == null) {
			log.info("Incoming Appointment: Start or end date are missing, cannot resolve Slots from Schedule.");
			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.start or Appointment.end",
					"Start and end dates are required when no Slot is referenced and a Schedule is provided.");
			return;
		}

		// First, try to resolve existing free overlapping slots on the schedule
		List<Slot> overlappingFreeSlots = appointmentBookHandlerHelper.findFreeOverlappingSlots(
				incomingAppointment, scheduleReference, requestDetails);

		if (!overlappingFreeSlots.isEmpty()) {
			// Use existing free slots — link them to the Appointment
			for (Slot freeSlot : overlappingFreeSlots) {
				incomingAppointment.addSlot(new Reference(
						freeSlot.getIdElement().toUnqualifiedVersionless().getValue()));
			}
			log.info(
					"Resolved {} existing free slot(s) from Schedule for the Appointment", overlappingFreeSlots.size());
			return;
		}

		// No free slots found — check for busy overlapping slots (conflict)
		List<Slot> overlappingBusySlots = appointmentBookHandlerHelper.findBusyOverlappingSlots(
				incomingAppointment, scheduleReference, requestDetails);
		if (!overlappingBusySlots.isEmpty()) {
			String overlappingSlotsDetails =
					appointmentBookHandlerHelper.getOverlappingSlotDetails(overlappingBusySlots);
			log.info(
					"Incoming Appointment: Start and end are overlapping with existing busy slots. "
							+ "Incoming Appointment Start: {}, Incoming Appointment End: {}, Overlapping Slots: {}",
					incomingAppointment.getStart(),
					incomingAppointment.getEnd(),
					overlappingSlotsDetails);

			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.start or Appointment.end",
					String.format(
							"Incoming Appointment: Start and end are overlapping with existing busy slots. "
									+ "Incoming Appointment Start: %s, Incoming Appointment End: %s, Overlapping Slots: \n%s",
							incomingAppointment.getStart(), incomingAppointment.getEnd(), overlappingSlotsDetails));
			return;
		}

		// No overlapping slots at all — create a new one
		appointmentBookHandlerHelper.createSlot(incomingAppointment, scheduleReference, requestDetails);
	}

	private OperationOutcome checkPlausibility(
			Appointment incomingAppointment, String cancelledApptId, RequestDetails requestDetails) {
		OperationOutcome outcome = new OperationOutcome();

		appointmentBookHandlerHelper.validateStartAndEndPresent(incomingAppointment, outcome);
		appointmentBookHandlerHelper.validateStartInFuture(incomingAppointment, outcome);
		appointmentBookHandlerHelper.validateStatusProposed(incomingAppointment, outcome);
		appointmentBookHandlerHelper.validateServiceType(incomingAppointment, outcome);

		if (appointmentBookHandlerHelper.hasSlot(incomingAppointment)) {
			String slotReference = incomingAppointment.getSlot().getFirst().getReference();
			if (!appointmentBookHandlerHelper.isSlotExistent(slotReference, requestDetails)) {
				log.info("Slot with ID : {} not found", slotReference);
				OperationOutcomeUtils.addIssue(
						outcome,
						"Appointment.slot",
						MessageFormat.format("Slot with ID: {0} not found", slotReference));
			} else {
				Slot slot = appointmentBookHandlerHelper.getSlot(slotReference, requestDetails);
				appointmentBookHandlerHelper.validateStartAndEnd(incomingAppointment, slot, outcome);
				appointmentBookHandlerHelper.validateReferencedSlotFree(slot, outcome);
			}
		}

		String patientReference = appointmentBookHandlerHelper.findPatientReference(incomingAppointment);
		if (patientReference == null) {
			log.info("Incoming Appointment: No patient participant found");
			OperationOutcomeUtils.addIssue(
					outcome,
					"Appointment.participant",
					"No participant with a Patient reference found in the Appointment");
		} else {
			Patient patient = appointmentBookHandlerHelper.getPatient(patientReference, requestDetails);
			appointmentBookHandlerHelper.validateReferencedPatientActive(patient, outcome);
		}

		if (cancelledApptId != null
				&& !appointmentBookHandlerHelper.isCancelledAppointmentExistent(cancelledApptId, requestDetails)) {
			log.info("Appointment for cancellation with ID {} not found (cancelled-appt-id)", cancelledApptId);
			OperationOutcomeUtils.addIssue(
					outcome,
					"Parameters.cancelled-appt-id",
					MessageFormat.format(
							"Appointment for cancellation with ID {0} not found (cancelled-appt-id)", cancelledApptId));
		}

		return outcome;
	}
}
