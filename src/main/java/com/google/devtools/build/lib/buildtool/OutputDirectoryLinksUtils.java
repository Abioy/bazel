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

import com.google.common.base.Joiner;
import com.google.devtools.build.lib.events.ErrorEventListener;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.view.config.BuildConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Static utilities for managing output directory symlinks.
 */
public class OutputDirectoryLinksUtils {
  public static final String OUTPUT_SYMLINK_NAME = "blaze-out";
  public static final String GOOGLE3_SYMLINK_NAME = "blaze-google3"; // points to getExecRoot().

  // Used in getPrettyPath() method below.
  private static final String[] LINKS = { "bin", "genfiles", "includes" };
  private static final String[] NO_PREFIX_LINKS = {
    OUTPUT_SYMLINK_NAME, GOOGLE3_SYMLINK_NAME, "." };

  private static final String NO_CREATE_SYMLINKS_PREFIX = "/";

  /**
   * Attempts to create convenience symlinks in the workspaceDirectory and in
   * execRoot to the output area and to the configuration-specific output
   * directories. Issues a warning if it fails, e.g. because workspaceDirectory
   * is readonly.
   */
  public static void createOutputDirectoryLinks(Path workspace, Path execRoot, Path outputPath,
      ErrorEventListener listener, BuildConfiguration targetConfig, String symlinkPrefix) {
    if (NO_CREATE_SYMLINKS_PREFIX.equals(symlinkPrefix)) {
      return;
    }
    List<String> failures = new ArrayList<>();

    // Make the two non-specific links from the workspace to the output area,
    // and the configuration-specific links in both the workspace and the execution root dirs.
    // NB!  Keep in sync with removeOutputDirectoryLinks below.
    createLink(workspace, OUTPUT_SYMLINK_NAME, outputPath, failures);
    createLink(workspace, GOOGLE3_SYMLINK_NAME, execRoot, failures);
    createLink(workspace, symlinkPrefix + "bin", targetConfig.getBinDirectory().getPath(),
        failures);
    createLink(workspace, symlinkPrefix + "testlogs", targetConfig.getTestLogsDirectory().getPath(),
        failures);
    createLink(workspace, symlinkPrefix + "genfiles", targetConfig.getGenfilesDirectory().getPath(),
        failures);
    if (!failures.isEmpty()) {
      listener.warn(null, String.format(
          "failed to create one or more convenience symlinks for prefix '%s':\n  %s",
          symlinkPrefix, Joiner.on("\n  ").join(failures)));
    }
  }

  /**
   * Returns a convenient path to the specified file, relativizing it and using output-dir symlinks
   * if possible.  Otherwise, return a path relative to the workspace directory if possible.
   * Otherwise, return the absolute path.
   *
   * <p>This method must be called after the symlinks are created at the end of a build. If called
   * before, the pretty path may be incorrect if the symlinks end up pointing somewhere new.
   */
  public static PathFragment getPrettyPath(Path file, Path workspaceDirectory,
      String symlinkPrefix) {
    for (String link : LINKS) {
      PathFragment result = relativize(file, workspaceDirectory, symlinkPrefix + link);
      if (result != null) {
        return result;
      }
    }
    for (String link : NO_PREFIX_LINKS) {
      PathFragment result = relativize(file, workspaceDirectory, link);
      if (result != null) {
        return result;
      }
    }
    return file.asFragment();
  }

  // Helper to getPrettyPath.  Returns file, relativized w.r.t. the referent of
  // "linkname", or null if it was a not a child.
  private static PathFragment relativize(Path file, Path workspaceDirectory, String linkname) {
    PathFragment link = new PathFragment(linkname);
    try {
      Path dir = workspaceDirectory.getRelative(link);
      PathFragment levelOneLinkTarget = dir.readSymbolicLink();
      if (levelOneLinkTarget.isAbsolute() &&
          file.startsWith(dir = file.getRelative(levelOneLinkTarget))) {
        return link.getRelative(file.relativeTo(dir));
      }
    } catch (IOException e) {
      /* ignore */
    }
    return null;
  }

  /**
   * Attempts to remove the convenience symlinks in the workspace directory.
   *
   * <p>Issues a warning if it fails, e.g. because workspaceDirectory is readonly.
   * Also cleans up any child directories created by a custom prefix.
   *
   * @param workspace the runtime's workspace
   * @param listener the error listener
   * @param symlinkPrefix the symlink prefix which should be removed
   */
  public static void removeOutputDirectoryLinks(Path workspace, ErrorEventListener listener,
      String symlinkPrefix) {
    if (NO_CREATE_SYMLINKS_PREFIX.equals(symlinkPrefix)) {
      return;
    }
    List<String> failures = new ArrayList<>();

    removeLink(workspace, OUTPUT_SYMLINK_NAME, failures);
    removeLink(workspace, GOOGLE3_SYMLINK_NAME, failures);
    removeLink(workspace, symlinkPrefix + "bin", failures);
    removeLink(workspace, symlinkPrefix + "testlogs", failures);
    removeLink(workspace, symlinkPrefix + "genfiles", failures);
    FileSystemUtils.removeDirectoryAndParents(workspace, new PathFragment(symlinkPrefix));
    if (!failures.isEmpty()) {
      listener.warn(null, String.format(
          "failed to remove one or more convenience symlinks for prefix '%s':\n  %s", symlinkPrefix,
          Joiner.on("\n  ").join(failures)));
    }
  }

  /**
   * Helper to createOutputDirectoryLinks that creates a symlink from base + name to target.
   */
  private static boolean createLink(Path base, String name, Path target, List<String> failures) {
    try {
      FileSystemUtils.ensureSymbolicLink(base.getRelative(name), target);
      return true;
    } catch (IOException e) {
      failures.add(String.format("%s -> %s:  %s", name, target.getPathString(), e.getMessage()));
      return false;
    }
  }

  /**
   * Helper to removeOutputDirectoryLinks that removes one of the Blaze convenience symbolic links.
   */
  private static boolean removeLink(Path base, String name, List<String> failures) {
    Path link = base.getRelative(name);
    try {
      if (link.exists(Symlinks.NOFOLLOW)) {
        ExecutionTool.LOG.finest("Removing " + link);
        link.delete();
      }
      return true;
    } catch (IOException e) {
      failures.add(String.format("%s: %s", name, e.getMessage()));
      return false;
    }
  }
}
