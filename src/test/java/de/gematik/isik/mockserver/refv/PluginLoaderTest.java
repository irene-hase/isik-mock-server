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

import de.gematik.refv.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PluginLoaderTest {

	private PluginLoader pluginLoader;

	@BeforeEach
	public void setUp() {
		pluginLoader = new PluginLoader("plugins", true);
		pluginLoader.init();
	}

	@Test
	void testInitShouldLoadPlugins() {
		assertThat(pluginLoader.getPlugins())
			.hasSize(1)
			.containsKeys(
				"isik5"
			);
	}

	@Test
	void testGetPlugin() {
		Plugin result = pluginLoader.getPlugin("isik5");
		assertThat(result).isNotNull();
		assertThat(result.getId()).isEqualTo("isik5");
	}

	@Test
	void testInit_WhenNoPluginsFound_PluginsListShouldBeEmpty() {
		pluginLoader = new PluginLoader("resources/conformance", true);
		pluginLoader.init();
		assertThat(pluginLoader.getPlugins()).isEmpty();
	}


	@Test
	void testGetPlugin_WhenPluginDoesNotExist_ShouldThrowException() {
		assertThatThrownBy(() -> pluginLoader.getPlugin("nonexistent"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Validation module [nonexistent] unsupported");
	}

	@Test
	void testInit_WhenDisabled_ShouldNotLoadPlugins() {
		PluginLoader disabledLoader = new PluginLoader("plugins", false);
		disabledLoader.init();
		assertThat(disabledLoader.getPlugins()).isEmpty();
		assertThat(disabledLoader.isEnabled()).isFalse();
	}

	@Test
	void testInit_CalledTwice_ShouldReloadPlugins() {
		assertThat(pluginLoader.getPlugins()).hasSize(1);

		pluginLoader.init();
		assertThat(pluginLoader.getPlugins()).hasSize(1);
	}

	@Test
	void testGetPlugin_WhenNullId_ShouldThrowException() {
		assertThatThrownBy(() -> pluginLoader.getPlugin(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testGetPlugin_WhenEmptyId_ShouldThrowException() {
		assertThatThrownBy(() -> pluginLoader.getPlugin(""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Validation module [] unsupported");
	}

	@Test
	void testIsEnabled_WhenEnabled_ShouldReturnTrue() {
		assertThat(pluginLoader.isEnabled()).isTrue();
	}
}
