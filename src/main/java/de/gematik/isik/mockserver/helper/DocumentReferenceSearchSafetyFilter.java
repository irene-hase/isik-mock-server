package de.gematik.isik.mockserver.helper;

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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

@Component
public class DocumentReferenceSearchSafetyFilter implements Filter {

	private static final String DOCUMENT_REFERENCE_COLLECTION_PATH = "/DocumentReference";
	private static final String ENTERED_IN_ERROR_STATUS = "entered-in-error";

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (!(request instanceof HttpServletRequest httpServletRequest)
				|| !isDocumentReferenceSearchRequest(httpServletRequest)
				|| hasExplicitStatusParameter(httpServletRequest)) {
			chain.doFilter(request, response);
			return;
		}

		chain.doFilter(
				new QueryParameterOverrideRequestWrapper(
						httpServletRequest, Map.of("status:not", new String[] {ENTERED_IN_ERROR_STATUS})),
				response);
	}

	private boolean isDocumentReferenceSearchRequest(HttpServletRequest request) {
		return "GET".equalsIgnoreCase(request.getMethod())
				&& request.getRequestURI().endsWith(DOCUMENT_REFERENCE_COLLECTION_PATH);
	}

	private boolean hasExplicitStatusParameter(HttpServletRequest request) {
		return request.getParameterMap().keySet().stream()
				.anyMatch(parameterName -> "status".equals(parameterName) || parameterName.startsWith("status:"));
	}
}
