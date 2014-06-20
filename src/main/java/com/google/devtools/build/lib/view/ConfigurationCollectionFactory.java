// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.view;

import com.google.devtools.build.lib.blaze.BlazeDirectories;
import com.google.devtools.build.lib.events.ErrorEventListener;
import com.google.devtools.build.lib.pkgcache.LoadedPackageProvider;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.config.BuildOptions;
import com.google.devtools.build.lib.view.config.ConfigurationEnvironment;
import com.google.devtools.build.lib.view.config.ConfigurationFactory;
import com.google.devtools.build.lib.view.config.InvalidConfigurationException;
import com.google.devtools.build.lib.view.config.MachineSpecification;

import java.util.Map;

/**
 * A factory for configuration collection creation.
 */
public interface ConfigurationCollectionFactory {
  /**
   * Creates the top-level configuration for a build.
   *
   * <p>Also it may create a set of BuildConfigurations and define a transition table over them.
   * All configurations during a build should be accessible from this top-level configuration
   * via configuration transitions.
   * @param loadedPackageProvider the package provider
   * @param buildOptions top-level build options representing the command-line
   * @param directories set of significant Blaze directories
   * @param clientEnv the system environment
   * @param errorEventListener the event listener for errors
   * @param performSanityCheck flag to signal about performing sanity check. Can be false only for
   * tests in skyframe. Legacy mode uses loadedPackageProvider == null condition for this.
   * @return the top-level configuration
   * @throws InvalidConfigurationException
   */
  public BuildConfiguration createConfigurations(
      ConfigurationFactory configurationFactory,
      MachineSpecification hostMachineSpecification,
      LoadedPackageProvider loadedPackageProvider,
      BuildOptions buildOptions,
      BlazeDirectories directories,
      Map<String, String> clientEnv,
      ErrorEventListener errorEventListener,
      ConfigurationEnvironment env,
      boolean performSanityCheck) throws InvalidConfigurationException;
}
