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
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import de.gematik.isik.mockserver.provider.DocumentReferenceResourceProviderHelper;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentReferencePreStorageInterceptorTest {

    @Mock private DocumentReferenceResourceProviderHelper helper;

    @Mock private DocumentReferencePOSTHelper relatesToHelper;

    @Mock private RequestDetails requestDetails;

    private DocumentReferencePreStorageInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new DocumentReferencePreStorageInterceptor(helper, relatesToHelper);
    }

    @Test
    void handlePreCreate_ignoresNonDocumentReferenceResources() {
        Patient patient = new Patient();
        interceptor.handlePreCreate(patient, requestDetails);
        // No exception and no interaction with helper
        verify(helper, never()).validatePatient(any(), any(), any());
    }

    @Test
    void handlePreCreate_ignoresDocumentReferenceWithEmptyContent() {
        DocumentReference docRef = new DocumentReference();
        interceptor.handlePreCreate(docRef, requestDetails);
        verify(helper, never()).validateBase64Data(any(), any());
    }

    @Test
    void handlePreCreate_extractsAttachmentDataIntoBinaryResource() {
        DocumentReference docRef = buildMinimalDocumentReference();
        byte[] data = new byte[] {1, 2, 3};
        docRef.getContent().get(0).getAttachment().setData(data);
        docRef.getContent().get(0).getAttachment().setContentType("application/pdf");

        Coding kdlCode = new Coding().setSystem("http://dvmd.de/fhir/CodeSystem/kdl").setCode("PT130102");
        when(helper.getKDLTypeCode(any(), any())).thenReturn(kdlCode);
        when(helper.createBinaryResourceAndGetUrl(eq(data), eq("application/pdf"), eq(requestDetails)))
                .thenReturn("/Binary/42");

        interceptor.handlePreCreate(docRef, requestDetails);

        Attachment attachment = docRef.getContent().get(0).getAttachment();
        assertThat(attachment.getUrl()).isEqualTo("/Binary/42");
        assertThat(attachment.getData()).isNull();
        verify(helper, never()).validateBase64Data(any(), any(OperationOutcome.class));
    }

    @Test
    void handlePreCreate_skipsAttachmentExtractionWhenNoData() {
        DocumentReference docRef = buildMinimalDocumentReference();
        docRef.getContent().get(0).getAttachment().setData(null);

        Coding kdlCode = new Coding().setSystem("http://dvmd.de/fhir/CodeSystem/kdl").setCode("PT130102");
        when(helper.getKDLTypeCode(any(), any())).thenReturn(kdlCode);

        // Simulate the real helper behaviour: null data causes an error issue
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            OperationOutcome oo = invocation.getArgument(1);
                            oo.addIssue()
                                    .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                                    .setDiagnostics("No base64 data in the attachment element found");
                            return null;
                        })
                .when(helper)
                .validateBase64Data(any(), any(OperationOutcome.class));

        assertThatThrownBy(() -> interceptor.handlePreCreate(docRef, requestDetails))
                .isInstanceOf(UnprocessableEntityException.class);

        verify(helper).validateBase64Data(any(), any(OperationOutcome.class));
        verify(helper, never()).createBinaryResourceAndGetUrl(any(), any(), any());
    }

    @Test
    void handlePreCreate_validatesPatientWhenSubjectPresent() {
        DocumentReference docRef = buildMinimalDocumentReference();
        docRef.setSubject(new Reference("Patient/123"));

        Coding kdlCode = new Coding().setSystem("http://dvmd.de/fhir/CodeSystem/kdl").setCode("PT130102");
        when(helper.getKDLTypeCode(any(), any())).thenReturn(kdlCode);

        interceptor.handlePreCreate(docRef, requestDetails);

        verify(helper).validatePatient(eq(docRef), any(OperationOutcome.class), eq(requestDetails));
    }

    @Test
    void handlePreCreate_validatesEncounterWhenContextPresent() {
        DocumentReference docRef = buildMinimalDocumentReference();
        DocumentReference.DocumentReferenceContextComponent context =
                new DocumentReference.DocumentReferenceContextComponent();
        context.addEncounter(new Reference("Encounter/456"));
        docRef.setContext(context);

        Coding kdlCode = new Coding().setSystem("http://dvmd.de/fhir/CodeSystem/kdl").setCode("PT130102");
        when(helper.getKDLTypeCode(any(), any())).thenReturn(kdlCode);

        interceptor.handlePreCreate(docRef, requestDetails);

        verify(helper).validateEncounter(eq(docRef), any(OperationOutcome.class), eq(requestDetails));
    }

    @Test
    void handlePreCreate_processesRelatesToWhenPresent() {
        DocumentReference docRef = buildMinimalDocumentReference();
        DocumentReference.DocumentReferenceRelatesToComponent relatesTo =
                new DocumentReference.DocumentReferenceRelatesToComponent();
        relatesTo.setCode(DocumentReference.DocumentRelationshipType.REPLACES);
        relatesTo.setTarget(new Reference("DocumentReference/999"));
        docRef.addRelatesTo(relatesTo);

        Coding kdlCode = new Coding().setSystem("http://dvmd.de/fhir/CodeSystem/kdl").setCode("PT130102");
        when(helper.getKDLTypeCode(any(), any())).thenReturn(kdlCode);

        interceptor.handlePreCreate(docRef, requestDetails);

        verify(relatesToHelper).processRelatesTo(eq(docRef), eq(requestDetails));
    }

    @Test
    void handlePreCreate_throwsUnprocessableEntityWhenValidationFails() {
        DocumentReference docRef = buildMinimalDocumentReference();
        byte[] data = new byte[] {1, 2, 3};
        docRef.getContent().get(0).getAttachment().setData(data);
        docRef.getContent().get(0).getAttachment().setContentType("application/pdf");

        // Simulate KDL type code lookup adding an error issue to the outcome
        when(helper.createBinaryResourceAndGetUrl(any(), any(), any())).thenReturn("/Binary/42");
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            OperationOutcome oo = invocation.getArgument(1);
                            oo.addIssue()
                                    .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                                    .setDiagnostics("test error");
                            return new Coding();
                        })
                .when(helper)
                .getKDLTypeCode(any(), any(OperationOutcome.class));

        assertThatThrownBy(() -> interceptor.handlePreCreate(docRef, requestDetails))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void handlePreCreate_skipsValidationWhenAttachmentHasUrlButNoData() {
        DocumentReference docRef = buildMinimalDocumentReference();
        docRef.getContent().get(0).getAttachment().setData(null);
        docRef.getContent().get(0).getAttachment().setUrl("/Bundle/123");

        Coding kdlCode = new Coding().setSystem("http://dvmd.de/fhir/CodeSystem/kdl").setCode("PT130102");
        when(helper.getKDLTypeCode(any(), any())).thenReturn(kdlCode);

        interceptor.handlePreCreate(docRef, requestDetails);

        verify(helper, never()).validateBase64Data(any(), any(OperationOutcome.class));
        verify(helper, never()).createBinaryResourceAndGetUrl(any(), any(), any());
    }

    private DocumentReference buildMinimalDocumentReference() {
        DocumentReference docRef = new DocumentReference();
        DocumentReference.DocumentReferenceContentComponent content =
                new DocumentReference.DocumentReferenceContentComponent();
        Attachment attachment = new Attachment();
        attachment.setContentType("application/pdf");
        content.setAttachment(attachment);
        docRef.addContent(content);
        docRef.getType()
                .addCoding()
                .setSystem("http://dvmd.de/fhir/CodeSystem/kdl")
                .setCode("PT130102");
        return docRef;
    }
}

