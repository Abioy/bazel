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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.LABEL;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.PLIST_TYPE;

import com.google.devtools.build.lib.analysis.BlazeRule;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;

/**
 * Rule definition for objc_bundle_library.
 */
@BlazeRule(name = "objc_bundle_library",
    factoryClass = ObjcBundleLibrary.class,
    ancestors = { ObjcLibraryRule.class })
public class ObjcBundleLibraryRule implements RuleDefinition {
  @Override
  public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
    return builder
        /*<!-- #BLAZE_RULE(objc_bundle_library).IMPLICIT_OUTPUTS -->
        <ul>
         <li><code><var>name</var>.xcodeproj/project.pbxproj</code>: An Xcode project file which
         can be used to develop or build on a Mac.</li>
        </ul>
        <!-- #END_BLAZE_RULE.IMPLICIT_OUTPUTS -->*/
        .setImplicitOutputsFunction(ImplicitOutputsFunction.fromFunctions(ObjcRuleClasses.PBXPROJ))
        /* <!-- #BLAZE_RULE(objc_bundle_library).ATTRIBUTE(infoplist) -->
        The infoplist file. This corresponds to <i>appname</i>-Info.plist in Xcode projects.
        ${SYNOPSIS}
        Blaze will perform variable substitution on the plist file for the following values:
        <ul>
          <li><code>${EXECUTABLE_NAME}</code>: The name of the executable generated and included
              in the bundle by blaze, which can be used as the value for
              <code>CFBundleExecutable</code> within the plist.
          <li><code>${BUNDLE_NAME}</code>: This target's name and bundle suffix (.bundle or .app)
              in the form<code><var>name</var></code>.<code>suffix</code>.
          <li><code>${PRODUCT_NAME}</code>: This target's name.
        </ul>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("infoplist", LABEL)
            .allowedFileTypes(PLIST_TYPE))
        .removeAttribute("alwayslink")
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = objc_bundle_library, TYPE = LIBRARY, FAMILY = Objective-C) -->

${ATTRIBUTE_SIGNATURE}

<p>This rule encapsulates a library which is provided to dependers as a bundle.
It is similar to <code>objc_library</code> with the key difference being that
with <code>objc_bundle_libary</code>, the resources and binary are put in a
nested bundle in the final iOS application, whereas with a normal
<code>objc_library</code>, the resources are placed in the same bundle as the
application and the libraries are linked into the main application binary.

${ATTRIBUTE_DEFINITION}

<!-- #END_BLAZE_RULE -->*/
