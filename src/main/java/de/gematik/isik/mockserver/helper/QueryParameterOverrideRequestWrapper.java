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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public class QueryParameterOverrideRequestWrapper extends HttpServletRequestWrapper {

	private final Map<String, String[]> parameters;

	public QueryParameterOverrideRequestWrapper(
			HttpServletRequest request, Map<String, String[]> additionalParameters) {
		super(request);
		parameters = new LinkedHashMap<>(request.getParameterMap());
		additionalParameters.forEach(parameters::putIfAbsent);
	}

	@Override
	public String getParameter(String name) {
		String[] values = getParameterValues(name);
		return values == null || values.length == 0 ? null : values[0];
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		return Collections.unmodifiableMap(parameters);
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return Collections.enumeration(parameters.keySet());
	}

	@Override
	public String[] getParameterValues(String name) {
		return parameters.get(name);
	}

	@Override
	public String getQueryString() {
		StringJoiner joiner = new StringJoiner("&");
		parameters.forEach((name, values) -> {
			if (values == null || values.length == 0) {
				joiner.add(urlEncode(name));
				return;
			}
			for (String value : values) {
				joiner.add(urlEncode(name) + "=" + urlEncode(value));
			}
		});
		return joiner.toString();
	}

	private String urlEncode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
