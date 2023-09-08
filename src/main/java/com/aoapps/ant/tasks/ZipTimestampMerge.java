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
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.ParseException;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Standalone implementation of ZIP-file timestamp merging.
 * <p>
 * This does not have any direct Ant dependencies.
 * If only using this class, it is permissible to exclude the ant dependencies.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public final class ZipTimestampMerge {

  /** Make no instances. */
  private ZipTimestampMerge() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(ZipTimestampMerge.class.getName());

  private static final FilenameFilter FILTER = (dir, name) -> {
    String lowerName = name.toLowerCase(Locale.ROOT);
    return lowerName.endsWith(".aar")
        || lowerName.endsWith(".jar")
        || lowerName.endsWith(".war")
        || lowerName.endsWith(".zip");
  };

  private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("-[0-9]");

  private static String parseArtifactId(String filename) throws ParseException {
    // Take everythiing up to the first hyphen followed by a digit
    Matcher matcher = ARTIFACT_ID_PATTERN.matcher(filename);
    if (matcher.find()) {
      int start = matcher.start();
      if (start < 1) {
        throw new ParseException("Unable to parse artifactId: " + filename, 0);
      }
      return filename.substring(0, start);
    }
    throw new ParseException("Unable to parse artifactId: " + filename, 0);
  }

  private static final Pattern TYPE_PATTERN = Pattern.compile(".*\\.([a-zA-Z]+)");

  private static String parseType(String filename) throws ParseException {
    Matcher matcher = TYPE_PATTERN.matcher(filename);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    throw new ParseException("Unable to parse type: " + filename, 0);
  }

  private static final Pattern CLASSIFIER_PATTERN = Pattern.compile(".*?-([a-z-]+)");

  private static String parseClassifier(String filename, String type) {
    Objects.requireNonNull(filename);
    Objects.requireNonNull(type);
    assert filename.endsWith("." + type);
    String withoutType = filename.substring(0, filename.length() - (type.length() + 1));
    Matcher matcher = CLASSIFIER_PATTERN.matcher(withoutType);
    if (matcher.matches()) {
      return matcher.group(1);
    } else {
      return "";
    }
  }

  /**
   * Round to 2-second interval for ZIP time compatibility.
   */
  private static long zipRoundTime(long mills) {
    return Math.floorDiv(mills, 2000) * 2000;
  }

  /**
   * Implementation of {@link #mergeFile(java.time.Instant, boolean, java.io.File, java.io.File)}
   * with provided logging.
   */
  static void mergeFile(
      Instant outputTimestamp,
      boolean buildReproducible,
      File lastBuildArtifact,
      File buildArtifact,
      Consumer<? super String> debug,
      Consumer<? super String> warn
  ) throws IOException {
    // Validate
    Objects.requireNonNull(outputTimestamp, "outputTimestamp required");
    long outputTimestampMillis = outputTimestamp.toEpochMilli();
    debug.accept("Opening buildArtifact: " + buildArtifact);
    try (ZipFile buildZipFile = new ZipFile(buildArtifact)) {
      if (buildReproducible) {
        debug.accept("validate reproducible: " + buildArtifact);
        Enumeration<? extends ZipEntry> entries = buildZipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          // Verify time
          long entryTime = ZipUtils.getTimeUtc(entry).orElseThrow(() -> new ZipException(
              "validate reproducible: No time on ZIP entry: " + buildArtifact + " @ " + entry.getName()));
          if (entryTime != outputTimestampMillis
              && zipRoundTime(entryTime) != zipRoundTime(outputTimestampMillis)) {
            throw new ZipException("validate reproducible: Mismatched entry.time: expected " + outputTimestampMillis
                + ", got " + entryTime + " on ZIP entry: " + buildArtifact + " @ " + entry.getName());
          }
          // Verify last-modified time
          long entryLastModifiedTime = ZipUtils.getLastModifiedTimeUtc(entry).orElseThrow(() -> new ZipException(
              "validate reproducible: No last-modified time on ZIP entry: " + buildArtifact + " @ " + entry.getName()));
          if (entryLastModifiedTime != outputTimestampMillis
              && zipRoundTime(entryLastModifiedTime) != zipRoundTime(outputTimestampMillis)) {
            throw new ZipException("validate reproducible: Mismatched entry.lastModifiedTime: expected " + outputTimestampMillis
                + ", got " + entryLastModifiedTime + " on ZIP entry: " + buildArtifact + " @ " + entry.getName());
          }
        }
      }
      debug.accept("Opening lastBuildArtifact: " + lastBuildArtifact);
      try (ZipFile lastBuildZipFile = new ZipFile(lastBuildArtifact)) {
        warn.accept("TODO: Implement ZIP file merge from " + lastBuildArtifact + " to " + buildArtifact);
      }
    }
  }

  /**
   * Creates a ZIP file with contents matching {@code buildArtifact} but with timestamps derived from
   * {@code lastBuildArtifact}.
   * <p>
   * For each entry, if the content is byte-for-byte equal, maintains the
   * {@linkplain ZipEntry#getTime() time} and {@linkplain ZipEntry#getLastModifiedTime()}.
   * </p>
   *
   * @param outputTimestamp   See {@link ZipTimestampMergeTask#setOutputTimestamp(java.lang.String)}
   * @param buildReproducible See {@link ZipTimestampMergeTask#setBuildReproducible(boolean)}
   * @param lastBuildArtifact The ZIP file from the last successful build
   * @param buildArtifact     The ZIP file from the current build
   */
  public static void mergeFile(
      Instant outputTimestamp,
      boolean buildReproducible,
      File lastBuildArtifact,
      File buildArtifact
  ) throws IOException, ParseException {
    ZipTimestampMerge.mergeFile(
        outputTimestamp,
        buildReproducible,
        lastBuildArtifact,
        buildArtifact,
        logger::fine,
        logger::warning
    );
  }

  /**
   * Artifacts are identified by {@code (artifactId, classifier, type)}.
   */
  private static final class Identifier implements Comparable<Identifier> {

    private final String artifactId;
    private final String classifier;
    private final String type;

    private Identifier(String filename) throws ParseException {
      artifactId = parseArtifactId(filename);
      type = parseType(filename);
      classifier = parseClassifier(filename, type);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(artifactId).append("-*");
      if (!classifier.isEmpty()) {
        sb.append('-').append(classifier);
      }
      sb.append('.').append(type);
      return sb.toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(artifactId, classifier, type);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Identifier)) {
        return false;
      }
      final Identifier other = (Identifier) obj;
      return artifactId.equals(other.artifactId)
          && classifier.equals(other.classifier)
          && type.equals(other.type);
    }

    @Override
    public int compareTo(Identifier o) {
      int diff = artifactId.compareToIgnoreCase(o.artifactId);
      if (diff != 0) {
        return diff;
      }
      assert classifier.equals(classifier.toLowerCase(Locale.ROOT)) : "classifier is lowercase";
      assert o.classifier.equals(o.classifier.toLowerCase(Locale.ROOT)) : "classifier is lowercase";
      diff = classifier.compareToIgnoreCase(o.classifier);
      if (diff != 0) {
        return diff;
      }
      return type.compareToIgnoreCase(o.type);
    }
  }

  /**
   * Reads all the files in a directory matching {@link #FILTER}.
   */
  private static Map<Identifier, File> findArtifacts(String paramName, File directory, boolean requiredDirectory) throws IOException, ParseException {
    if (requiredDirectory) {
      Objects.requireNonNull(directory, () -> paramName + " required");
    }
    Map<Identifier, File> files = new TreeMap<>();
    if (directory != null) {
      if (!directory.exists()) {
        if (requiredDirectory) {
          throw new IOException(paramName + " does not exist: " + directory);
        }
      } else if (!directory.isDirectory()) {
        throw new IOException(paramName + " is not a directory: " + directory);
      } else {
        String[] list = directory.list(FILTER);
        if (list != null) {
          for (String filename : list) {
            Identifier identifier = new Identifier(filename);
            File file = new File(directory, filename);
            File existing = files.put(identifier, file);
            if (existing != null) {
              throw new IOException(paramName + " has duplicate " + identifier + " in " + directory + ": "
                  + existing.getName() + " and " + filename);
            }
          }
        }
      }
    }
    return files;
  }

  /**
   * Implementation of {@link #mergeDirectory(java.time.Instant, boolean, boolean, java.io.File, java.io.File)}
   * with provided logging.
   */
  static void mergeDirectory(
      Instant outputTimestamp,
      boolean buildReproducible,
      boolean requireLastBuild,
      File lastBuildDirectory,
      File buildDirectory,
      Consumer<? super String> debug,
      Consumer<? super String> info,
      Consumer<? super String> warn,
      Consumer<? super String> err
  ) throws IOException, ParseException {
    // Validate
    Objects.requireNonNull(outputTimestamp, "outputTimestamp required");
    // Find artifacts
    Map<Identifier, File> lastBuildArtifacts = findArtifacts("lastBuildDirectory", lastBuildDirectory, requireLastBuild);
    Map<Identifier, File> buildArtifacts = findArtifacts("buildDirectory", buildDirectory, true);
    // Enforce one-to-one mapping
    if (requireLastBuild) {
      Set<Identifier> lastBuildKeys = lastBuildArtifacts.keySet();
      Set<Identifier> buildKeys = buildArtifacts.keySet();
      if (!lastBuildKeys.equals(buildKeys)) {
        StringBuilder message = new StringBuilder("Not a one-to-one mapping while requireLastBuild = true:");
        boolean first = true;
        for (Identifier buildKey : buildKeys) {
          if (!lastBuildKeys.contains(buildKey)) {
            if (first) {
              message.append(System.lineSeparator()).append("  Missing");
              if (lastBuildDirectory != null) {
                message.append(" in ").append(lastBuildDirectory);
              }
              message.append(':');
              first = false;
            }
            message.append(System.lineSeparator()).append("    ").append(buildKey);
          }
        }
        first = true;
        for (Identifier lastBuildKey : lastBuildKeys) {
          if (!buildKeys.contains(lastBuildKey)) {
            if (first) {
              message.append(System.lineSeparator()).append("  Missing in ").append(buildDirectory).append(':');
              first = false;
            }
            message.append(System.lineSeparator()).append("    ").append(lastBuildKey);
          }
        }
        throw new IOException(message.toString());
      }
    }
    // Perform for each artifact
    for (Map.Entry<Identifier, File> buildEntry : buildArtifacts.entrySet()) {
      Identifier identifier = buildEntry.getKey();
      File buildArtifact = buildEntry.getValue();
      debug.accept(identifier + ": buildArtifact: " + buildArtifact);
      File lastBuildArtifact = lastBuildArtifacts.get(identifier);
      if (lastBuildArtifact != null) {
        debug.accept(identifier + ": lastBuildArtifact: " + lastBuildArtifact);
        mergeFile(
            outputTimestamp,
            buildReproducible,
            lastBuildArtifact,
            buildArtifact,
            // Prepend identifier on log messages
            msg -> debug.accept(identifier + ": " + msg),
            msg -> warn.accept(identifier + ": " + msg)
        );
      } else {
        assert !requireLastBuild : "one-to-one mapping already enforced";
        warn.accept(identifier + ": not found in lastBuildDirectory: " + lastBuildDirectory);
      }
    }
  }

  /**
   * <p>
   * Merges all {@code *.aar}, {@code *.jar}, {@code *.war}, and {@code *.zip} files between {@code lastBuildDirectory}
   * and {@code buildDirectory}.  Artifacts in {@code buildDirectory} are overwritten in-place only when altered.
   * </p>
   * <p>
   * Identifies the one-to-one mappings by matching artifactId, classifier (optional), and type.  These are parsed from
   * the filenames and make the following assumptions:
   * </p>
   * <ol>
   * <li>All fields are separated by hyphens {@code '-'}</li>
   * <li>Version number begins with {@code [0-9]}</li>
   * <li>Type is everything after the final period and contains only {@code [a-zA-Z]}</li>
   * <li>Classifier is before the final period and contains only {@code [a-z-]}</li>
   * </ol>
   * <p>
   * There must be only one possible mapping per unique {@code (artifactId, classifier, type)}.
   * When {@code requireLastBuild = true}, there must be a one-to-one mapping in both directions between
   * {@code lastBuildDirectory} and {@code buildDirectory}.  No file may be added or missing.
   * </p>
   * <p>
   * Each mappings are resolved, calls {@link #mergeFile(java.time.Instant, boolean, java.io.File, java.io.File)} for
   * each pair of files.
   * </p>
   *
   * @param outputTimestamp    See {@link ZipTimestampMergeTask#setOutputTimestamp(java.lang.String)}
   * @param buildReproducible  See {@link ZipTimestampMergeTask#setBuildReproducible(boolean)}
   * @param requireLastBuild   See {@link ZipTimestampMergeTask#setRequireLastBuild(boolean)}
   * @param lastBuildDirectory See {@link ZipTimestampMergeTask#setLastBuildDirectory(java.lang.String)}
   * @param buildDirectory     See {@link ZipTimestampMergeTask#setBuildDirectory(java.lang.String)}
   */
  public static void mergeDirectory(
      Instant outputTimestamp,
      boolean buildReproducible,
      boolean requireLastBuild,
      File lastBuildDirectory,
      File buildDirectory
  ) throws IOException, ParseException {
    ZipTimestampMerge.mergeDirectory(
        outputTimestamp,
        buildReproducible,
        requireLastBuild,
        lastBuildDirectory,
        buildDirectory,
        logger::fine,
        logger::info,
        logger::warning,
        logger::severe
    );
  }
}
