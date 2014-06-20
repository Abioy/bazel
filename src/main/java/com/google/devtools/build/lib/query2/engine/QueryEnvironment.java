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
package com.google.devtools.build.lib.query2.engine;

import com.google.devtools.build.lib.graph.Node;

import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * The environment of a Blaze query. Implementations do not need to be thread-safe. The generic type
 * T represents a node of the graph on which the query runs; as such, there is no restriction on T.
 * However, query assumes a certain graph model, and the {@link TargetAccessor} class is used to
 * access properties of these nodes.
 *
 * @param <T> the node type of the dependency graph
 */
public interface QueryEnvironment<T> {
  /**
   * Type of an argument of a user-defined query function.
   */
  public enum ArgumentType {
    EXPRESSION, WORD, INTEGER;
  }

  /**
   * Value of an argument of a user-defined query function.
   */
  public static class Argument {
    private final ArgumentType type;
    private final QueryExpression expression;
    private final String word;
    private final int integer;

    private Argument(ArgumentType type, QueryExpression expression, String word, int integer) {
      this.type = type;
      this.expression = expression;
      this.word = word;
      this.integer = integer;
    }

    static Argument of(QueryExpression expression) {
      return new Argument(ArgumentType.EXPRESSION, expression, null, 0);
    }

    static Argument of(String word) {
      return new Argument(ArgumentType.WORD, null, word, 0);
    }

    static Argument of(int integer) {
      return new Argument(ArgumentType.INTEGER, null, null, integer);
    }

    public ArgumentType getType() {
      return type;
    }

    public QueryExpression getExpression() {
      return expression;
    }

    public String getWord() {
      return word;
    }

    public int getInteger() {
      return integer;
    }

    @Override
    public String toString() {
      switch (type) {
        case WORD: return "'" + word + "'";
        case EXPRESSION: return expression.toString();
        case INTEGER: return Integer.toString(integer);
        default: throw new IllegalStateException();
      }
    }
  }

  /**
   * A user-defined query function.
   */
  public interface QueryFunction {
    /**
     * Name of the function as it appears in the query language.
     */
    String getName();

    /**
     * The number of arguments that are required. The rest is optional.
     *
     * <p>This should be greater than or equal to zero and at smaller than or equal to the length
     * of the list returned by {@link #getArgumentTypes}.
     */
    int getMandatoryArguments();

    /**
     * The types of the arguments of the function.
     */
    List<ArgumentType> getArgumentTypes();

    /**
     * Called when a user-defined function is to be evaluated.
     *
     * @param env the query environment this function is evaluated in.
     * @param expression the expression being evaluated.
     * @param args the input arguments. These are type-checked against the specification returned
     *     by {@link #getArgumentTypes} and {@link #getMandatoryArguments}
     */
    <T> Set<Node<T>> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
        throws QueryException;
  }

  /**
   * Exception type for the case where a target cannot be found. It's basically a wrapper for
   * whatever exception is internally thrown.
   */
  public static final class TargetNotFoundException extends Exception {
    public TargetNotFoundException(String msg) {
      super(msg);
    }

    public TargetNotFoundException(Throwable cause) {
      super(cause.getMessage(), cause);
    }
  }

  /**
   * Returns the set of target nodes in the graph for the specified target
   * pattern, in 'blaze build' syntax.
   */
  Set<Node<T>> getTargetsMatchingPattern(QueryExpression owner, String pattern)
      throws QueryException;

  /**
   * Returns the graph node for the specified target, creating a new one if not
   * found.
   */
  Node<T> getNode(T target);

  /**
   * Returns the forward transitive closure of all of the nodes in
   * "targetNodes".  Callers must ensure that {@link #buildTransitiveClosure}
   * has been called for the relevant subgraph.
   */
  Set<Node<T>> getTransitiveClosure(Set<Node<T>> targetNodes);

  /**
   * Construct the dependency graph for a depth-bounded forward transitive closure
   * of all nodes in "targetNodes".  The identity of the calling expression is
   * required to produce error messages.
   *
   * <p>If a larger transitive closure was already built, returns it to
   * improve incrementality, since all depth-constrained methods filter it
   * after it is built anyway.
   */
  void buildTransitiveClosure(QueryExpression caller,
                              Set<Node<T>> targetNodes,
                              int maxDepth) throws QueryException;

  /**
   * Returns the set of nodes on some path from "from" to "to".
   */
  Set<Node<T>> getNodesOnPath(Node<T> from, Node<T> to);

  /**
   * Returns the value of the specified variable, or null if it is undefined.
   */
  Set<Node<T>> getVariable(String name);

