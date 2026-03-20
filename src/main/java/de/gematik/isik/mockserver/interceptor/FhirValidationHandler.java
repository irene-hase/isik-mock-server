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

import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import de.gematik.isik.mockserver.refv.PluginLoader;
import de.gematik.isik.mockserver.refv.PluginMappingResolver;
import de.gematik.refv.Plugin;
import de.gematik.refv.SupportedValidationModule;
import de.gematik.refv.commons.exceptions.ValidationModuleInitializationException;
import de.gematik.refv.commons.validation.ValidationOptions;
import de.gematik.refv.commons.validation.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static de.gematik.isik.mockserver.interceptor.FhirValidationHandlerHelper.ISIK_5_PLUGIN_ID;
import static de.gematik.isik.mockserver.interceptor.FhirValidationUtils.getValidationResult;

@Component
@RequiredArgsConstructor
@Slf4j
public class FhirValidationHandler {

	private final PluginMappingResolver pluginMappingResolver;
	private final PluginLoader pluginLoader;
	private final FhirValidationBundleHandler fhirValidationBundleHandler;

	public ValidationResult validateResource(IBaseResource resource, String body)
			throws ValidationModuleInitializationException {
		if (!resource.getMeta().getProfile().isEmpty()) {
			log.info("Validating resource using meta.profile...");
			return validateResourceWithProfile(resource, body);
		} else {
			log.info("Validating resource using ResourceType...");
			return validateResourceWithResourceType(resource, body);
		}
	}

	private ValidationResult validateResourceWithProfile(IBaseResource resource, String body)
			throws ValidationModuleInitializationException {
		Optional<String> isikProfile = FhirValidationHandlerHelper.findIsikProfile(resource);
		final String profileToUse =
				isikProfile.orElse(resource.getMeta().getProfile().getFirst().getValue());
		final String pluginId = pluginMappingResolver.getPluginIdFromProfile(profileToUse);

		try {
			final Plugin plugin = pluginLoader.getPlugin(pluginId);
			var validationModule = fhirValidationBundleHandler.getOrCreateModule(plugin);
			return validationModule.validateString(body);
		} catch (IllegalArgumentException iae) {
			log.warn("No plugin found for profile {}", profileToUse);
			return ValidationResult.createInstance(
					ResultSeverityEnum.ERROR, "Profile unsupported: " + profileToUse, "Resource.meta.profile[0]");
		}
	}

	private ValidationResult validateResourceWithResourceType(IBaseResource resource, String body)
			throws ValidationModuleInitializationException {
		String resourceType = resource.getIdElement().getResourceType();
		if (resourceType == null || resourceType.isEmpty()) {
			resourceType = FhirValidationHandlerHelper.getResourceType(body);
		}
		List<String> pluginIds = pluginMappingResolver.getPluginIdsFromResourceType(resourceType);
		List<String> profileUrls = pluginMappingResolver.getProfileUrlsFromResourceType(resourceType);
		List<Plugin> plugins;

		if (pluginIds.isEmpty() || !pluginLoader.isEnabled()) {
			return validateResourceWithCoreModule(resourceType, body);
		}

		try {
			plugins = new ArrayList<>(
					pluginIds.stream().map(pluginLoader::getPlugin).toList());

			if (resourceType.equals("Bundle")) {
				List<String> filteredProfileUrls = filterBundleProfilesByType(resource, profileUrls);
				return fhirValidationBundleHandler.validateBundleResourceWithPlugins(
						body, plugins, filteredProfileUrls);
			} else {
				List<ValidationOptions> validationOptionsList = profileUrls.stream()
						.map(profileUrl -> {
							ValidationOptions validationOptions = ValidationOptions.getDefaults();
							validationOptions.setProfiles(Collections.singletonList(profileUrl));
							return validationOptions;
						})
						.toList();

				return validateResourceWithPlugins(body, plugins, validationOptionsList);
			}
		} catch (IllegalArgumentException iae) {
			log.warn("Resource not known: {}", resourceType);
			return ValidationResult.createInstance(
					ResultSeverityEnum.ERROR, "Failed to validate Resource - unsupported", "Resource.meta.profile[0]");
		}
	}

