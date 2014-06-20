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
package com.google.devtools.build.docgen;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.devtools.build.docgen.DocgenConsts.RuleType;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.view.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.view.RuleDefinition;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A class to assemble documentation for the Build Encyclopedia. The
 * program parses the documentation fragments of rule-classes and
 * generates the html format documentation.
 */
public class BuildEncyclopediaProcessor {

  private ConfiguredRuleClassProvider ruleClassProvider;

  /**
   * Creates the BuildEncyclopediaProcessor instance. The ruleClassProvider parameter
   * is used for rule class hierarchy and attribute checking.
   *
   */
  public BuildEncyclopediaProcessor(ConfiguredRuleClassProvider ruleClassProvider) {
    this.ruleClassProvider = Preconditions.checkNotNull(ruleClassProvider);
  }

  /**
   * Collects and processes all the rule and attribute documentation in inputDirs and
   * generates the Build Encyclopedia into the outputRootDir.
   */
  public void generateDocumentation(String[] inputDirs, String outputRootDir)
      throws BuildEncyclopediaDocException, IOException {
    BufferedWriter bw = null;
    File buildEncyclopediaPath = setupDirectories(outputRootDir);
    try {
      bw = new BufferedWriter(new FileWriter(buildEncyclopediaPath));
      System.out.println(
          "Build Encyclopedia generated: " + buildEncyclopediaPath.getAbsolutePath());

      bw.write(DocgenConsts.HEADER_COMMENT);

      Set<RuleDocumentation> ruleDocEntries = collectAndProcessRuleDocs(inputDirs, true);
      writeRuleClassDocs(ruleDocEntries, bw);

      bw.write(SourceFileReader.readTemplateContents(DocgenConsts.FOOTER_TEMPLATE));

    } finally {
      if (bw != null) {
        bw.close();
      }
    }
  }

  /**
   * Collects all the rule and attribute documentation present in inputDirs, integrates the
   * attribute documentation in the rule documentation and returns the rule documentation.
   */
  public Set<RuleDocumentation> collectAndProcessRuleDocs(String[] inputDirs,
      boolean printMessages) throws BuildEncyclopediaDocException, IOException {
    // RuleDocumentations are generated in order (based on rule type then alphabetically).
    // The ordering is also used to determine in which rule doc the common attribute docs are
    // generated (they are generated at the first appearance).
    Set<RuleDocumentation> ruleDocEntries = new TreeSet<>();
    // RuleDocumentationAttribute objects equal based on attributeName so they have to be
    // collected in a List instead of a Set.
    ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries =
        LinkedListMultimap.create();
    for (String inputDir : inputDirs) {
      if (printMessages) {
        System.out.println(" Processing input directory: " + inputDir);
      }
      int ruleNum = ruleDocEntries.size();
      collectDocs(ruleDocEntries, attributeDocEntries, new File(inputDir));
      if (printMessages) {
        System.out.println(
          " " + (ruleDocEntries.size() - ruleNum) + " rule documentations found.");
      }
    }

    processAttributeDocs(ruleDocEntries, attributeDocEntries);
    return ruleDocEntries;
  }

