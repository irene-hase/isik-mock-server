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

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import de.gematik.isik.mockserver.helper.OperationOutcomeUtils;
import de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.stereotype.Component;

import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.XDS_TYPE_CODE_SYSTEM;

/**
 * Pre-storage interceptor that validates and enriches every {@link DocumentReference} before it is
 * persisted, regardless of whether it arrives via a direct {@code POST /DocumentReference} or
 * inside a transaction {@code Bundle}.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Validate that the referenced Patient and Encounter exist on the server.
 *   <li>Extract embedded base64 attachment data into a separate Binary resource.
 *   <li>Ensure a KDL type code is present and map it to XDS type / class codes.
 *   <li>Process {@code relatesTo} entries with code {@code replaces} by setting the original
 *       document's status to {@code superseded}.
 * </ul>
 */
@Slf4j
@Interceptor
@Component
@RequiredArgsConstructor
public class DocumentReferencePreStorageInterceptor {

	private final DocumentReferenceResourceProviderHelper helper;
	private final DocumentReferencePOSTHelper relatesToHelper;

	/**
	 * Hook fired just before a new resource is stored. Only processes {@link DocumentReference}
	 * resources; all other resource types are ignored.
	 */
	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
	public void handlePreCreate(IBaseResource theResource, RequestDetails theRequestDetails) {
		if (!(theResource instanceof DocumentReference documentReference)) {
			return;
		}

		if (documentReference.getContent().isEmpty()) {
			// Let HAPI's built-in validation handle missing required elements.
			return;
		}

		OperationOutcome outcome = new OperationOutcome();

		// --- Reference validation ---
		if (documentReference.hasSubject() && documentReference.getSubject().hasReference()) {
			helper.validatePatient(documentReference, outcome, theRequestDetails);
		}
		if (documentReference.hasContext()
				&& documentReference.getContext().hasEncounter()
				&& !documentReference.getContext().getEncounter().isEmpty()) {
			helper.validateEncounter(documentReference, outcome, theRequestDetails);
		}

		// --- Attachment processing: extract data → Binary, replace with URL ---
		Attachment attachment = documentReference.getContent().getFirst().getAttachment();
		byte[] base64data = attachment.getData();
		if (base64data != null && base64data.length > 0) {
			String contentType = attachment.getContentType();
			attachment.setUrl(helper.createBinaryResourceAndGetUrl(base64data, contentType, theRequestDetails));
			attachment.setData(null);
		} else if (!attachment.hasUrl()) {
			// No embedded data and no external URL — the attachment is incomplete.
			helper.validateBase64Data(base64data, outcome);
		}

		// --- KDL / XDS code mapping ---
		if (documentReference.hasType()
				&& !documentReference.getType().getCoding().isEmpty()) {
			var kdlTypeCode = helper.getKDLTypeCode(documentReference, outcome);

			if (kdlTypeCode.hasCode()) {
				if (documentReference.getType().getCoding().stream()
						.noneMatch(c -> XDS_TYPE_CODE_SYSTEM.equals(c.getSystem()))) {
					helper.mapKdlCodeToXdsType(
							kdlTypeCode, documentReference.getType().getCoding(), outcome);
				}

				if (documentReference.getCategory().isEmpty()) {
					helper.mapKdlCodeToXdsClass(kdlTypeCode, documentReference.getCategory(), outcome);
				}
			}
		}

		// --- Abort if validation errors accumulated ---
		if (OperationOutcomeUtils.hasErrorIssue(outcome)) {
			throw new UnprocessableEntityException("Invalid DocumentReference", outcome);
		}

		// --- relatesTo / replaces handling ---
		if (documentReference.hasRelatesTo()) {
			relatesToHelper.processRelatesTo(documentReference, theRequestDetails);
		}
	}
}
