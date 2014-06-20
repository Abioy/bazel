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
package com.google.devtools.build.lib.collect.nestedset;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An empty nested set.
 */
final class EmptyNestedSet<E> implements NestedSet<E> {

  private final Order order;

  EmptyNestedSet(Order type) {
    this.order = type;
  }

  @Override
  public Iterator<E> iterator() {
    return ImmutableList.<E>of().iterator();
  }

  @Override
  public ImmutableList<E> directMembers() {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<NestedSet<E>> transitiveSets() {
    return ImmutableList.of();
  }

  @Override
  public boolean isEmpty() {
    return true;
  }

  @Override
  public Collection<E> toCollection() {
    return ImmutableList.of();
  }

  @Override
  public List<E> toList() {
    return ImmutableList.of();
  }

  @Override
  public Set<E> toSet() {
    return ImmutableSet.of();
  }

  @Override
  public String toString() {
    return "{}";
  }

  @Override
  public Order getOrder() {
    return order;
  }

  @Override
  public boolean shallowEquals(@Nullable NestedSet<? extends E> other) {
    return other != null && getOrder() == other.getOrder() && other.isEmpty();
  }

  @Override
  public int shallowHashCode() {
    return Objects.hash(getOrder());
  }
}