  /**
   * Go through all attributes of all documented rules and search the best attribute documentation
   * if exists. The best documentation is the closest documentation in the ancestor graph. E.g. if
   * java_library.deps documented in $rule and $java_rule then the one in $java_rule is going to
   * apply since it's a closer ancestor of java_library.
   */
  private void processAttributeDocs(Set<RuleDocumentation> ruleDocEntries,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries)
          throws BuildEncyclopediaDocException {
    for (RuleDocumentation ruleDoc : ruleDocEntries) {
      RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName());
      if (ruleClass != null) {
        if (ruleClass.isDocumented()) {
          Class<? extends RuleDefinition> ruleDefinition =
              ruleClassProvider.getRuleClassDefinition(ruleDoc.getRuleName());
          for (Attribute attribute : ruleClass.getAttributes()) {
            String attrName = attribute.getName();
            List<RuleDocumentationAttribute> attributeDocList =
                attributeDocEntries.get(attrName);
            if (attributeDocList != null) {
              // There are attribute docs for this attribute.
              // Search the closest one in the ancestor graph.
              int minLevel = Integer.MAX_VALUE;
              RuleDocumentationAttribute bestAttributeDoc = null;
              for (RuleDocumentationAttribute attributeDoc : attributeDocList) {
                int level = attributeDoc.getDefinitionClassAncestryLevel(ruleDefinition);
                if (level >= 0 && level < minLevel) {
                  bestAttributeDoc = attributeDoc;
                  minLevel = level;
                }
              }
              if (bestAttributeDoc != null) {
                ruleDoc.addAttribute(bestAttributeDoc);
                // If this is the first rule using this attribute documentation
                // it is going to be generated here in the Build Encyclopedia.
                // If not, there will be a link.
                bestAttributeDoc.setGeneratedInRuleIfNotSet(ruleDoc.getRuleName());
              // If there is no matching attribute doc try to add the common.
              } else if (ruleDoc.getRuleType().equals(RuleType.BINARY)
                  && PredefinedAttributes.BINARY_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.BINARY_ATTRIBUTES.get(attrName));
              } else if (ruleDoc.getRuleType().equals(RuleType.TEST)
                  && PredefinedAttributes.TEST_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.TEST_ATTRIBUTES.get(attrName));
              } else if (PredefinedAttributes.COMMON_ATTRIBUTES.containsKey(attrName)) {
                ruleDoc.addAttribute(PredefinedAttributes.COMMON_ATTRIBUTES.get(attrName));
              }
            }
          }
        }
      } else {
        throw ruleDoc.createException("Can't find RuleClass for " + ruleDoc.getRuleName());
      }
    }
  }

  /**
   * Categorizes, checks and prints all the rule-class documentations.
   */
  private void writeRuleClassDocs(Set<RuleDocumentation> docEntries, BufferedWriter bw)
      throws BuildEncyclopediaDocException, IOException {
    Set<RuleDocumentation> binaryDocs = new TreeSet<>();
    Set<RuleDocumentation> libraryDocs = new TreeSet<>();
    Set<RuleDocumentation> testDocs = new TreeSet<>();
    Set<RuleDocumentation> generateDocs = new TreeSet<>();
    Set<RuleDocumentation> otherDocs = new TreeSet<>();

    for (RuleDocumentation doc : docEntries) {
      RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(doc.getRuleName());
      if (ruleClass.isDocumented()) {
        if (DocgenConsts.LANGUAGE_SPECIFIC_RULE_FAMILIES.contains(doc.getRuleFamily())) {
          switch(doc.getRuleType()) {
            case BINARY:
              binaryDocs.add(doc);
              break;
            case LIBRARY:
              libraryDocs.add(doc);
              break;
            case TEST:
              testDocs.add(doc);
              break;
            case OTHER:
              otherDocs.add(doc);
              break;
          }
        } else if (DocgenConsts.OTHER_RULE_FAMILIES.contains(doc.getRuleFamily())) {
          otherDocs.add(doc);
        } else {
          throw doc.createException("Unknown rule family: " + doc);
        }
      }
    }

    bw.write(SourceFileReader.readTemplateContents(DocgenConsts.HEADER_TEMPLATE,
        generateBEHeaderMapping(docEntries)));

    Map<String, String> sectionMapping = ImmutableMap.of(
        DocgenConsts.VAR_SECTION_BINARY,   getRuleDocs(binaryDocs),
        DocgenConsts.VAR_SECTION_LIBRARY,  getRuleDocs(libraryDocs),
        DocgenConsts.VAR_SECTION_TEST,     getRuleDocs(testDocs),
        DocgenConsts.VAR_SECTION_GENERATE, getRuleDocs(generateDocs),
        DocgenConsts.VAR_SECTION_OTHER,    getRuleDocs(otherDocs));
    bw.write(SourceFileReader.readTemplateContents(DocgenConsts.BODY_TEMPLATE, sectionMapping));
  }

  private Map<String, String> generateBEHeaderMapping(Set<RuleDocumentation> docEntries)
      throws BuildEncyclopediaDocException {
    StringBuilder sb = new StringBuilder();
    sb.append("<table id=\"rules\" summary=\"Table of rules sorted by language\">\n")
      .append("<colgroup span=\"5\" width=\"20%\"></colgroup>\n")
      .append("<tr><th>Language</th><th>Binary rules</th><th>Library rules</th>"
        + "<th>Test rules</th><th>Other rules</th><th></th></tr>\n");
    // Create a mapping of rules based on rule type and family.
    Map<String, ListMultimap<RuleType, RuleDocumentation>> ruleMapping = new HashMap<>();
    for (RuleDocumentation ruleDoc : docEntries) {
      RuleClass ruleClass = ruleClassProvider.getRuleClassMap().get(ruleDoc.getRuleName());
      if (ruleClass != null) {
        String ruleFamily = ruleDoc.getRuleFamily();
        if (!ruleMapping.containsKey(ruleFamily)) {
          ruleMapping.put(ruleFamily, LinkedListMultimap.<RuleType, RuleDocumentation>create());
        }
        if (ruleClass.isDocumented()) {
          ruleMapping.get(ruleFamily).put(ruleDoc.getRuleType(), ruleDoc);
        }
      } else {
        throw ruleDoc.createException("Can't find RuleClass for " + ruleDoc.getRuleName());
      }
    }
    // Generate the table.
    for (String ruleFamilyKey : DocgenConsts.LANGUAGE_SPECIFIC_RULE_FAMILIES) {
      generateHeaderTableRuleFamily(sb, ruleMapping, ruleFamilyKey);
    }
    sb.append(" </tr>\n");
    sb.append("<tr><th>&nbsp;</th></tr>");
    sb.append("<tr><th colspan=\"5\">Rules that do not apply to a "
            + "specific programming language</th></tr>");
    for (String ruleFamilyKey : DocgenConsts.OTHER_RULE_FAMILIES) {
      generateHeaderTableRuleFamily(sb, ruleMapping, ruleFamilyKey);
    }
    sb.append("</table>\n");
    return ImmutableMap.<String, String>of(DocgenConsts.VAR_HEADER_TABLE, sb.toString(),
        DocgenConsts.VAR_COMMON_ATTRIBUTE_DEFINITION, generateCommonAttributeDocs(
            PredefinedAttributes.COMMON_ATTRIBUTES, DocgenConsts.COMMON_ATTRIBUTES),
        DocgenConsts.VAR_TEST_ATTRIBUTE_DEFINITION, generateCommonAttributeDocs(
            PredefinedAttributes.TEST_ATTRIBUTES, DocgenConsts.TEST_ATTRIBUTES),
        DocgenConsts.VAR_BINARY_ATTRIBUTE_DEFINITION, generateCommonAttributeDocs(
            PredefinedAttributes.BINARY_ATTRIBUTES, DocgenConsts.BINARY_ATTRIBUTES));
  }

  private String generateCommonAttributeDocs(Map<String, RuleDocumentationAttribute> attributes,
      String attributeGroupName) throws BuildEncyclopediaDocException {
    RuleDocumentation ruleDoc = new RuleDocumentation(
        attributeGroupName, "OTHER", null, null, 0, null, ImmutableSet.<String>of());
    for (RuleDocumentationAttribute attribute : attributes.values()) {
      ruleDoc.addAttribute(attribute);
    }
    return ruleDoc.generateAttributeDefinitions();
  }

  private void generateHeaderTableRuleFamily(StringBuilder sb,
      Map<String, ListMultimap<RuleType, RuleDocumentation>> ruleMapping, String ruleFamilyKey) {
    String ruleFamilyName = DocgenConsts.RULE_FAMILY_NAMES.get(ruleFamilyKey);
    sb.append(" <tr>\n")
      .append(String.format("  <td class=\"lang\">%s</td>\n", ruleFamilyName));
    ListMultimap<RuleType, RuleDocumentation> ruleTypeMap = ruleMapping.get(ruleFamilyKey);
    boolean otherRulesSplitted = false;
    for (RuleType ruleType : DocgenConsts.RuleType.values()) {
      sb.append("  <td>");
      int i = 0;
      List<RuleDocumentation> ruleDocList = ruleTypeMap.get(ruleType);
      for (RuleDocumentation ruleDoc : ruleDocList) {
        if (i > 0) {
          if (ruleType.equals(RuleType.OTHER)
              && ruleDocList.size() >= 4 && i == (ruleDocList.size() + 1) / 2) {
            // Split 'other rules' into two columns if there are too many of them.
            sb.append("</td>\n  <td>");
            otherRulesSplitted = true;
          } else {
            sb.append("<br/>");
          }
        }
        String ruleName = ruleDoc.getRuleName();
        String deprecatedString = ruleDoc.hasFlag(DocgenConsts.FLAG_DEPRECATED)
            ? " class=\"deprecated\"" : "";
        sb.append(String.format("<a href=\"#%s\"%s>%s</a>", ruleName, deprecatedString, ruleName));
        i++;
      }
      sb.append("</td>\n");
    }
    // There should be 6 columns.
    if (!otherRulesSplitted) {
      sb.append("  <td></td>\n");
    }
  }

  private String getRuleDocs(Iterable<RuleDocumentation> docEntries) {
    StringBuilder sb = new StringBuilder();
    for (RuleDocumentation doc : docEntries) {
      sb.append(doc.getHtmlDocumentation());
    }
    return sb.toString();
  }

  /**
   * Goes through all the html files and subdirs under inputPath and collects the rule
   * and attribute documentations using the ruleDocEntries and attributeDocEntries variable.
   */
  public void collectDocs(Set<RuleDocumentation> ruleDocEntries,
      ListMultimap<String, RuleDocumentationAttribute> attributeDocEntries,
      File inputPath) throws BuildEncyclopediaDocException, IOException {
    if (inputPath.isFile()) {
      if (DocgenConsts.JAVA_SOURCE_FILE_SUFFIX.apply(inputPath.getName())) {
        SourceFileReader sfr = new SourceFileReader(
            ruleClassProvider, inputPath.getAbsolutePath());
        sfr.readDocsFromComments();
        ruleDocEntries.addAll(sfr.getRuleDocEntries());
        if (attributeDocEntries != null) {
          // Collect all attribute documentations from this file.
          attributeDocEntries.putAll(sfr.getAttributeDocEntries());
        }
      }
    } else if (inputPath.isDirectory()) {
      for (File childPath : inputPath.listFiles()) {
        collectDocs(ruleDocEntries, attributeDocEntries, childPath);
      }
    }
  }

  private File setupDirectories(String outputRootDir) {
    if (outputRootDir != null) {
      File outputRootPath = new File(outputRootDir);
      outputRootPath.mkdirs();
      return new File(outputRootDir + File.separator + DocgenConsts.BUILD_ENCYCLOPEDIA_NAME);
    } else {
      return new File(DocgenConsts.BUILD_ENCYCLOPEDIA_NAME);
    }
  }
}
