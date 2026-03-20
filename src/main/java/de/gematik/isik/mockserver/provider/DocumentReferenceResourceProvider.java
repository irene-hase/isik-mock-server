package de.gematik.isik.mockserver.provider;

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

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.DocumentReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Custom JPA resource provider for {@link DocumentReference}.
 *
 * <p>The provider delegates all CRUD operations to the default HAPI JPA implementation. ISiK-
 * specific validation, attachment extraction, and KDL→XDS code mapping are applied by {@link
 * de.gematik.isik.mockserver.interceptor.DocumentReferencePreStorageInterceptor}, which fires for
 * <em>every</em> DocumentReference create — including those submitted inside transaction Bundles.
 */
@Slf4j
@Component
public class DocumentReferenceResourceProvider
		extends ca.uhn.fhir.jpa.provider.BaseJpaResourceProvider<DocumentReference> {

	@Autowired
	private IFhirResourceDao<DocumentReference> documentReferenceDao;

	@PostConstruct
	public void postConstruct() {
		setDao(documentReferenceDao);
	}
}
