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
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.springframework.stereotype.Component;

/**
 * Interceptor that enriches outgoing transaction-response bundles to conform to the
 * ISiKMedikationTransactionResponse profile (ISiK Medikation Stufe 5).
 *
 * <p>Per the specification the server MUST respond to a FHIR transaction bundle with a
 * TransactionResponse bundle whose entries carry the following Must-Support elements:
 *
 * <ul>
 *   <li>{@code Bundle.meta.profile} — the ISiKMedikationTransactionResponse canonical URL
 *   <li>{@code Bundle.entry.fullUrl} — the absolute URL of the created/updated resource
 *   <li>{@code Bundle.entry.response.status} — HTTP status code (populated by HAPI)
 *   <li>{@code Bundle.entry.response.location} — versioned URL (populated by HAPI)
 * </ul>
 *
 * <p>HAPI JPA populates {@code entry.response.status} and {@code entry.response.location}
 * automatically but does <b>not</b> set {@code entry.fullUrl}. This interceptor derives {@code
 * fullUrl} from the location by stripping the {@code /_history/…} version suffix.
 *
 * @see <a
 *     href="https://simplifier.net/guide/isik-medikation-stufe-5/Einfuehrung/Artefakte/Datenobjekt_MedikationTransactionResponse">
 *     ISiKMedikationTransactionResponse</a>
 */
@Slf4j
@Interceptor
@Component
public class TransactionResponseProfileInterceptor {

	private static final String ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE =
			"https://gematik.de/fhir/isik/StructureDefinition/ISiKMedikationTransactionResponse";

	/** Regex that matches the {@code /_history/<versionId>} suffix in a FHIR location URL. */
	static final String HISTORY_SUFFIX_PATTERN = "/_history/[^/]+$";

	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public void outgoingResponse(RequestDetails theRequestDetails, IBaseResource theResource) {
		if (!(theResource instanceof Bundle bundle)) {
			return;
		}

		if (bundle.getType() != Bundle.BundleType.TRANSACTIONRESPONSE) {
			return;
		}

		addProfileIfMissing(bundle);
		populateEntryFullUrls(bundle, theRequestDetails);
	}

	/**
	 * Adds the ISiKMedikationTransactionResponse profile canonical to the bundle's meta unless it is
	 * already present.
	 */
	void addProfileIfMissing(Bundle bundle) {
		boolean alreadyTagged = bundle.getMeta().getProfile().stream()
				.map(CanonicalType::getValue)
				.anyMatch(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE::equals);

		if (!alreadyTagged) {
			bundle.getMeta().addProfile(ISIK_MEDIKATION_TRANSACTION_RESPONSE_PROFILE);
			log.info("Added ISiKMedikationTransactionResponse profile to transaction-response bundle");
		}
	}

	/**
	 * Derives {@code entry.fullUrl} from {@code entry.response.location} for every entry that does
	 * not already carry a fullUrl.
	 *
	 * <p>The location produced by HAPI JPA has the form {@code
	 * <serverBase>/<ResourceType>/<id>/_history/<vid>}. The fullUrl is the canonical URL without the
	 * version suffix, i.e. {@code <serverBase>/<ResourceType>/<id>}.
	 *
	 * <p>If the location is a relative path (no scheme), the server base URL from the request is
	 * prepended so that fullUrl is always absolute as required by the FHIR specification.
	 */
	void populateEntryFullUrls(Bundle bundle, RequestDetails theRequestDetails) {
		String serverBase = theRequestDetails != null ? theRequestDetails.getServerBaseForRequest() : null;

		for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
			if (entry.hasFullUrl()) {
				continue;
			}

			Bundle.BundleEntryResponseComponent response = entry.getResponse();
			if (response == null || response.getLocation() == null) {
				continue;
			}

			String fullUrl = deriveFullUrl(response.getLocation(), serverBase);
			if (fullUrl != null) {
				entry.setFullUrl(fullUrl);
				log.debug("Set entry.fullUrl to {} (derived from location {})", fullUrl, response.getLocation());
			}
		}
	}

	/**
	 * Strips the {@code /_history/<versionId>} suffix from the given location and, if the result is a
	 * relative path, prepends the server base URL.
	 *
	 * @param location the response location (may be absolute or relative)
	 * @param serverBase the server base URL, may be {@code null}
	 * @return an absolute fullUrl, or {@code null} if the location could not be processed
	 */
	static String deriveFullUrl(String location, String serverBase) {
		if (location == null || location.isBlank()) {
			return null;
		}

		String withoutHistory = location.replaceAll(HISTORY_SUFFIX_PATTERN, "");

		// If the URL is already absolute, return it directly
		if (withoutHistory.startsWith("http://") || withoutHistory.startsWith("https://")) {
			return withoutHistory;
		}

		// Make relative URLs absolute by prepending the server base
		if (serverBase != null && !serverBase.isBlank()) {
			String base = serverBase.endsWith("/") ? serverBase : serverBase + "/";
			return base + withoutHistory;
		}

		// Cannot make it absolute — return relative form as fallback
		return withoutHistory;
	}
}
