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
package com.google.devtools.build.lib.rules.java;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.JavaClasspathMode;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.view.RedirectChaser;
import com.google.devtools.build.lib.view.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.view.config.BuildOptions;
import com.google.devtools.build.lib.view.config.ConfigurationEnvironment;
import com.google.devtools.build.lib.view.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.view.config.InvalidConfigurationException;

/**
 * A loader that creates JavaConfiguration instances based on JavaBuilder configurations and
 * command-line options.
 */
public class JavaConfigurationLoader implements ConfigurationFragmentFactory {
  private final JavaCpuSupplier cpuSupplier;

  public JavaConfigurationLoader(JavaCpuSupplier cpuSupplier) {
    this.cpuSupplier = cpuSupplier;
  }

  @Override
  public JavaConfiguration create(ConfigurationEnvironment env, BuildOptions buildOptions)
      throws InvalidConfigurationException {
    JavaOptions javaOptions = buildOptions.get(JavaOptions.class);

    ImmutableList<String> defaultJavacOpts = getDefaultJavacOptions(env, javaOptions);
    if (defaultJavacOpts == null) {
      return null;
    }

    Label javaToolchain = RedirectChaser.followRedirects(env, javaOptions.javaToolchain,
        "java_toolchain");
    return create(defaultJavacOpts, javaOptions, javaToolchain, 
        cpuSupplier.getJavaCpu(buildOptions, env));
  }

  @Override
  public Class<? extends Fragment> creates() {
    return JavaConfiguration.class;
  }

  public JavaConfiguration create(ImmutableList<String> defaultJavacOpts,
      JavaOptions javaOptions, Label javaToolchain, String javaCpu)
          throws InvalidConfigurationException {

    boolean generateJavaDeps = javaOptions.javaDeps ||
        javaOptions.experimentalJavaClasspath != JavaClasspathMode.OFF;

    ImmutableList<String> defaultJavaBuilderJvmOpts = ImmutableList.<String>builder()
        .addAll(getJavacJvmOptions())
        .addAll(JavaHelper.tokenizeJavaOptions(javaOptions.javaBuilderJvmOpts))
        .build();

    return new JavaConfiguration(defaultJavacOpts,
        ImmutableList.copyOf(JavaHelper.tokenizeJavaOptions(javaOptions.javacOpts)),
        generateJavaDeps, javaOptions.jvmOpts, javaOptions, javaToolchain, javaCpu,
        defaultJavaBuilderJvmOpts);
  }

  /**
   * This method returns the list of JVM options when invoking the java compiler.
   *
   * <p>TODO(bazel-team): Maybe we should put those options in the java_toolchain rule.
   */
  protected ImmutableList<String> getJavacJvmOptions() {
    return ImmutableList.of("-client");
  }

  /**
   * This method uses custom way to determine default javac options. It should returns null if some
   * dependency are not yet loaded and an empty list for no special options.
   *
   * <p>This method will be deprecated in the future.
   */
  @SuppressWarnings("unused")
  protected ImmutableList<String> getDefaultJavacOptions(ConfigurationEnvironment env,
      JavaOptions javaOptions) throws InvalidConfigurationException {
    return ImmutableList.<String>of();
  }

}