  /**
   * Sets the value of the specified variable.  If value is null the variable
   * becomes undefined.  Returns the previous value, if any.
   */
  Set<Node<T>> setVariable(String name, Set<Node<T>> value);

  void reportBuildFileError(QueryExpression expression, String msg) throws QueryException;

  /**
   * Returns the set of BUILD, included, and sub-included files that define the given set of
   * targets. Each such file is itself represented as a target in the result.
   */
  Set<Node<T>> getBuildFiles(QueryExpression caller, Set<Node<T>> nodes) throws QueryException;

  /**
   * Returns an object that can be used to query information about targets. Implementations should
   * create a single instance and return that for all calls. A class can implement both {@code
   * QueryEnvironment} and {@code TargetAccessor} at the same time, in which case this method simply
   * returns {@code this}.
   */
  TargetAccessor<T> getAccessor();

  /**
   * Whether the given setting is enabled. The code should default to return {@code false} for all
   * unknown settings. The enum is used rather than a method for each setting so that adding more
   * settings is backwards-compatible.
   *
   * @throws NullPointerException if setting is null
   */
  boolean isSettingEnabled(@Nonnull Setting setting);

  /**
   * Returns the set of query functions implemented by this query environment.
   */
  Iterable<QueryFunction> getFunctions();

  /**
   * Settings for the query engine. See {@link QueryEnvironment#isSettingEnabled}.
   */
  public static enum Setting {

    /**
     * Whether to evaluate tests() expressions in strict mode. If {@link #isSettingEnabled} returns
     * true for this setting, then the tests() expression will give an error when expanding tests
     * suites, if the test suite contains any non-test targets.
     */
    TESTS_EXPRESSION_STRICT,

    /**
     * Do not consider implicit deps (any label that was not explicitly specified in the BUILD file)
     * when traversing dependency edges.
     */
    NO_IMPLICIT_DEPS,

    /**
     * Do not consider host dependencies when traversing dependency edges.
     */
    NO_HOST_DEPS,

    /**
     * Do not consider nodep attributes when traversing dependency edges.
     */
    NO_NODEP_DEPS;
  }

  /**
   * An adapter interface giving access to properties of T. There are four types of targets: rules,
   * package groups, source files, and generated files. Of these, only rules can have attributes.
   */
  public static interface TargetAccessor<T> {
    /**
     * Returns the target type represented as a string of the form {@code &lt;type&gt; rule} or
     * {@code package group} or {@code source file} or {@code generated file}. This is widely used
     * for target filtering, so implementations must use the Blaze rule class naming scheme.
     */
    String getTargetKind(T target);

    /**
     * Returns the full label of the target as a string, e.g. {@code //some:target}.
     */
    String getLabel(T target);

    /**
     * Returns whether the given target is a rule.
     */
    boolean isRule(T target);

    /**
     * Returns whether the given target is a test target. If this returns true, then {@link #isRule}
     * must also return true for the target.
     */
    boolean isTestRule(T target);

    /**
     * Returns whether the given target is a test suite target. If this returns true, then {@link
     * #isRule} must also return true for the target, but {@link #isTestRule} must return false;
     * test suites are not test rules, and vice versa.
     */
    boolean isTestSuite(T target);

    /**
     * If the attribute of the given name on the given target is a label or label list, then this
     * method returns the list of corresponding target instances. Otherwise returns an empty list.
     * If an error occurs during resolution, it throws a {@link QueryException} using the caller and
     * error message prefix.
     *
     * @throws IllegalArgumentException if target is not a rule (according to {@link #isRule})
     */
    List<T> getLabelListAttr(QueryExpression caller, T target, String attrName,
        String errorMsgPrefix) throws QueryException;

    /**
     * If the attribute of the given name on the given target is a string list, then this method
     * returns it.
     *
     * @throws IllegalArgumentException if target is not a rule (according to {@link #isRule}), or
     *                                  if the target does not have an attribute of type string list
     *                                  with the given name
     */
    List<String> getStringListAttr(T target, String attrName);

    /**
     * If the attribute of the given name on the given target is a string, then this method returns
     * it.
     *
     * @throws IllegalArgumentException if target is not a rule (according to {@link #isRule}), or
     *                                  if the target does not have an attribute of type string with
     *                                  the given name
     */
    String getStringAttr(T target, String attrName);

    /**
     * Returns the given attribute represented as a string. Note that for backwards compatibility,
     * tristate and boolean attributes are returned as int using the values {@code 0, 1} and {@code
     * -1}. If there is no such attribute, this method returns {@code null} instead.
     *
     * @throws IllegalArgumentException if target is not a rule (according to {@link #isRule})
     */
    String getAttrAsString(T target, String attrName);
  }
}
