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
package com.google.devtools.build.lib.testutil;

import com.google.devtools.build.lib.util.SkyframeMode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Test annotations used to select which tests to run in a given situation.
 */
public enum Suite {

  /**
   * It's so blazingly fast and lightweight we run it whenever we make any
   * build.lib change. This size is the default.
   */
  SMALL_TESTS,

  /**
   * It's a bit too slow to run all the time, but it still tests some
   * unit of functionality. May run external commands such as gcc, for example.
   */
  MEDIUM_TESTS,

  /**
   * I don't even want to think about running this one after every edit,
   * but I don't mind if the continuous build runs it, and I'm happy to have
   * it before making a release.
   */
  LARGE_TESTS,

  /**
   * These tests take a long time. They should only ever be run manually and probably from their
   * own Blaze test target.
   */
  ENORMOUS_TESTS,

  /**
   * I don't want the continuous build to run this test because the test is
   * doing timing.  If I run in on my computer, I'll need to make sure my
   * computer is not heavily taxed running other things.
   */
  PERFORMANCE_TESTS,

  /**
   * Tests that can only be run on Unix.
   */
  UNIX_TESTS,

  /**
   * Tests that can run both on Windows and Unix.
   */
  ALL_PLATFORM_TESTS;

  /**
   * Given a class, determine the test size.
   */
  public static Suite getSize(Class<?> clazz) {
    return getAnnotationElementOrDefault(clazz, "size");
  }

  /**
   * Given a class, determine the platforms where it can run.
   */
  public static Suite getPlatform(Class<?> clazz) {
    return getAnnotationElementOrDefault(clazz, "platform");
  }

  /**
   * Given a class, determine the minimum level of skyframe it makes sense to run on.
   */
  public static SkyframeMode getSkyframeMin(Class<?> clazz) {
    return getAnnotationElementOrDefault(clazz, "skyframeMin");
  }

  /**
   * Given a class, determine the maximum level of skyframe it makes sense to run on.
   */
  public static SkyframeMode getSkyframeMax(Class<?> clazz) {
    return getAnnotationElementOrDefault(clazz, "skyframeMax");
  }

  /**
   * Given a class, determine the suite it belongs to.
   */
  public static String getSuiteName(Class<?> clazz) {
    return getAnnotationElementOrDefault(clazz, "suite");
  }

  /**
   * Returns the value of the given element in the {@link TestSpec} annotation of the given class,
   * or the default value of that element if the class doesn't have a {@link TestSpec} annotation.
   */
  @SuppressWarnings("unchecked")
  private static <T> T getAnnotationElementOrDefault(Class<?> clazz, String elementName) {
    TestSpec spec = clazz.getAnnotation(TestSpec.class);
    try {
      Method method = TestSpec.class.getMethod(elementName);
      return spec != null ? (T) method.invoke(spec) : (T) method.getDefaultValue();
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException("no such element " + elementName, e);
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      throw new IllegalStateException("can't invoke accessor for element " + elementName, e);
    }
  }
}
