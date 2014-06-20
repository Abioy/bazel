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
package com.google.devtools.build.lib.actions;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action.MiddlemanType;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.Digest;
import com.google.devtools.build.lib.actions.cache.Metadata;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.pkgcache.PackageUpToDateChecker;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Checks whether an {@link Action} needs to be executed, or whether it has not changed since it was
 * last stored in the action cache. Must be informed of the new Action data after execution as well.
 *
 * <p>The fingerprint, input files names, and metadata (either mtimes or MD5sums) of each action are
 * cached in the action cache to avoid unnecessary rebuilds. Middleman artifacts are handled
 * specially, avoiding the need to create actual files corresponding to the middleman artifacts.
 * Instead of that, results of MiddlemanAction dependency checks are cached internally and then
 * reused whenever an input middleman artifact is encountered.
 *
 * <p>While instances of this class hold references to action and metadata cache instances, they are
 * otherwise lightweight, and should be constructed anew and discarded for each build request.
 */
public class ActionCacheChecker {
  private final ActionCache actionCache;
  private final Predicate<? super Action> executionFilter;
  private final ArtifactResolver artifactResolver;
  private final PackageUpToDateChecker packageUpToDateChecker;

  // True iff --verbose_explanations flag is set.
  private final boolean verboseExplanations;

  public ActionCacheChecker(ActionCache actionCache, ArtifactResolver artifactResolver,
      PackageUpToDateChecker packageUpToDateChecker, Predicate<? super Action> executionFilter,
      boolean verboseExplanations) {
    this.actionCache = actionCache;
    this.executionFilter = executionFilter;
    this.artifactResolver = artifactResolver;
    this.packageUpToDateChecker = packageUpToDateChecker;
    this.verboseExplanations = verboseExplanations;
  }

  public boolean isActionExecutionProhibited(Action action) {
    return !executionFilter.apply(action);
  }

  /**
   * Checks whether one of existing output paths is already used as a key.
   * If yes, returns it - otherwise uses first output file as a key
   */
  private ActionCache.Entry getCacheEntry(Action action) {
    for (Artifact output : action.getOutputs()) {
      ActionCache.Entry entry = actionCache.get(output.getExecPathString());
      if (entry != null) {
        return entry;
      }
    }
    return null;
  }

  /**
   * Validate metadata state for action input or output artifacts.
   *
   * @param entry cached action information.
   * @param action action to be validated.
   * @param metadataHandler provider of metadata for the artifacts this action interacts with.
   * @param checkOutput true to validate output artifacts, Otherwise, just
   *                    validate inputs.
   *
   * @return true if at least one artifact has changed, false - otherwise.
   */
  private boolean validateArtifacts(ActionCache.Entry entry, Action action,
      MetadataHandler metadataHandler, boolean checkOutput) {
    Iterable<Artifact> artifacts = checkOutput
        ? Iterables.concat(action.getOutputs(), action.getInputs())
        : action.getInputs();
    Map<String, Metadata> mdMap = new HashMap<>();
    for (Artifact artifact : artifacts) {
      mdMap.put(artifact.getExecPathString(), metadataHandler.getMetadataMaybe(artifact));
    }
    return !Digest.fromMetadata(mdMap).equals(entry.getFileDigest());
  }

  private void reportCommand(DepcheckerListener listener, Action action) {
    if (listener != null) {
      if (verboseExplanations) {
        String keyDescription = action.describeKey();
        reportRebuild(listener, action,
            keyDescription == null ? "action command has changed" :
            "action command has changed.\nNew action: " + keyDescription);
      } else {
        reportRebuild(listener, action,
            "action command has changed (try --verbose_explanations for more info)");
      }
    }
  }

  protected boolean unconditionalExecution(Action action) {
    // TODO(bazel-team): Remove PackageUpToDateChecker.
    return !isActionExecutionProhibited(action)
        && action.executeUnconditionally(packageUpToDateChecker);
  }

