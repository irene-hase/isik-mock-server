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

import lombok.AllArgsConstructor;
import lombok.Data;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.OperationOutcome;

@Data
@AllArgsConstructor
public class AppointmentHandlerReturnObject {
	private Appointment appointment;
	private boolean operationSuccessful;
	private OperationOutcome operationOutcome;
	private boolean update;
	private int httpStatusCode;

	public AppointmentHandlerReturnObject(
			Appointment appointment, boolean operationSuccessful, OperationOutcome operationOutcome) {
		this(appointment, operationSuccessful, operationOutcome, false, operationSuccessful ? 201 : 422);
	}

	public AppointmentHandlerReturnObject(
			Appointment appointment, boolean operationSuccessful, OperationOutcome operationOutcome, boolean update) {
		this(
				appointment,
				operationSuccessful,
				operationOutcome,
				update,
				operationSuccessful ? (update ? 200 : 201) : 422);
	}
}
