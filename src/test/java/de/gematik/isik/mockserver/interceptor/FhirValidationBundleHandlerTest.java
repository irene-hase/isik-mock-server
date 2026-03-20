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

import de.gematik.isik.mockserver.refv.PluginLoader;
import de.gematik.refv.Plugin;
import de.gematik.refv.SupportedValidationModule;
import de.gematik.refv.commons.validation.ValidationModule;
import de.gematik.refv.commons.validation.ValidationResult;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static de.gematik.isik.mockserver.helper.ResourceLoadingHelper.loadResourceAsString;
import static org.assertj.core.api.Assertions.assertThat;

class FhirValidationBundleHandlerTest {

	private FhirValidationBundleHandler fhirValidationBundleHandler;

	List<String> profileUrls = List.of(
		"https://gematik.de/fhir/isik/StructureDefinition/ISiKBerichtBundle");

	List<Plugin> plugins = new ArrayList<>();

	@BeforeEach
	void setUp() {
		fhirValidationBundleHandler = new FhirValidationBundleHandler();

		PluginLoader pluginLoader = new PluginLoader("plugins", true);
		pluginLoader.init();

		Plugin isik5 = pluginLoader.getPlugin("isik5");
		plugins.add(isik5);
	}

	@SneakyThrows
	@Test
	void shouldValidateValidBundle() {
		String body = loadResourceAsString("fhir-examples/valid/valid-bundle.json");
		ValidationResult result = fhirValidationBundleHandler.validateBundleResourceWithPlugins(body, plugins, profileUrls);

		assertThat(result.isValid()).isTrue();
	}

	@SneakyThrows
	@Test
	void shouldValidateInvalidBundle() {
		String body = loadResourceAsString("fhir-examples/invalid/invalid-bundle.json");
		ValidationResult result = fhirValidationBundleHandler.validateBundleResourceWithPlugins(body, plugins, profileUrls);

		assertThat(result.isValid()).isFalse();
	}

	@SneakyThrows
	@Test
	void shouldCacheModuleAcrossMultipleCalls() {
		Plugin plugin = plugins.get(0);
		ValidationModule module1 = fhirValidationBundleHandler.getOrCreateModule(plugin);
		ValidationModule module2 = fhirValidationBundleHandler.getOrCreateModule(plugin);

		assertThat(module1).isSameAs(module2);
	}

	@SneakyThrows
	@Test
	void shouldCacheCoreModuleAcrossMultipleCalls() {
		ValidationModule core1 = fhirValidationBundleHandler.getOrCreateCoreModule(SupportedValidationModule.CORE);
		ValidationModule core2 = fhirValidationBundleHandler.getOrCreateCoreModule(SupportedValidationModule.CORE);

		assertThat(core1).isSameAs(core2);
	}

	@SneakyThrows
	@Test
	void shouldReturnSameModuleUnderConcurrentAccess() {
		Plugin plugin = plugins.get(0);
		int threadCount = 8;
		CountDownLatch latch = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);

		try {
			List<Future<ValidationModule>> futures = new ArrayList<>();
			for (int i = 0; i < threadCount; i++) {
				futures.add(executor.submit(() -> {
					latch.await();
					return fhirValidationBundleHandler.getOrCreateModule(plugin);
				}));
			}

			latch.countDown();

			List<ValidationModule> modules = new ArrayList<>();
			for (Future<ValidationModule> f : futures) {
				modules.add(f.get());
			}

			// All threads should receive the same cached instance
			ValidationModule first = modules.get(0);
			for (ValidationModule m : modules) {
				assertThat(m).isSameAs(first);
			}
		} finally {
			executor.shutdown();
		}
	}

	@SneakyThrows
	@Test
	void shouldReturnEmptyResultForNonIsik5Plugin() {
		Plugin nonIsik5 = org.mockito.Mockito.mock(Plugin.class);
		org.mockito.Mockito.when(nonIsik5.getId()).thenReturn("some-other-plugin");

		List<Plugin> otherPlugins = List.of(nonIsik5);
		String body = loadResourceAsString("fhir-examples/valid/valid-bundle.json");

		ValidationResult result = fhirValidationBundleHandler.validateBundleResourceWithPlugins(body, otherPlugins, profileUrls);

		// No isik5 plugin found, returns empty validation messages
		assertThat(result.getValidationMessages()).isEmpty();
	}
}
