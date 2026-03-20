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

import de.gematik.isik.mockserver.provider.KdlCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.ConceptMap;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.KDL_TYPE_CODE_SYSTEM;
import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.UNKNOWN_XDS_CLASS_CODE;
import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.UNKNOWN_XDS_CLASS_CODE_DISPLAY;
import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.UNKNOWN_XDS_CLASS_CODE_SYSTEM;
import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.XDS_CLASS_CODE_SYSTEM;
import static de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper.XDS_TYPE_CODE_SYSTEM;

@Component
@Slf4j
@RequiredArgsConstructor
public class IsikBerichtSubSystemeTypeMapper {

	@Autowired
	private final KdlCodeMapper kdlCodeMapper;

	public void mapKdlAndXdsCodings(
			Composition composition, DocumentReference documentReference, OperationOutcome operationOutcome) {

		final var typeCoding = composition.getType().getCoding();

		//	Composition.type.coding[KDL] -> DocumentReference.type.coding:KDL - Composition = OPTIONAL
		// vs. DocumentReference = REQUIRED -> Error when KDL is missing
		Optional<Coding> kdlCodingOptional = typeCoding.stream()
				.filter(coding -> KDL_TYPE_CODE_SYSTEM.equals(coding.getSystem()))
				.findFirst();

		if (kdlCodingOptional.isEmpty()) {
			// KDL coding is required for DocumentReference but missing in the Composition.
			log.info("Missing required KDL coding in Composition.type.coding");
			operationOutcome
					.addIssue()
					.setSeverity(OperationOutcome.IssueSeverity.ERROR)
					.setDiagnostics("Missing required KDL coding in Composition.type.coding");
			return;
		} else {
			documentReference.setType(new CodeableConcept(kdlCodingOptional.get()));
		}

		//	Composition.type.coding[XDS] -> DocumentReference.type.coding:XDS - Composition = OPTIONAL
		// vs. DocumentReference = Optional, but server must set it explicitly.
		// If not found, then use UNK with system "http://terminology.hl7.org/CodeSystem/v3-NullFlavor"
		Optional<Coding> xdsTypeCodingOptional = typeCoding.stream()
				.filter(coding -> XDS_TYPE_CODE_SYSTEM.equals(coding.getSystem()))
				.findFirst();
		if (xdsTypeCodingOptional.isEmpty()) {
			ConceptMap conceptMap = kdlCodeMapper.getTypeCodeConceptMap();
			Coding targetCoding = kdlCodeMapper.findTargetCoding(
					conceptMap, kdlCodingOptional.get().getCode(), KDL_TYPE_CODE_SYSTEM, XDS_TYPE_CODE_SYSTEM);
			if (targetCoding == null) {
				targetCoding = new Coding(
						UNKNOWN_XDS_CLASS_CODE_SYSTEM, UNKNOWN_XDS_CLASS_CODE, UNKNOWN_XDS_CLASS_CODE_DISPLAY);
				log.info(
						"No mapping found for KDL code {}, setting XDS to {} - {}",
						kdlCodingOptional.get().getCode(),
						UNKNOWN_XDS_CLASS_CODE,
						UNKNOWN_XDS_CLASS_CODE_SYSTEM);
			}
			documentReference.getType().addCoding(targetCoding);
		} else {
			documentReference.getType().addCoding(xdsTypeCodingOptional.get());
		}

		//	3. Composition.category.coding[XDS] - > DocumentReference.category.coding:XDS - Composition =
		// OPTIONAL vs. DocumentReference = OPTIONAL, should be extended from server
		Optional<Coding> xdsClassCodingOptional = composition.getCategory().stream()
				.flatMap(category -> category.getCoding().stream())
				.filter(coding -> XDS_CLASS_CODE_SYSTEM.equals(coding.getSystem()))
				.findFirst();
		if (xdsClassCodingOptional.isEmpty()) {
			ConceptMap conceptMap = kdlCodeMapper.getClassCodeConceptMap();
			Coding targetCoding = kdlCodeMapper.findTargetCoding(
					conceptMap, kdlCodingOptional.get().getCode(), KDL_TYPE_CODE_SYSTEM, XDS_CLASS_CODE_SYSTEM);
			if (targetCoding != null) {
				documentReference.setCategory(List.of(new CodeableConcept(targetCoding)));
			} else {
				log.info(
						"No mapping found for KDL code: {} to {}",
						kdlCodingOptional.get().getCode(),
						XDS_CLASS_CODE_SYSTEM);
			}
		} else {
			documentReference.setCategory(List.of(new CodeableConcept(xdsClassCodingOptional.get())));
		}
	}
}
