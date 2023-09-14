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
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.LogLevel;

/*
Test with:

(
  cd ~/maven2/ao/oss/ant-tasks &&
  MAVEN_OPTS="-Djansi.force=true" mvn -Dpgpverify.skip -Pjenkins clean test &&
  MAVEN_OPTS="-Djansi.force=true" mvn -Dpgpverify.skip -Pjenkins,jenkins-deploy,nexus -DrequireLastBuild=false install
) 2>&1 | less -SR
*/
/**
 * Ant task that invokes {@link SeoJavadocFilter#filterJavadocJar(java.io.File, java.lang.String, java.lang.Iterable, java.lang.Iterable)}.
 * <p>
 * Note: This task should be performed before {@link ZipTimestampMergeTask} in order to have correct content to be able
 * to maintain timestamps.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
@SuppressWarnings("CloneableImplementsClone")
public class SeoJavadocFilterTask extends Task {

  private static final String JAVASE = "javase";
  private static final String JAVAEE = "javaee";
  private static final String JAKARTAEE = "jakartaee";
  private static final String DEFAULT = "default";

  /**
   * URL prefixes for {@link #JAVASE}.
   */
  // Note: These prefixes match ao-oss-parent:pom.xml -->
  private static final List<String> javaseUrlPrefixes = Arrays.asList(
      "https://docs.oracle.com/javase/",
      "https://docs.oracle.com/en/java/javase/",
      "https://download.java.net/java/early_access/"
  );

  /**
   * URL prefixes for {@link #JAVAEE} or {@link #JAKARTAEE}.
   */
  // Note: These prefixes match ao-oss-parent:pom.xml -->
  private static final List<String> javaeeUrlPrefixes = Arrays.asList(
      "https://jakarta.ee/specifications/activation/",
      "https://javaee.github.io/javamail/docs/api/",
      "https://docs.oracle.com/javaee/",
      "https://jakarta.ee/specifications/platform/"
  );

  /**
   * Default nofollows for {@link #DEFAULT}.
   */
  private static final List<String> defaultNofollows;

  static {
    int size = javaseUrlPrefixes.size() + javaeeUrlPrefixes.size();
    defaultNofollows = new ArrayList<>(size);
    defaultNofollows.addAll(javaseUrlPrefixes);
    defaultNofollows.addAll(javaeeUrlPrefixes);
    assert javaseUrlPrefixes.size() == size;
  }

  static final String FILTER_SUFFIX = "-javadoc.jar";

  static final FileFilter javadocJarFilter =
      pathname -> StringUtils.endsWithIgnoreCase(pathname.getName(), FILTER_SUFFIX);

  private File buildDirectory;
  private String projectUrl;
  private Iterable<String> nofollow = defaultNofollows;
  private Iterable<String> follow = Collections.singletonList(SeoJavadocFilter.ANY_URL);

  /**
   * The current build directory.
   * Must exist and be a directory.
   * <p>
   * Each file ending with <code>"{@value #FILTER_SUFFIX}"</code> (case-insensitive) will be processed.
   * </p>
   * <p>
   * Each file is a Javadoc JAR file to filter and must be a regular file.
   * </p>
   */
  public void setBuildDirectory(String buildDirectory) {
    this.buildDirectory = new File(buildDirectory);
  }

  /**
   * The project url.  The apidocs URLs will be based on this, depending on artifact classifier.
   * Ending in {@code "*-test-javadoc.jar"} will be {@code "${projectUrl}test/apidocs/"}.
   * Otherwise will be {@code "${projectUrl}apidocs/"}
   */
  public void setProjectUrl(String projectUrl) {
    if (!projectUrl.endsWith("/")) {
      projectUrl += "/";
    }
    this.projectUrl = projectUrl;
  }

  /**
   * The comma/whitespace separated list of URL prefixes (case-insensitive) to set as
   * <code>rel="nofollow"</code>. May use {@link SeoJavadocFilter#ANY_URL} to match all.
   * Nofollows are matched before {@linkplain #setFollow(java.lang.String) follows}.
   * <p>
   * If no match is found in either nofollow or follow, the filtering will throw an exception.  This can be useful
   * for those who want to ensure every URL is considered.
   * </p>
   * <p>
   * May use word "default" in list to add default entries in addition to your own.
   * May use word "javase" to exclude Java SE.
   * May use word "javaee" or "jakartaee" to exclude both Java EE and Jakarta EE.
   * </p>
   * <p>
   * Defaults to exclude Java SE, Java EE, and Jakarta EE apidocs.
   * </p>
   */
  public void setNofollow(String nofollow) {
    Set<String> nofollowPrefixes = new LinkedHashSet<>();
    for (String value : nofollow.split("[\\s,]+")) {
      if (DEFAULT.equalsIgnoreCase(value)) {
        nofollowPrefixes.addAll(defaultNofollows);
      } else if (JAVASE.equalsIgnoreCase(value)) {
        nofollowPrefixes.addAll(javaseUrlPrefixes);
      } else if (JAVAEE.equalsIgnoreCase(value) || JAKARTAEE.equalsIgnoreCase(value)) {
        nofollowPrefixes.addAll(javaeeUrlPrefixes);
      } else {
        nofollowPrefixes.add(value);
      }
    }
    this.nofollow = nofollowPrefixes;
  }

  /**
   * The comma/whitespace separated list of URL prefixes (case-insensitive) to <strong>not</strong> set as
   * <code>rel="nofollow"</code>. May use {@link SeoJavadocFilter#ANY_URL} to match all.
   * Follows are matched after {@linkplain #setNofollow(java.lang.String) nofollows}.
   * <p>
   * If no match is found in either nofollow or follow, the filtering will throw an exception.  This can be useful
   * for those who want to ensure every URL is considered.
   * </p>
   * <p>
   * May use word "javase" to include Java SE.
   * May use word "javaee" or "jakartaee" to include both Java EE and Jakarta EE.
   * </p>
   * <p>
   * Defaults to <code>"{@value SeoJavadocFilter#ANY_URL}"</code> (all).
   * </p>
   */
  public void setFollow(String follow) {
    Set<String> followPrefixes = new LinkedHashSet<>();
    for (String value : follow.split("[\\s,]+")) {
      if (JAVASE.equalsIgnoreCase(value)) {
        followPrefixes.addAll(javaseUrlPrefixes);
      } else if (JAVAEE.equalsIgnoreCase(value) || JAKARTAEE.equalsIgnoreCase(value)) {
        followPrefixes.addAll(javaeeUrlPrefixes);
      } else {
        followPrefixes.add(value);
      }
    }
    this.follow = followPrefixes;
  }

  static String getApidocsUrl(File javadocJar, String projectUrl) {
    String apidocsUrl;
    if (StringUtils.endsWithIgnoreCase(javadocJar.getName(), "-test-javadoc.jar")) {
      apidocsUrl = projectUrl + "test/apidocs";
    } else {
      apidocsUrl = projectUrl + "apidocs";
    }
    return apidocsUrl;
  }

  /**
   * Calls {@link SeoJavadocFilter#filterJavadocJar(java.io.File, java.lang.String, java.lang.Iterable, java.lang.Iterable)} for each
   * file in {@link #setBuildDirectory(java.lang.String)} that matches {@link #javadocJarFilter}
   * while logging to {@link #log(java.lang.String, int)}.
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
      File[] javadocJarFiles = buildDirectory.listFiles(javadocJarFilter);
      if (javadocJarFiles != null) {
        for (File javadocJar : javadocJarFiles) {
          SeoJavadocFilter.filterJavadocJar(
              javadocJar,
              getApidocsUrl(javadocJar, projectUrl),
              nofollow,
              follow,
              msg -> log(msg.get(), LogLevel.DEBUG.getLevel()),
              msg -> log(msg.get(), LogLevel.INFO.getLevel()),
              msg -> log(msg.get(), LogLevel.WARN.getLevel())
          );
          count++;
        }
      }
      if (count == 0) {
        log("SEO Javadoc filtering found no files matching *" + FILTER_SUFFIX, LogLevel.INFO.getLevel());
      }
    } catch (IOException e) {
      throw new BuildException(e);
    }
  }
}