  /**
   * Checks whether {@link action} needs to be executed, by seeing if any of its inputs or outputs
   * have changed. Returns a non-null {@link Token} if the action needs to be executed, and null
   * otherwise.
   *
   * <p>If this method returns non-null, indicating that the action will be executed, the
   * metadataHandler's {@link MetadataHandler#discardMetadata} method must be called, so that it
   * does not serve stale metadata for the action's outputs after the action is executed.
   */
  // Note: the listener should only be used for DEPCHECKER events; there's no
  // guarantee it will be available for other events.
  public Token needToExecute(Action action, DepcheckerListener listener,
      MetadataHandler metadataHandler) {
    // TODO(bazel-team): (2010) For RunfilesAction/SymlinkAction and similar actions that
    // produce only symlinks we should not check whether inputs are valid at all - all that matters
    // that inputs and outputs are still exist (and new inputs have not appeared). All other checks
    // are unnecessary. In other words, the only metadata we should check for them is file existence
    // itself.

    MiddlemanType middlemanType = action.getActionType();
    if (middlemanType.isMiddleman()) {
      // Some types of middlemen are not checked because they should not
      // propagate invalidation of their inputs.
      if (middlemanType != MiddlemanType.SCHEDULING_MIDDLEMAN &&
          middlemanType != MiddlemanType.TARGET_COMPLETION_MIDDLEMAN &&
          middlemanType != MiddlemanType.ERROR_PROPAGATING_MIDDLEMAN) {
        checkMiddlemanAction(action, listener, metadataHandler);
      }
      if (middlemanType != MiddlemanType.TARGET_COMPLETION_MIDDLEMAN) {
        return null; // Only target completion middlemen are executed by the builder.
      }
    }
    ActionCache.Entry entry = null; // Populated lazily.

    // Update action inputs from cache, if necessary.
    boolean inputsKnown = action.inputsKnown();
    if (!inputsKnown) {
      Preconditions.checkState(action.discoversInputs());
      entry = getCacheEntry(action);
      updateActionInputs(action, entry);
    }
    if (mustExecute(action, entry, listener, metadataHandler)) {
      return new Token(getKeyString(action));
    }
    return null;
  }

  protected boolean mustExecute(Action action, @Nullable ActionCache.Entry entry,
      DepcheckerListener listener, MetadataHandler metadataHandler) {
    // Unconditional execution can be applied only for actions that are allowed to be executed.
    if (unconditionalExecution(action)) {
      Preconditions.checkState(action.isVolatile());
      reportUnconditionalExecution(listener, action);
      return true; // must execute - unconditional execution is requested.
    }

    if (entry == null) {
      entry = getCacheEntry(action);
    }
    if (entry == null) {
      reportNewAction(listener, action);
      return true; // must execute -- no cache entry (e.g. first build)
    }

    if (entry.isCorrupted()) {
      reportCorruptedCacheEntry(listener, action);
      return true; // cache entry is corrupted - must execute
    } else if (validateArtifacts(entry, action, metadataHandler, true)) {
      reportChanged(listener, action);
      return true; // files have changed
    } else if (!entry.getActionKey().equals(action.getKey())){
      reportCommand(listener, action);
      return true; // must execute -- action key is different
    }

    entry.ensurePacked();
    return false; // cache hit
  }

  public void afterExecution(Action action, Token token, MetadataHandler metadataHandler)
      throws IOException {
    Preconditions.checkArgument(token != null);
    String key = token.cacheKey;
    ActionCache.Entry entry = actionCache.createEntry(action.getKey());
    for (Artifact output : action.getOutputs()) {
      // Remove old records from the cache if they used different key.
      String execPath = output.getExecPathString();
      if (!key.equals(execPath)) {
        actionCache.remove(key);
      }
      // Output files *must* exist and be accessible after successful action execution.
      Metadata metadata = metadataHandler.getMetadata(output);
      Preconditions.checkState(metadata != null);
      entry.addFile(output.getExecPath(), metadata);
    }
    for (Artifact input : action.getInputs()) {
      entry.addFile(input.getExecPath(), metadataHandler.getMetadataMaybe(input));
    }
    entry.ensurePacked();
    actionCache.put(key, entry);
  }

  protected boolean updateActionInputs(Action action, ActionCache.Entry entry) {
    if (entry == null || entry.isCorrupted()) {
      return false;
    }
    List<PathFragment> outputs = new ArrayList<>();
    for (Artifact output : action.getOutputs()) {
      outputs.add(output.getExecPath());
    }
    List<PathFragment> inputs = new ArrayList<>();
    for (String path : entry.getPaths()) {
      PathFragment execPath = new PathFragment(path);
      // Code assumes that action has only 1-2 outputs and ArrayList.contains() will be
      // most efficient.
      if (!outputs.contains(execPath)) {
        inputs.add(execPath);
      }
    }
    action.updateInputsFromCache(artifactResolver, inputs);
    return true;
  }

