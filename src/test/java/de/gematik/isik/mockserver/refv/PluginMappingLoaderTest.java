package de.gematik.isik.mockserver.refv;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginMappingLoaderTest {

	@InjectMocks
	private PluginMappingLoader pluginMappingLoader;

	private AutoCloseable mocks;

	private final Map<String, List<String>> mockResourceTypeToPluginIdMap = Map.of(
		"ResourceTypeA", List.of("PluginId1", "PluginId2")
	);
	private final Map<String, List<String>> mockResourceTypeToProfileUrlMap = Map.of(
		"ResourceTypeA", List.of("ProfileUrl1", "ProfileUrl2")
	);
	private final Map<String, List<String>> mockProfileUrlToPluginIdMap = Map.of(
		"isik5", List.of("https://gematik.de/fhir/isik/StructureDefinition/ISiKPatient")
	);

	@BeforeEach
	@SneakyThrows
	void setUp() {
		mocks = MockitoAnnotations.openMocks(this);

		String resourceTypeToPluginIdPath = "mockResourceTypeToPluginId.json";
		String resourceTypeToProfileUrlPath = "mockResourceTypeToProfileUrl.json";
		String profileUrlToPluginIdPath = "mockProfileUrlToPluginId.json";

		pluginMappingLoader = new PluginMappingLoader(resourceTypeToPluginIdPath, resourceTypeToProfileUrlPath, profileUrlToPluginIdPath, new ObjectMapper());

	}

	@AfterEach
	@SneakyThrows
	void tearDown() {
		if (mocks != null) {
			mocks.close();
		}
	}

	@Test
	@SneakyThrows
	void testLoadData() {
		pluginMappingLoader.loadData();

		assertThat(pluginMappingLoader.getResourceTypeToPluginIdMap()).isEqualTo(mockResourceTypeToPluginIdMap);
		assertThat(pluginMappingLoader.getResourceTypeToProfileUrlMap()).isEqualTo(mockResourceTypeToProfileUrlMap);
		assertThat(pluginMappingLoader.getProfileUrlToPluginIdMap()).isEqualTo(mockProfileUrlToPluginIdMap);
	}

	@Test
	void testLoadData_WithNonExistentFile_ShouldThrow() {
		PluginMappingLoader badLoader = new PluginMappingLoader(
			"nonexistent.json", "mockResourceTypeToProfileUrl.json", "mockProfileUrlToPluginId.json", new ObjectMapper());

		assertThatThrownBy(badLoader::loadData).isInstanceOf(Exception.class);
	}

	@Test
	@SneakyThrows
	void testLoadData_CalledTwice_ShouldReloadData() {
		pluginMappingLoader.loadData();
		Map<String, List<String>> firstLoad = pluginMappingLoader.getResourceTypeToPluginIdMap();

		pluginMappingLoader.loadData();
		Map<String, List<String>> secondLoad = pluginMappingLoader.getResourceTypeToPluginIdMap();

		assertThat(firstLoad).isEqualTo(secondLoad);
	}

	@Test
	@SneakyThrows
	void testLoadData_MapsAreNotNull() {
		pluginMappingLoader.loadData();

		assertThat(pluginMappingLoader.getResourceTypeToPluginIdMap()).isNotNull();
		assertThat(pluginMappingLoader.getResourceTypeToProfileUrlMap()).isNotNull();
		assertThat(pluginMappingLoader.getProfileUrlToPluginIdMap()).isNotNull();
	}

	@Test
	void testMapsAreNullBeforeLoadData() {
		assertThat(pluginMappingLoader.getResourceTypeToPluginIdMap()).isNull();
		assertThat(pluginMappingLoader.getResourceTypeToProfileUrlMap()).isNull();
		assertThat(pluginMappingLoader.getProfileUrlToPluginIdMap()).isNull();
	}
}
