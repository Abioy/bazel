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

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.view.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.view.test.InstrumentedFilesProviderImpl;

/**
 * A ConfiguredTarget for an OutputFile.
 */
public class OutputFileConfiguredTarget extends FileConfiguredTarget
    implements LicensesProvider, InstrumentedFilesProvider {

  private final Artifact artifact;
  private final TransitiveInfoCollection generatingRule;

  OutputFileConfiguredTarget(
      TargetContext targetContext, OutputFile outputFile,
      TransitiveInfoCollection generatingRule, Artifact outputArtifact) {
    super(targetContext);
    Preconditions.checkArgument(targetContext.getTarget() == outputFile);
    this.artifact = outputArtifact;
    this.generatingRule = generatingRule;
    filesToBuild = NestedSetBuilder.create(Order.STABLE_ORDER, artifact);
  }

  @Override
  public OutputFile getTarget() {
    return (OutputFile) super.getTarget();
  }

  @Override
  public Artifact getArtifact() {
    return artifact;
  }

  public TransitiveInfoCollection getGeneratingRule() {
    return generatingRule;
  }

  @Override
  public NestedSet<TargetLicense> getTransitiveLicenses() {
    return getProvider(LicensesProvider.class, LicensesProviderImpl.EMPTY)
        .getTransitiveLicenses();
  }

  @Override
  public NestedSet<Artifact> getInstrumentedFiles() {
    return getProvider(InstrumentedFilesProvider.class, InstrumentedFilesProviderImpl.EMPTY)
        .getInstrumentedFiles();
  }

  @Override
  public NestedSet<Artifact> getInstrumentationMetadataFiles() {
    return getProvider(InstrumentedFilesProvider.class, InstrumentedFilesProviderImpl.EMPTY)
        .getInstrumentationMetadataFiles();
  }

  /**
   * Returns the corresponding provider from the generating rule, if it is non-null, or {@code
   * defaultValue} otherwise.
   */
  private <T extends TransitiveInfoProvider> T getProvider(Class<T> clazz, T defaultValue) {
    if (generatingRule != null) {
      T result = generatingRule.getProvider(clazz);
      if (result != null) {
        return result;
      }
    }
    return defaultValue;
  }
}