  /**
   * Special handling for the MiddlemanAction. Since MiddlemanAction output
   * artifacts are purely fictional and used only to stay within dependency
   * graph model limitations (action has to depend on artifacts, not on other
   * actions), we do not need to validate metadata for the outputs - only for
   * inputs. We also do not need to validate MiddlemanAction key, since action
   * cache entry key already incorporates that information for the middlemen
   * and we will experience a cache miss when it is different. Whenever it
   * encounters middleman artifacts as input artifacts for other actions, it
   * consults with the aggregated middleman digest computed here.
   */
  protected void checkMiddlemanAction(Action action, DepcheckerListener listener,
      MetadataHandler metadataHandler) {
    Artifact middleman = action.getPrimaryOutput();
    String cacheKey = middleman.getExecPathString();
    ActionCache.Entry entry = actionCache.get(cacheKey);
    boolean changed = false;
    if (entry != null) {
      if (entry.isCorrupted()) {
        reportCorruptedCacheEntry(listener, action);
        changed = true;
      } else if (validateArtifacts(entry, action, metadataHandler, false)) {
        reportChanged(listener, action);
        changed = true;
      }
    } else {
      reportChangedDeps(listener, action);
      changed = true;
    }
    if (changed) {
      // Compute the aggregated middleman digest.
      // Since we never validate action key for middlemen, we should not store
      // it in the cache entry and just use empty string instead.
      entry = actionCache.createEntry("");
      for (Artifact input : action.getInputs()) {
        entry.addFile(input.getExecPath(), metadataHandler.getMetadataMaybe(input));
      }
    }

    metadataHandler.setDigestForVirtualArtifact(middleman, entry.getFileDigest());
    entry.ensurePacked();
    if (changed) {
      actionCache.put(cacheKey, entry);
    }
  }

  /**
   * Returns an action key. It is always set to the first output exec path string.
   */
  private static String getKeyString(Action action) {
    Preconditions.checkState(!action.getOutputs().isEmpty());
    return action.getOutputs().iterator().next().getExecPathString();
  }


  /**
   * In most cases, this method should not be called directly - reportXXX() methods
   * should be used instead. This is done to avoid cost associated with building
   * the message.
   */
  private static void reportRebuild(DepcheckerListener listener, Action action, String message) {
    // For MiddlemanAction, do not report rebuild.
    if (!action.getActionType().isMiddleman()) {
      listener.depchecker("Executing " + action.prettyPrint() + ": " + message + ".");
    }
  }

  // Called by IncrementalDependencyChecker.
  protected static void reportUnconditionalExecution(DepcheckerListener listener, Action action) {
    if (listener != null) {
      reportRebuild(listener, action, "unconditional execution is requested");
    }
  }

  private static void reportChanged(DepcheckerListener listener, Action action) {
    if (listener != null) {
      reportRebuild(listener, action, "One of the files has changed");
    }
  }

  private static void reportChangedDeps(DepcheckerListener listener, Action action) {
    if (listener != null) {
      reportRebuild(listener, action,
          "the set of files on which this action depends has changed");
    }
  }

  private static void reportNewAction(DepcheckerListener listener, Action action) {
    if (listener != null) {
      reportRebuild(listener, action, "no entry in the cache (action is new)");
    }
  }

  private static void reportCorruptedCacheEntry(DepcheckerListener listener, Action action) {
    if (listener != null) {
      reportRebuild(listener, action, "cache entry is corrupted");
    }
  }

  /** Wrapper for all context needed by the ActionCacheChecker to handle a single action. */
  public static final class Token {
    private final String cacheKey;

    private Token(String cacheKey) {
      this.cacheKey = Preconditions.checkNotNull(cacheKey);
    }
  }

  /** An interface to report DEPCHECKER events to. */
  public static class DepcheckerListener {
    private final Reporter reporter;

    private DepcheckerListener(Reporter reporter) {
      this.reporter = Preconditions.checkNotNull(reporter);
    }

    /**
     * Reports a dependency-checking event, which explains why an Action is being rebuilt (or not).
     */
    public void depchecker(String message) {
      reporter.depchecker(message);
    }

    /** Returns a DepcheckerListener if the reporter handles DEPCHECKER events, null otherwise. */
    @Nullable public static DepcheckerListener createListenerMaybe(Reporter reporter) {
      Preconditions.checkNotNull(reporter);
      return reporter.hasHandlerFor(EventKind.DEPCHECKER)
          ? new DepcheckerListener(reporter)
      : null;
    }
  }
}
