/*
 * ao-ant-tasks - Ant tasks used in building AO-supported projects.
 * Copyright (C) 2023  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-ant-tasks.
 *
 * ao-ant-tasks is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-ant-tasks is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-ant-tasks.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoapps.ant.tasks;

import java.io.File;
import java.io.IOException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * Creates a ZIP file with a set of directories up to and including the given path.  The timestamps for the directories
 * will be taken from the provided ZIP file and path.
 * <p>
 * It is proving difficult to be able to introduce new directory into a ZIP file at build time in a reproducible way.
 * If we set set <code>&lt;zip modificationtime="…"&gt;</code> we replace all meaningful timestamps.
 * If we do not set it, newly added directories have current time.
 * Cannot find any way with mappings, since directory-only mappings seem to be always ignored.
 * </p>
 * <p>
 * Use this task to create a small ZIP file containing only the needed new directories, then includes this new ZIP
 * file as an additional <code>&lt;zipfileset /&gt;</code>.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
@SuppressWarnings("CloneableImplementsClone")
public class CreateZippedDirectoriesTask extends Task {

  private File referenceZip;
  private String referencePath;
  private File generateZip;
  private String generatePath;

  /**
   * The ZIP file that will be referenced to get the timestamp.
   */
  public void setReferenceZip(String referenceZip) {
    this.referenceZip = new File(referenceZip);
  }

  /**
   * The path within the ZIP file that will be referenced to get the timestamp.
   */
  public void setReferencePath(String referencePath) {
    this.referencePath = referencePath;
  }

  /**
   * The ZIP file that will be generated containing the given path and all parents, each with timestamps matching
   * the reference.
   */
  public void setGenerateZip(String generateZip) {
    this.generateZip = new File(generateZip);
  }

  /**
   * The path to generate in the ZIP file, including all parents, each with timestamps matching
   * the reference.
   */
  public void setGeneratePath(String generatePath) {
    while (generatePath.startsWith("/")) {
      generatePath = generatePath.substring(1);
    }
    while (generatePath.contains("//")) {
      generatePath = generatePath.replace("//", "/");
    }
    if (!generatePath.isEmpty() && !generatePath.endsWith("/")) {
      generatePath += "/";
    }
    this.generatePath = generatePath;
  }

  @Override
  public void execute() throws BuildException {
    if (referenceZip == null) {
      throw new BuildException("referenceZip required");
    }
    if (referencePath == null) {
      throw new BuildException("referencePath required");
    }
    if (generateZip == null) {
      throw new BuildException("generateZip required");
    }
    if (generatePath == null) {
      throw new BuildException("generatePath required");
    }
    if (generatePath.isEmpty()) {
      throw new BuildException("generatePath empty");
    }
    if (!referenceZip.exists()) {
      throw new BuildException("referenceZip does not exist: " + referenceZip);
    }
    if (!referenceZip.isFile()) {
      throw new BuildException("referenceZip is not a regular file: " + referenceZip);
    }
    if (generateZip.exists() && !generateZip.isFile()) {
      throw new BuildException("generateZip exists and is not a regular file: " + generateZip);
    }
    try {
      // Only getting and copying - no need for any UTC timestamp conversions
      long referenceTime;
      try (ZipFile referenceZipFile = new ZipFile(referenceZip)) {
        ZipArchiveEntry referenceEntry = referenceZipFile.getEntry(referencePath);
        if (referenceEntry == null) {
          throw new BuildException("reference entry not found: " + referenceZip + " @ " + referencePath);
        }
        referenceTime = referenceEntry.getTime();
        if (referenceTime == -1) {
          throw new BuildException("reference entry does not have any timestamp: " + referenceZip + " @ " + referencePath);
        }
      }
      FileUtils.forceMkdirParent(generateZip);
      try (ZipArchiveOutputStream generatedZipOut = new ZipArchiveOutputStream(generateZip)) {
        assert !generatePath.startsWith("/");
        assert !generatePath.contains("//");
        assert generatePath.endsWith("/");
        int slashPos = -1;
        while (slashPos < (generatePath.length() - 1)) {
          slashPos = generatePath.indexOf('/', slashPos + 1);
          assert slashPos != -1;
          ZipArchiveEntry newEntry = new ZipArchiveEntry(generatePath.substring(0, slashPos + 1));
          newEntry.setTime(referenceTime);
          generatedZipOut.putArchiveEntry(newEntry);
          generatedZipOut.closeArchiveEntry();
        }
      }
    } catch (IOException e) {
      throw new BuildException(e);
    }
  }
}
