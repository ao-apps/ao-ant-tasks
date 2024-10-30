/*
 * ao-ant-tasks - Ant tasks used in building AO-supported projects.
 * Copyright (C) 2023, 2024  AO Industries, Inc.
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
import java.text.ParseException;
import java.time.DateTimeException;
import java.time.Instant;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.LogLevel;

/**
 * Ant task that invokes {@link ZipTimestampMerge#mergeDirectory(java.time.Instant, boolean, boolean, java.io.File, java.io.File)}.
 *
 * <p>Note: This task should be performed before {@link GenerateJavadocSitemapTask} in order to have correct timestamps
 * inside the generated sitemaps.</p>
 *
 * @author  AO Industries, Inc.
 */
@SuppressWarnings("CloneableImplementsClone")
public class ZipTimestampMergeTask extends Task {

  private Instant outputTimestamp;
  private boolean buildReproducible = true;
  private boolean requireLastBuild = true;
  private File lastBuildDirectory;
  private File buildDirectory;

  /**
   * The output timestamp used for entries that are found to be updated.
   * When {@link #setBuildReproducible(boolean) buildReproducible}, then value must match all the entries of the
   * AAR/JAR/WAR/ZIP files contained in {@link #setBuildDirectory(java.lang.String) buildDirectory}.
   */
  public void setOutputTimestamp(String outputTimestamp) throws DateTimeException {
    this.outputTimestamp = Instant.parse(outputTimestamp);
  }

  /**
   * When the build is reproducible (the default), all AAR/JAR/WAR/ZIP entries are verified to match
   * {@link #setOutputTimestamp(java.lang.String) outputTimestamp}.  When not flagged as reproducible, all entries
   * will be patched to be equal to {@link #setOutputTimestamp(java.lang.String) outputTimestamp}.
   */
  public void setBuildReproducible(boolean buildReproducible) {
    this.buildReproducible = buildReproducible;
  }

  /**
   * When requiring the last successful build (the default), all AAR/JAR/WAR/ZIP files in
   * {@link #setBuildDirectory(java.lang.String) buildDirectory} must have a one-for-one corresponding file in
   * {@link #setLastBuildDirectory(java.lang.String) lastBuildDirectory}.
   *
   * <p>Furthermore, the one-for-one mapping must be bi-directional: all AAR/JAR/WAR/ZIP files in
   * {@link #setLastBuildDirectory(java.lang.String) lastBuildDirectory} must have a corresponding file in
   * {@link #setBuildDirectory(java.lang.String) buildDirectory}.</p>
   *
   * <p>This is expected to be set to {@code false} for a first build only.  Subsequent builds should always have this at
   * the default {@code true}.  In there rare event the build removes or adds new artifacts, the build may need to be
   * manually launched with {@code requireLastBuild = false}.</p>
   */
  public void setRequireLastBuild(boolean requireLastBuild) {
    this.requireLastBuild = requireLastBuild;
  }

  /**
   * The directory that contains the artifacts of the last successful build.
   * Must exist when {@link #setRequireLastBuild(boolean) requireLastBuild} and be a directory.
   */
  public void setLastBuildDirectory(String lastBuildDirectory) {
    this.lastBuildDirectory = new File(lastBuildDirectory);
  }

  /**
   * The directory that contains the artifacts of the current build.
   * Must exist and be a directory.
   */
  public void setBuildDirectory(String buildDirectory) {
    this.buildDirectory = new File(buildDirectory);
  }

  /**
   * Calls {@link ZipTimestampMerge#mergeDirectory(java.time.Instant, boolean, boolean, java.io.File, java.io.File)}
   * while logging to {@link #log(java.lang.String, int)}.
   */
  @Override
  public void execute() throws BuildException {
    try {
      ZipTimestampMerge.mergeDirectory(
          outputTimestamp,
          buildReproducible,
          requireLastBuild,
          lastBuildDirectory,
          buildDirectory,
          msg -> log(msg.get(), LogLevel.DEBUG.getLevel()),
          msg -> log(msg.get(), LogLevel.INFO.getLevel()),
          msg -> log(msg.get(), LogLevel.WARN.getLevel())
      );
    } catch (IOException | ParseException e) {
      throw new BuildException(e);
    }
  }
}
