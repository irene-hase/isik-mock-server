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
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.api.EncodingEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentReferenceGenerateMetadataOperationTest {

	private static final String ISIK_BERICHT_BUNDLE_PROFILE =
			"https://gematik.de/fhir/isik/StructureDefinition/ISiKBerichtBundle";
	private static final String ISIK_BERICHT_SUBSYSTEME_PROFILE =
			"https://gematik.de/fhir/isik/StructureDefinition/ISiKBerichtSubSysteme";

	private final FhirContext ctx = FhirContext.forR4();
	private final IParser parser = ctx.newJsonParser();

	private DocumentReferenceGenerateMetadataOperation operation;

	@Mock
	private DaoRegistry daoRegistry;

	@Mock
	private IFhirResourceDao<Bundle> bundleDao;

	@Mock
	private IFhirResourceDao<DocumentReference> documentReferenceDao;

	@Mock
	private IsikBerichtBundleToISiKDokumentenMetadatenMapper mapper;

	@Mock
	private HttpServletRequest request;

	@Mock
	private HttpServletResponse response;

	@Mock
	private RequestDetails requestDetails;

	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		operation = new DocumentReferenceGenerateMetadataOperation();
		ReflectionTestUtils.setField(operation, "daoRegistry", daoRegistry);
		ReflectionTestUtils.setField(operation, "ctx", ctx);
		ReflectionTestUtils.setField(operation, "bundleToISiKDokumentenMetadatenMapper", mapper);
	}

	@Test
	@SneakyThrows
	void generateMetadata_returnsNullWhenParsingAlreadyHandledError() {
		try (MockedStatic<DocumentReferenceOperationCommons> commonsMock =
				mockStatic(DocumentReferenceOperationCommons.class)) {
			commonsMock
				.when(() -> DocumentReferenceOperationCommons.parseAndValidate(request, response, ctx))
				.thenReturn(null);

			DocumentReference result = operation.generateMetadata(request, response, requestDetails);

			assertThat(result).isNull();
			verifyNoInteractions(daoRegistry, mapper);
		}
	}

	@Test
	@SneakyThrows
	void generateMetadata_rejectsUnsupportedResourceType() {
		Parameters unsupportedResource = new Parameters();
		DocumentReferenceOperationCommons.ParsedRequest parsedRequest =
				new DocumentReferenceOperationCommons.ParsedRequest(
						unsupportedResource, parser, EncodingEnum.JSON);
			StringWriter responseWriter = new StringWriter();
			when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));

		try (MockedStatic<DocumentReferenceOperationCommons> commonsMock =
				mockStatic(DocumentReferenceOperationCommons.class)) {
			commonsMock
				.when(() -> DocumentReferenceOperationCommons.parseAndValidate(request, response, ctx))
				.thenReturn(parsedRequest);

			DocumentReference result = operation.generateMetadata(request, response, requestDetails);

			assertThat(result).isNull();
			verify(response).reset();
			verify(response).setStatus(400);
			verify(response).setContentType(EncodingEnum.JSON.getResourceContentTypeNonLegacy());
			assertThat(responseWriter.toString()).contains("Unsupported Document Type");
			verifyNoInteractions(daoRegistry, mapper);
		}
	}

	@Test
	@SneakyThrows
	void generateMetadata_rejectsBundleWithoutMatchingCompositionProfile() {
		Bundle bundle = createIsikBundle();
		Composition composition = new Composition();
		composition.getMeta().addProfile("https://example.org/StructureDefinition/OtherCompositionProfile");
		bundle.addEntry().setResource(composition);
		DocumentReferenceOperationCommons.ParsedRequest parsedRequest =
				new DocumentReferenceOperationCommons.ParsedRequest(bundle, parser, EncodingEnum.JSON);
		StringWriter responseWriter = new StringWriter();
		when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));

		try (MockedStatic<DocumentReferenceOperationCommons> commonsMock =
				mockStatic(DocumentReferenceOperationCommons.class)) {
			commonsMock
				.when(() -> DocumentReferenceOperationCommons.parseAndValidate(request, response, ctx))
				.thenReturn(parsedRequest);

			DocumentReference result = operation.generateMetadata(request, response, requestDetails);

			assertThat(result).isNull();
			verify(response).setStatus(400);
			assertThat(responseWriter.toString()).contains("Couldn't find correct Composition in Bundle");
			verifyNoInteractions(daoRegistry, mapper);
		}
	}

	@Test
	@SneakyThrows
	void generateMetadata_rollsBackCreatedBundleWhenMapperFails() {
		Bundle bundle = createIsikBundle();
		Composition composition = createSubSystemComposition();
		bundle.addEntry().setResource(composition);
		DocumentReferenceOperationCommons.ParsedRequest parsedRequest =
				new DocumentReferenceOperationCommons.ParsedRequest(bundle, parser, EncodingEnum.JSON);
		StringWriter responseWriter = new StringWriter();
		when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
		when(daoRegistry.getResourceDao(Bundle.class)).thenReturn(bundleDao);
		DaoMethodOutcome bundleCreateOutcome = new DaoMethodOutcome();
		bundleCreateOutcome.setId(new IdType("Bundle/bundle-1/_history/1"));
		when(bundleDao.create(bundle, requestDetails)).thenReturn(bundleCreateOutcome);
		OperationOutcome mapperOutcome = new OperationOutcome();
		mapperOutcome.addIssue().setDiagnostics("mapper failed");
		when(mapper.mapCompositionToDocumentReference(
				eq(composition),
				eq(EncodingEnum.JSON),
				eq(bundle.getIdentifier()),
				eq("Bundle/bundle-1"),
				eq(requestDetails)))
				.thenReturn(new DocumentReferenceMetadataReturnObject(null, false, mapperOutcome));

		try (MockedStatic<DocumentReferenceOperationCommons> commonsMock =
				mockStatic(DocumentReferenceOperationCommons.class)) {
			commonsMock
				.when(() -> DocumentReferenceOperationCommons.parseAndValidate(request, response, ctx))
				.thenReturn(parsedRequest);

			DocumentReference result = operation.generateMetadata(request, response, requestDetails);

			assertThat(result).isNull();
			verify(bundleDao).create(bundle, requestDetails);
			verify(bundleDao).delete(new IdType("Bundle/bundle-1"), requestDetails);
			verify(daoRegistry, never()).getResourceDao(DocumentReference.class);
			verify(response).setStatus(400);
			assertThat(responseWriter.toString()).contains("mapper failed");
		}
	}

	@Test
	@SneakyThrows
	void generateMetadata_createsBundleAndDocumentReferenceOnSuccess() {
		Bundle bundle = createIsikBundle();
		Composition composition = createSubSystemComposition();
		bundle.addEntry().setResource(composition);
		DocumentReferenceOperationCommons.ParsedRequest parsedRequest =
				new DocumentReferenceOperationCommons.ParsedRequest(bundle, parser, EncodingEnum.JSON);
		StringWriter responseWriter = new StringWriter();
		when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
		when(daoRegistry.getResourceDao(Bundle.class)).thenReturn(bundleDao);
		when(daoRegistry.getResourceDao(DocumentReference.class)).thenReturn(documentReferenceDao);
		DaoMethodOutcome bundleCreateOutcome = new DaoMethodOutcome();
		bundleCreateOutcome.setId(new IdType("Bundle/bundle-1/_history/1"));
		when(bundleDao.create(bundle, requestDetails)).thenReturn(bundleCreateOutcome);
		DocumentReference createdDocumentReference = new DocumentReference();
		createdDocumentReference.setId("DocumentReference/doc-1");
		createdDocumentReference.setDescription("generated metadata");
		when(mapper.mapCompositionToDocumentReference(
				eq(composition),
				eq(EncodingEnum.JSON),
				eq(bundle.getIdentifier()),
				eq("Bundle/bundle-1"),
				eq(requestDetails)))
				.thenReturn(new DocumentReferenceMetadataReturnObject(createdDocumentReference, true, null));
		DaoMethodOutcome docRefCreateOutcome = new DaoMethodOutcome();
		docRefCreateOutcome.setId(new IdType("DocumentReference/doc-1/_history/1"));
		when(documentReferenceDao.create(createdDocumentReference, requestDetails)).thenReturn(docRefCreateOutcome);

		try (MockedStatic<DocumentReferenceOperationCommons> commonsMock =
				mockStatic(DocumentReferenceOperationCommons.class)) {
			commonsMock
				.when(() -> DocumentReferenceOperationCommons.parseAndValidate(request, response, ctx))
				.thenReturn(parsedRequest);

			DocumentReference result = operation.generateMetadata(request, response, requestDetails);

			assertThat(result).isSameAs(createdDocumentReference);
			verify(bundleDao).create(bundle, requestDetails);
			verify(documentReferenceDao).create(createdDocumentReference, requestDetails);
			verify(response).setStatus(HttpServletResponse.SC_CREATED);
			assertThat(responseWriter.toString())
					.isEqualTo(ctx.newJsonParser().encodeResourceToString(createdDocumentReference));
		}
	}

	private Bundle createIsikBundle() {
		Bundle bundle = new Bundle();
		bundle.getMeta().addProfile(ISIK_BERICHT_BUNDLE_PROFILE);
		bundle.setIdentifier(new Identifier().setSystem("urn:test").setValue("bundle-identifier"));
		return bundle;
	}

	private Composition createSubSystemComposition() {
		Composition composition = new Composition();
		composition.getMeta().addProfile(ISIK_BERICHT_SUBSYSTEME_PROFILE);
		return composition;
	}
}

