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
package com.google.devtools.build.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.skyframe.AutoUpdatingGraph.NodeProgressReceiver;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * A visitor that is useful for invalidating transitive dependencies of Skyframe nodes.
 *
 * <p>Interruptibility: It is safe to interrupt the invalidation process at any time. Consider a
 * graph and a set of modified nodes. Then the reverse transitive closure of the modified nodes is
 * the set of dirty nodes. We provide interruptibility by making sure that the following invariant
 * holds at any time:
 *
 * <p>If a node is dirty, but not removed (or marked as dirty) yet, then either it or any of its
 * transitive dependencies must be in the {@link #pendingVisitations} set. Furthermore, reverse dep
 * pointers must always point to existing nodes.
 *
 * <p>Thread-safety: This class should only be instantiated and called on a single thread, but
 * internally it spawns many worker threads to process the graph. The thread-safety of the workers
 * on the graph can be delicate, and is documented below. Moreover, no other modifications to the
 * graph can take place while invalidation occurs.
 */
abstract class InvalidatingNodeVisitor extends AbstractQueueVisitor {

  // Default thread count is equal to the number of cores to exploit
  // that level of hardware parallelism, since invalidation should be CPU-bound. 
  // We may consider increasing this in the future.
  private static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors();

  protected final DirtiableGraph graph;
  @Nullable protected final NodeProgressReceiver invalidationReceiver;
  // Aliased to InvalidationState.pendingVisitations.
  protected final Set<Pair<NodeKey, InvalidationType>> pendingVisitations;

  protected InvalidatingNodeVisitor(
      DirtiableGraph graph, @Nullable NodeProgressReceiver invalidationReceiver,
      InvalidationState state) {
    super(/*concurrent*/true,
        /*corePoolSize*/DEFAULT_THREAD_COUNT,
        /*maxPoolSize*/DEFAULT_THREAD_COUNT,
        1, TimeUnit.SECONDS,
        /*failFastOnException*/true,
        /*failFastOnInterrupt*/true,
        "skyframe-invalidator");
    this.graph = Preconditions.checkNotNull(graph);
    this.invalidationReceiver = invalidationReceiver;
    this.pendingVisitations = state.pendingNodes;
  }

  /**
   * Initiates visitation and waits for completion.
   */
  void run() throws InterruptedException {
    // Make a copy to avoid concurrent modification confusing us as to which nodes were passed by
    // the caller, and which are added by other threads during the run. Since no tasks have been
    // started yet (the queueDirtying calls start them), this is thread-safe.
    for (Pair<NodeKey, InvalidationType> visitData : ImmutableList.copyOf(pendingVisitations)) {
      visit(visitData.first, visitData.second);
    }
    work(/*failFastOnInterrupt=*/true);
    Preconditions.checkState(pendingVisitations.isEmpty(),
        "All dirty nodes should have been processed: %s", pendingVisitations);
  }
  
  protected void informInvalidationReceiver(Node node, 
      NodeProgressReceiver.InvalidationState state) {
    if (invalidationReceiver != null && node != null) {
      invalidationReceiver.invalidated(node, state);
    }
  }

  /**
   * Enqueues a node for invalidation.
   */
  @ThreadSafe
  abstract void visit(NodeKey key, InvalidationType second);

  @VisibleForTesting
  enum InvalidationType {
    /**
     * The node is dirty and must be re-run.
     */
    CHANGED,
    /**
     * The node is dirty, but may be marked clean later during change pruning.
     */
    DIRTIED,
    /**
     * The node is deleted.
     */
    DELETED;
  }

  /**
   * Invalidation state object that keeps track of which nodes need to be invalidated, but have not
   * been dirtied/deleted yet. This supports interrupts - by only deleting a node from this set when
   * all its parents have been invalidated, we ensure that no information is lost when an interrupt
   * comes in.
   */
  static class InvalidationState {
    private final Set<Pair<NodeKey, InvalidationType>> pendingNodes = Sets.newConcurrentHashSet();
    private final InvalidationType defaultUpdateType;

    private InvalidationState(InvalidationType defaultUpdateType) {
      this.defaultUpdateType = Preconditions.checkNotNull(defaultUpdateType);
    }

    void update(Iterable<NodeKey> diff) {
      Iterables.addAll(pendingNodes, Iterables.transform(diff,
          new Function<NodeKey, Pair<NodeKey, InvalidationType>>() {
            @Override
            public Pair<NodeKey, InvalidationType> apply(NodeKey nodeKey) {
              return Pair.of(nodeKey, defaultUpdateType);
            }
          }));
    }

    @VisibleForTesting
    boolean isEmpty() {
      return pendingNodes.isEmpty();
    }

    @VisibleForTesting
    Set<Pair<NodeKey, InvalidationType>> getInvalidationsForTesting() {
      return ImmutableSet.copyOf(pendingNodes);
    }
  }

  static class DirtyingInvalidationState extends InvalidationState {
    public DirtyingInvalidationState() {
      super(InvalidationType.CHANGED);
    }
  }

  static class DeletingInvalidationState extends InvalidationState {
    public DeletingInvalidationState() {
      super(InvalidationType.DELETED);
    }
  }

  /**
   * A node-deleting implementation.
   */
  static class DeletingNodeVisitor extends InvalidatingNodeVisitor {

    private final Set<NodeKey> visitedNodes = Sets.newConcurrentHashSet();

    protected DeletingNodeVisitor(DirtiableGraph graph, NodeProgressReceiver invalidationReceiver,
        InvalidationState state) {
      super(graph, invalidationReceiver, state);
    }

    @Override
    public void visit(final NodeKey key, InvalidationType invalidationType) {
      Preconditions.checkState(invalidationType == InvalidationType.DELETED, key);
      if (!visitedNodes.add(key)) {
        return;
      }
      final Pair<NodeKey, InvalidationType> invalidationPair = Pair.of(key, invalidationType);
      pendingVisitations.add(invalidationPair);
      enqueue(new Runnable() {
        @Override
        public void run() {
          NodeEntry entry = graph.get(key);
          if (entry == null) {
            pendingVisitations.remove(invalidationPair);
            return;
          }

          // Propagate deletion upwards.
          for (NodeKey reverseDep : entry.getReverseDeps()) {
            visit(reverseDep, InvalidationType.DELETED);
          }

          if (entry.isDone()) {
            // Only process this node's value and children if it is done, since dirty nodes have no
            // awareness of either.

            // Unregister this node from direct deps, since reverse dep edges cannot point to
            // non-existent nodes.
            for (NodeKey directDep : entry.getDirectDeps()) {
              NodeEntry dep = graph.get(directDep);
              if (dep != null) {
                dep.removeReverseDep(key);
              }
            }
            // Allow custom Node-specific logic to update dirtiness status.
            informInvalidationReceiver(entry.getNode(), 
                NodeProgressReceiver.InvalidationState.DELETED);
          }
          // Force reverseDeps consolidation (validates that attempts to remove reverse deps were
          // really successful.
          entry.getReverseDeps();
          // Actually remove the node.
          graph.remove(key);

          // Remove the node from the set as the last operation.
          pendingVisitations.remove(invalidationPair);
        }
      });
    }
  }

  /**
   * A node-dirtying implementation.
   */
  static class DirtyingNodeVisitor extends InvalidatingNodeVisitor {

    private final Set<Pair<NodeKey, InvalidationType>> visited = Sets.newConcurrentHashSet();

    protected DirtyingNodeVisitor(DirtiableGraph graph, NodeProgressReceiver invalidationReceiver,
        InvalidationState state) {
      super(graph, invalidationReceiver, state);
    }

    /**
     * Queues a task to dirty the node named by {@code key}. May be called from multiple threads.
     * It is possible that the same node is enqueued many times. However, we require that a node is
     * only actually marked dirty/changed once, with two exceptions:
     *
     * (1) If a node is marked dirty, it can subsequently be marked changed. This can occur if, for
     * instance, FileNode workspace/foo/foo.cc is marked dirty because FileNode workspace/foo is
     * marked changed (and every FileNode depends on its parent). Then FileNode workspace/foo/foo.cc
     * is itself changed (this can even happen on the same build).
     *
     * (2) If a node is going to be marked both dirty and changed, as, for example, in the previous
     * case if both workspace/foo/foo.cc and workspace/foo have been changed in the same build, the
     * thread marking workspace/foo/foo.cc dirty may race with the one marking it changed, and so
     * try to mark it dirty after it has already been marked changed. In that case, the
     * {@link NodeEntry} ignores the second marking.
     *
     * The invariant that we do not process a (NodeKey, InvalidationType) pair twice is enforced by
     * the {@link #visited} set.
     *
     * The "invariant" is also enforced across builds by checking to see if the entry is already
     * marked changed, or if it is already marked dirty and we are just going to mark it dirty
     * again.
     *
     * If either of the above tests shows that we have already started a task to mark this entry
     * dirty/changed, or that it is already marked dirty/changed, we do not continue this task.
     */
    @Override
    @ThreadSafe
    public void visit(final NodeKey key, final InvalidationType invalidationType) {
      Preconditions.checkState(invalidationType != InvalidationType.DELETED, key);
      final boolean isChanged = (invalidationType == InvalidationType.CHANGED);
      final Pair<NodeKey, InvalidationType> invalidationPair = Pair.of(key, invalidationType);
      if (!visited.add(invalidationPair)) {
        return;
      }
      pendingVisitations.add(invalidationPair);
      enqueue(new Runnable() {
        @Override
        public void run() {
          NodeEntry entry = graph.get(key);

          if (entry == null) {
            // Currently, the only way for an entry to not exist is if the caller requested
            // invalidation of a non-existent node. Since all caller-specified nodes are isChanged,
            // we check for that. Nodes that depend on the error transience node can also be
            // marked changed, so this fail-fast check is not perfectly tight.
            Preconditions.checkState(isChanged,
                "%s does not exist in the graph but was enqueued for dirtying by another node",
                key);
            pendingVisitations.remove(invalidationPair);
            return;
          }

          if (entry.isChanged() || (!isChanged && entry.isDirty())) {
            // If this node is already marked changed, or we are only marking this node dirty, and
            // it already is, move along.
            pendingVisitations.remove(invalidationPair);
            return;
          }

          // This entry remains in the graph in this dirty state until it is re-evaluated.
          Pair<Iterable<NodeKey>, ? extends Node> depsAndValue = entry.markDirty(isChanged);
          // It is not safe to interrupt the logic from this point until the end of the method.
          // Any exception thrown should be unrecoverable.
          if (depsAndValue == null) {
            // Another thread has already dirtied this node. Don't do anything in this thread.
            pendingVisitations.remove(invalidationPair);
            return;
          }
          // Propagate dirtiness upwards and mark this node dirty/changed. Reverse deps should only
          // be marked dirty (because only a dependency of theirs has changed) unless this node is
          // the error transience node, in which case the caller wants any node in error to be
          // re-evaluated unconditionally, hence those error nodes should be marked changed.
          for (NodeKey reverseDep : entry.getReverseDeps()) {
            visit(reverseDep, key.equals(ErrorTransienceNode.key())
                ? InvalidationType.CHANGED
                : InvalidationType.DIRTIED);
          }

          // Remove this node as a reverse dep from its children, since we have reset it and it no
          // longer lists its children as direct deps.
          for (NodeKey dep : depsAndValue.first) {
            graph.get(dep).removeReverseDep(key);
          }

          Node node = NodeWithMetadata.justNode(depsAndValue.second);
          informInvalidationReceiver(node, NodeProgressReceiver.InvalidationState.DIRTY);
          // Remove the node from the set as the last operation.
          pendingVisitations.remove(invalidationPair);
        }
      });
    }
  }
}
