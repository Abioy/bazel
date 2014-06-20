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

package com.google.devtools.build.lib.view.config;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.Label.SyntaxException;
import com.google.devtools.common.options.Options;
import com.google.devtools.common.options.OptionsBase;

import java.util.Map;
import java.util.Set;

/**
 * Command-line build options for a Blaze module.
 */
public abstract class FragmentOptions extends OptionsBase implements Cloneable {

  /**
   * Adds all labels defined by the options to a multimap. See {@code BuildOptions.getAllLabels()}.
   *
   * <p>There should generally be no code duplication between this code and DefaultsPackage. Either
   * the labels are loaded unconditionally using this method, or they are added as magic labels
   * using the tools/defaults package, but not both.
   */
  public abstract void addAllLabels(Multimap<String, Label> labelMap);

  /**
   * Returns the labels contributed to the defaults package by this fragment.
   *
   * <p>The set of keys returned by this function should be constant, however, the values are
   * allowed to change depending on the value of the options.
   */
  @SuppressWarnings("unused")
  public Map<String, Set<Label>> getDefaultsLabels(BuildConfiguration.Options commonOptions) {
    return ImmutableMap.of();
  }

  @Override
  public FragmentOptions clone() {
    try {
      return (FragmentOptions) super.clone();
    } catch (CloneNotSupportedException e) {
      // This can't happen.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Creates a new FragmentOptions instance with all flags set to default.
   */
  public FragmentOptions getDefault() {
    return Options.getDefaults(getClass());
  }

  /**
   * Creates a new FragmentOptions instance with flags adjusted to host platform.
   *
   * @param fallback see {@code BuildOptions.createHostOptions}
   */
  @SuppressWarnings("unused")
  public FragmentOptions getHost(boolean fallback) {
    return getDefault();
  }

  protected void addOptionalLabel(Multimap<String, Label> map, String key, String value) {
    Label label = parseOptionalLabel(value);
    if (label != null) {
      map.put(key, label);
    }
  }

  private static Label parseOptionalLabel(String value) {
    if ((value != null) && value.startsWith("//")) {
      try {
        return Label.parseAbsolute(value);
      } catch (SyntaxException e) {
        // We ignore this exception here - it will cause an error message at a later time.
        // TODO(bazel-team): We can use a Converter to check the validity of the crosstoolTop
        // earlier.
        return null;
      }
    } else {
      return null;
    }
  }
}