	private ValidationResult validateResourceWithCoreModule(String resourceType, String body)
			throws ValidationModuleInitializationException {
		log.info("Validating resource using FHIR core validation module...");
		String profileUrl = "http://hl7.org/fhir/StructureDefinition/" + resourceType;
		ValidationOptions validationOptions = ValidationOptions.getDefaults();
		validationOptions.setProfiles(Collections.singletonList(profileUrl));
		var coreModule = fhirValidationBundleHandler.getOrCreateCoreModule(SupportedValidationModule.CORE);

		return coreModule.validateString(body, validationOptions);
	}

	private ValidationResult validateResourceWithPlugins(
			String body, List<Plugin> plugins, List<ValidationOptions> validationOptionsList)
			throws ValidationModuleInitializationException {

		final var allValidationMessages = new LinkedList<SingleValidationMessage>();

		// Validate with ISiK5 first
		final var isik5Plugin = FhirValidationHandlerHelper.findPlugin(plugins, ISIK_5_PLUGIN_ID);

		if (isik5Plugin.isPresent() && !validationOptionsList.isEmpty()) {
			log.info("Validating resource using ISiK5 plugin first...");
			final var isik5ValidationModule = fhirValidationBundleHandler.getOrCreateModule(isik5Plugin.get());
			final var validationResult = FhirValidationHandlerHelper.performValidation(
					body, isik5ValidationModule, validationOptionsList.getFirst());
			if (validationResult.isValid()) {
				return validationResult;
			}

			log.warn("ISiK5 validation found issues, proceeding with legacy modules...");
			allValidationMessages.addAll(validationResult.getValidationMessages());
		}

		return getValidationResult(allValidationMessages, List.of());
	}

	/**
	 * Mapping from {@link Bundle.BundleType} to profile URL substrings used to filter the full list
	 * of Bundle profile URLs so that only the profile(s) matching the actual bundle type are used
	 * during validation.
	 */
	private static final Map<Bundle.BundleType, String> BUNDLE_TYPE_PROFILE_KEYWORDS = Map.of(
			Bundle.BundleType.DOCUMENT, "ISiKBerichtBundle",
			Bundle.BundleType.TRANSACTION, "ISiKMedikationTransaction",
			Bundle.BundleType.SEARCHSET, "ISiKDokumentenSuchergebnisse");

	/**
	 * Filters the list of Bundle profile URLs based on the actual {@code Bundle.type} of the
	 * resource. This prevents cross-profile validation failures, e.g. a transaction bundle being
	 * validated against a document bundle profile.
	 *
	 * <p>If the Bundle.type is unknown or no matching profiles are found, all profile URLs are
	 * returned unchanged (best-effort validation).
	 *
	 * <p>Note: {@code ISiKMedikationTransactionResponse} is excluded for incoming validation because
	 * transaction-response bundles are server-generated, not client-submitted.
	 *
	 * @param resource the parsed FHIR resource (expected to be a Bundle)
	 * @param profileUrls all profile URLs mapped to the Bundle resource type
	 * @return the filtered list of profile URLs appropriate for the bundle's type
	 */
	private List<String> filterBundleProfilesByType(IBaseResource resource, List<String> profileUrls) {
		if (!(resource instanceof Bundle bundle)) {
			return profileUrls;
		}

		Bundle.BundleType bundleType = bundle.getType();
		if (bundleType == null) {
			log.warn("Bundle has no type set; validating against all known Bundle profiles");
			return profileUrls;
		}

		String keyword = BUNDLE_TYPE_PROFILE_KEYWORDS.get(bundleType);
		if (keyword == null) {
			log.info(
					"No specific profile mapping for Bundle.type={}; validating against all known Bundle profiles",
					bundleType.toCode());
			return profileUrls;
		}

		List<String> filtered = profileUrls.stream()
				.filter(url -> url.contains(keyword))
				// Exclude TransactionResponse for incoming validation
				.filter(url -> !url.contains("TransactionResponse"))
				.toList();

		if (filtered.isEmpty()) {
			log.warn(
					"No matching profiles found for Bundle.type={}; falling back to all Bundle profiles",
					bundleType.toCode());
			return profileUrls;
		}

		log.info("Filtered Bundle profiles for type={}: {}", bundleType.toCode(), filtered);
		return filtered;
	}
}
