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
package com.google.devtools.build.lib.buildtool;

/**
 * Event that is raised when the action and artifact metadata caches are saved at the end of the
 * build. Contains statistics.
 */
public class CachesSavedEvent {
  /** Cache serialization statistics. */
  private final long metadataCacheSaveTimeInMillis;
  private final long metadataCacheSizeInBytes;
  private final long actionCacheSaveTimeInMillis;
  private final long actionCacheSizeInBytes;

  public CachesSavedEvent(
      long metadataCacheSaveTimeInMillis,
      long metadataCacheSizeInBytes,
      long actionCacheSaveTimeInMillis,
      long actionCacheSizeInBytes) {
    this.metadataCacheSaveTimeInMillis = metadataCacheSaveTimeInMillis;
    this.metadataCacheSizeInBytes = metadataCacheSizeInBytes;
    this.actionCacheSaveTimeInMillis = actionCacheSaveTimeInMillis;
    this.actionCacheSizeInBytes = actionCacheSizeInBytes;
  }

  public long getMetadataCacheSaveTimeInMillis() {
    return metadataCacheSaveTimeInMillis;
  }

  public long getMetadataCacheSizeInBytes() {
    return metadataCacheSizeInBytes;
  }

  public long getActionCacheSaveTimeInMillis() {
    return actionCacheSaveTimeInMillis;
  }

  public long getActionCacheSizeInBytes() {
    return actionCacheSizeInBytes;
  }
}
