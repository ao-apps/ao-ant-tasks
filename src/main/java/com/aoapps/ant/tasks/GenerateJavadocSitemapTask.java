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
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.LogLevel;

/**
 * Ant task that invokes {@link GenerateJavadocSitemap#addSitemapToJavadocJar(java.io.File, java.lang.String)}.
 *
 * @author  AO Industries, Inc.
 */
@SuppressWarnings("CloneableImplementsClone")
public class GenerateJavadocSitemapTask extends Task {

  private File buildDirectory;
  private String projectUrl;
  private String subprojectSubpath;

  /**
   * The current build directory.
   * Must exist and be a directory.
   *
   * <p>Each file ending with <code>"{@value SeoJavadocFilterTask#FILTER_SUFFIX}"</code> (case-insensitive) will be processed.</p>
   *
   * <p>Each file is a Javadoc JAR file to add sitemap to and must be a regular file.</p>
   */
  public void setBuildDirectory(String buildDirectory) {
    this.buildDirectory = new File(buildDirectory);
  }

  /**
   * The project url.  The apidocs URLs will be based on this, depending on artifact classifier.
   * Ending in {@code "*-test-javadoc.jar"} will be {@code "${projectUrl}${subprojectSubpath}test/apidocs/"}.
   * Otherwise will be {@code "${projectUrl}${subprojectSubpath}apidocs/"}
   *
   * @see GenerateJavadocSitemapTask#setSubprojectSubpath(java.lang.String)
   */
  public void setProjectUrl(String projectUrl) {
    if (!projectUrl.endsWith("/")) {
      projectUrl += "/";
    }
    this.projectUrl = projectUrl;
  }

  /**
   * The sub-project sub-path used in the url.
   *
   * @see GenerateJavadocSitemapTask#setProjectUrl(java.lang.String)
   */
  public void setSubprojectSubpath(String subprojectSubpath) {
    if (!subprojectSubpath.isEmpty() && !subprojectSubpath.endsWith("/")) {
      subprojectSubpath += "/";
    }
    this.subprojectSubpath = subprojectSubpath;
  }

  /**
   * Calls {@link GenerateJavadocSitemap#addSitemapToJavadocJar(java.io.File, java.lang.String)} for each
   * file in {@link GenerateJavadocSitemapTask#setBuildDirectory(java.lang.String)} that matches {@link SeoJavadocFilterTask#javadocJarFilter}
   * while logging to {@link GenerateJavadocSitemapTask#log(java.lang.String, int)}.
   */
  @Override
  public void execute() throws BuildException {
    try {
      if (buildDirectory == null) {
        throw new BuildException("buildDirectory required");
      }
      if (!buildDirectory.exists()) {
        throw new IOException("buildDirectory does not exist: " + buildDirectory);
      }
      if (!buildDirectory.isDirectory()) {
        throw new IOException("buildDirectory is not a directory: " + buildDirectory);
      }
      int count = 0;
      File[] javadocJarFiles = buildDirectory.listFiles(SeoJavadocFilterTask.javadocJarFilter);
      if (javadocJarFiles != null) {
        for (File javadocJar : javadocJarFiles) {
          GenerateJavadocSitemap.addSitemapToJavadocJar(
              javadocJar,
              SeoJavadocFilterTask.getApidocsUrl(javadocJar, projectUrl, subprojectSubpath),
              msg -> log(msg.get(), LogLevel.DEBUG.getLevel()),
              msg -> log(msg.get(), LogLevel.INFO.getLevel()),
              msg -> log(msg.get(), LogLevel.WARN.getLevel())
          );
          count++;
        }
      }
      if (count == 0) {
        log("Generate Javadoc Sitemap found no files matching *" + SeoJavadocFilterTask.FILTER_SUFFIX, LogLevel.INFO.getLevel());
      }
    } catch (IOException e) {
      throw new BuildException(e);
    }
  }
}
