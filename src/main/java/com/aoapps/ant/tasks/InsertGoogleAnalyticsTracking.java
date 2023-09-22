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

import static com.aoapps.ant.tasks.SeoJavadocFilter.AT;
import static com.aoapps.ant.tasks.SeoJavadocFilter.ENCODING;
import static com.aoapps.ant.tasks.SeoJavadocFilter.FILTER_EXTENSION;
import static com.aoapps.ant.tasks.SeoJavadocFilter.NL;
import static com.aoapps.ant.tasks.SeoJavadocFilter.readLinesWithEof;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Inserts the modern Google Analytics
 * <a href="https://support.google.com/analytics/answer/1008080?hl=en&amp;ref_topic=1008079#GA">Global Site Tag</a>
 * into the <code>&lt;head&gt;</code> of all {@link SeoJavadocFilter#FILTER_EXTENSION} files in ZIP files.
 * Must be used with HTML 5.
 * <p>
 * This does not have any direct Ant dependencies.
 * If only using this class, it is permissible to exclude the ant dependencies.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public final class InsertGoogleAnalyticsTracking {

  /** Make no instances. */
  private InsertGoogleAnalyticsTracking() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(InsertGoogleAnalyticsTracking.class.getName());

  /**
   * Generates the expected lines for the tracking script.
   */
  // Matches semanticcms-google-analytics:GoogleAnalytics.java:writeGlobalSiteTag
  private static List<String> generateGlobalSiteTag(String googleAnalyticsTrackingId) {
    String encodedTrackingId = URLEncoder.encode(googleAnalyticsTrackingId, ENCODING);
    return Arrays.asList(
      "<link rel=\"dns-prefetch\" href=\"https://www.google-analytics.com/\">" + NL,
      "<link rel=\"preconnect\" href=\"https://www.google-analytics.com/\" crossorigin>" + NL,
      "<script async src=\"https://www.googletagmanager.com/gtag/js?id=" + encodedTrackingId + "\"></script>" + NL,
      "<script>" + NL,
      "window.dataLayer = window.dataLayer || [];" + NL,
      "function gtag(){dataLayer.push(arguments);}" + NL,
      "gtag(\"js\", new Date());" + NL,
      // This is not JavaScript encoded, but we're only expecting URL-safe characters anyway:
      "gtag(\"config\", \"" + encodedTrackingId + "\");" + NL,
      "</script>" + NL
    );
  }

  /**
   * Inserts the tracking code into the HTML head, failing if already exists and has a different code.
   */
  private static void insertIntoHead(File file, ZipArchiveEntry zipEntry, List<String> linesWithEof,
      List<String> googleSiteTag, Consumer<Supplier<String>> debug) throws ZipException {
    int headStartIndex = SeoJavadocFilter.findHeadStartIndex(file, zipEntry, linesWithEof, "");
    int headEndIndex = SeoJavadocFilter.findHeadEndIndex(file, zipEntry, linesWithEof, "", headStartIndex);
    // Check if script already present
    int insertIndex = headStartIndex + 1;
    if (linesWithEof.get(insertIndex).equals(googleSiteTag.get(0))) {
      // Verify all lines match, fail if mismatch
      debug.accept(() -> "Verifying existing Global Site Tag found at line " + (insertIndex + 1));
      for (int i = 1; i < googleSiteTag.size(); i++) {
        int verifyIndex = insertIndex + i;
        if (verifyIndex >= headEndIndex) {
          throw new ZipException("End of head reached before end of Google Site Tag: " + file + AT + zipEntry
              + " @ line " + (headEndIndex + 1));
        } else {
          String expected = googleSiteTag.get(i);
          String actual = linesWithEof.get(verifyIndex);
          if (!actual.equals(expected)) {
            throw new ZipException("Unexpected existing line of Google Site Tag: " + file + AT + zipEntry
                + " @ line " + (verifyIndex + 1) + ": expected \"" + expected.replace(String.valueOf(NL), "")
                + "\", actual \"" + actual.replace(String.valueOf(NL), "") + '"');
          }
        }
      }
    } else {
      // Insert script
      debug.accept(() -> "Inserting Global Site Tag at line " + (insertIndex + 1));
      linesWithEof.addAll(insertIndex, googleSiteTag);
    }
  }

  /**
   * Implementation of {@link #addTrackingCodeToZip(java.io.File)}
   * with provided logging.
   */
  static void addTrackingCodeToZip(
      File file,
      String googleAnalyticsTrackingId,
      Consumer<Supplier<String>> debug,
      Consumer<Supplier<String>> info,
      Consumer<Supplier<String>> warn
  ) throws IOException {
    info.accept(() -> "Insert Google Analytics Tracking processing " + file);
    // Validate
    Objects.requireNonNull(file, "file required");
    if (!file.exists()) {
      throw new IOException("file does not exist: " + file);
    }
    if (!file.isFile()) {
      throw new IOException("file is not a regular file: " + file);
    }
    googleAnalyticsTrackingId = StringUtils.trimToNull(googleAnalyticsTrackingId);
    if (googleAnalyticsTrackingId == null) {
      warn.accept(() -> "No googleAnalyticsTrackingId, skipping " + file);
    } else {
      // Generate the script once, since is the same for all files
      List<String> googleSiteTag = generateGlobalSiteTag(googleAnalyticsTrackingId);
      File tmpFile = File.createTempFile(file.getName() + "-", ".zip", file.getParentFile());
      Closeable deleteTmpFile = () -> {
        if (tmpFile.exists()) {
          FileUtils.delete(tmpFile);
        }
      };
      try (deleteTmpFile) {
        debug.accept(() -> "Writing temp file " + tmpFile);
        try (ZipArchiveOutputStream tmpZipOut = new ZipArchiveOutputStream(tmpFile)) {
          debug.accept(() -> "Reading " + file);
          try (ZipFile zipFile = new ZipFile(file)) {
            Enumeration<ZipArchiveEntry> zipEntries = zipFile.getEntriesInPhysicalOrder();
            while (zipEntries.hasMoreElements()) {
              ZipArchiveEntry zipEntry = zipEntries.nextElement();
              debug.accept(() -> "zipEntry: " + zipEntry);
              String zipEntryName = zipEntry.getName();
              // Require times on all entries
              long zipEntryTime = zipEntry.getTime();
              if (zipEntryTime == -1) {
                throw new ZipException("No time in entry: " + file + AT + zipEntryName);
              }
              // Anything not ending in *.html (which will include directories), just copy verbatim
              if (!StringUtils.endsWithIgnoreCase(zipEntryName, FILTER_EXTENSION)) {
                // Raw copy
                try (InputStream rawStream = zipFile.getRawInputStream(zipEntry)) {
                  tmpZipOut.addRawArchiveEntry(zipEntry, rawStream);
                }
              } else {
                List<String> linesWithEof = readLinesWithEof(file, zipFile, zipEntry);
                String originalHtml = StringUtils.join(linesWithEof, "");
                debug.accept(() -> zipEntryName + ": Read " + linesWithEof.size() + " lines, " + originalHtml.length()
                    + " characters");
                // Insert header, failing if already exists and has a different code
                insertIntoHead(file, zipEntry, linesWithEof, googleSiteTag, debug);
                // Recombine
                String newHtml = StringUtils.join(linesWithEof, "");
                // Only when modified
                if (!newHtml.equals(originalHtml)) {
                  // Store as copy to get as much as possible from old entry
                  ZipArchiveEntry newEntry = new ZipArchiveEntry(zipEntry);
                  tmpZipOut.putArchiveEntry(newEntry);
                  tmpZipOut.write(newHtml.getBytes(ENCODING));
                  tmpZipOut.closeArchiveEntry();
                } else {
                  // Raw copy
                  try (InputStream rawStream = zipFile.getRawInputStream(zipEntry)) {
                    tmpZipOut.addRawArchiveEntry(zipEntry, rawStream);
                  }
                }
              }
            }
          }
        }
        // Ovewrite if anything changed, delete otherwise
        if (!FileUtils.contentEquals(file, tmpFile)) {
          if (!tmpFile.renameTo(file)) {
            throw new IOException("Rename failed: " + tmpFile + " to " + file);
          }
        } else {
          info.accept(() -> "Insert Google Analytics Tracking: No changes made (ZIP file already processed?)");
        }
      }
    }
  }

  /**
   * Inserts the modern Google Analytics
   * <a href="https://support.google.com/analytics/answer/1008080?hl=en&amp;ref_topic=1008079#GA">Global Site Tag</a>
   * into the <code>&lt;head&gt;</code> of all {@link SeoJavadocFilter#FILTER_EXTENSION} files in the given ZIP file.
   *
   * @param file See {@link InsertGoogleAnalyticsTrackingTask#setFile(java.lang.String)}
   * @param googleAnalyticsTrackingId See {@link InsertGoogleAnalyticsTrackingTask#setGoogleAnalyticsTrackingId(java.lang.String)}
   */
  public static void addTrackingCodeToZip(
      File file,
      String googleAnalyticsTrackingId
  ) throws IOException {
    addTrackingCodeToZip(
        file,
        googleAnalyticsTrackingId,
        logger::fine,
        logger::info,
        logger::warning
    );
  }
}
