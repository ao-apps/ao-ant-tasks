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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Filters javadocs for search engine optimization.  Performs the following transformations:
 * <p>
 * Note: This task should be performed before {@link ZipTimestampMerge} in order to have correct content to be able
 * to maintain timestamps.
 * </p>
 * <ol>
 * <li>
 *   Adds <a href="https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls">Canonical URLs</a> to each page.
 * </li>
 * <li>
 *   Adds <a href="https://www.robotstxt.org/meta.html">{@code <meta name="robots" content="noindex, nofollow">}</a>
 *   to selective pages. See
 *   {@link #getRobotsHeader(java.io.File, org.apache.commons.compress.archivers.zip.ZipArchiveEntry, java.util.List)}.
 * </li>
 * <li>
 *   rel="nofollow" is added to all links matching the configured nofollow and follow prefixes.
 *   This defaults to Java SE, Java EE, and Jakarta EE apidocs.
 * </li>
 * </ol>
 * <p>
 * All existing ZIP entry timestamps are preserved.
 * </p>
 * <p>
 * This does not have any direct Ant dependencies.
 * If only using this class, it is permissible to exclude the ant dependencies.
 * </p>
 * <p>
 * See <a href="https://github.com/marketplace/actions/javadoc-cleanup">javadoc-cleanup GitHub Action</a>.
 * </p>
 *
 * @author  AO Industries, Inc.
 */
public final class SeoJavadocFilter {

  /** Make no instances. */
  private SeoJavadocFilter() {
    throw new AssertionError();
  }

  private static final Logger logger = Logger.getLogger(SeoJavadocFilter.class.getName());

  // Note: Matches semanticcms-core-servlet:SiteMapIndexServlet.java:ENCODING
  static final Charset ENCODING = StandardCharsets.UTF_8;

  /**
   * The value used to match any URL for {@link SeoJavadocFilterTask#setNofollow(java.lang.String)} or
   * {@link SeoJavadocFilterTask#setFollow(java.lang.String)}.
   */
  public static final String ANY_URL = "*";

  /**
   * The entry name pattern that will be filtered in the javadocs (case-insensitive).
   */
  static final String FILTER_EXTENSION = ".html";

  static {
    assert FILTER_EXTENSION.equals(FILTER_EXTENSION.toLowerCase(Locale.ROOT));
  }

  static final String NOINDEX = "noindex";

  private static final String FOLLOW = "follow";

  private static final String NOFOLLOW = "nofollow";

  private static final String NOINDEX_FOLLOW = NOINDEX + ", " + FOLLOW;

  private static final String NOINDEX_NOFOLLOW = NOINDEX + ", " + NOFOLLOW;

  /**
   * The required newline character.
   */
  static final char NL = '\n';

  static final String HEAD_ELEM_START = "<head>" + NL;

  static final String HEAD_ELEM_END = "</head>" + NL;

  private static final String CANONICAL_PREFIX = "<link rel=\"canonical\" href=\"";

  private static final String CANONICAL_SUFFIX = "\">" + NL;

  static final String ROBOTS_PREFIX = "<meta name=\"robots\" content=\"";

  static final String ROBOTS_SUFFIX = "\">" + NL;

  /**
   * Pattern to determine if a URL starts with a scheme.
   * Per <a href="http://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>:
   * <code>scheme        = alpha *( alpha | digit | "+" | "-" | "." )</code>
   */
  private static final Pattern SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:.*");

  static final String AT = " @ ";

  static final String AT_LINE = " @ line ";

  private static final String INDEX_HTML = "index.html";

  private static final String OVERVIEW_SUMMARY_HTML = "overview-summary.html";

  /**
   * Determines the robots header value.
   *
   * @return  the header value or {@code null} for none.
   */
  private static String getRobotsHeader(File javadocJar, ZipArchiveEntry zipEntry,
      List<String> linesWithEof) throws ZipException {
    if (zipEntry.isDirectory()) {
      return null;
    }
    String name = zipEntry.getName();
    if (
        // Packages
        StringUtils.containsIgnoreCase(name, "/class-use/")
        || StringUtils.endsWithIgnoreCase(name, "/package-tree.html")
        || StringUtils.endsWithIgnoreCase(name, "/package-use.html")
        // Directories
        || StringUtils.startsWithIgnoreCase(name, "legal/")
        || StringUtils.startsWithIgnoreCase(name, "src/")
        // Top-level
        || name.equalsIgnoreCase("allclasses-index.html")
        || name.equalsIgnoreCase("allpackages-index.html")
        || name.equalsIgnoreCase("deprecated-list.html")
        || name.equalsIgnoreCase("help-doc.html")
        || name.equalsIgnoreCase("index-all.html")
        || name.equalsIgnoreCase("overview-tree.html")
        || name.equalsIgnoreCase("search.html")
    ) {
      return NOINDEX_NOFOLLOW;
    } else if (
        name.equalsIgnoreCase(INDEX_HTML)
        || name.equalsIgnoreCase(OVERVIEW_SUMMARY_HTML)
    ) {
      boolean hasRefresh = linesWithEof.stream()
          .anyMatch(line -> line.startsWith("<meta http-equiv=\"Refresh\" content=\"0;"));
      boolean hasRedirectClass = linesWithEof.stream()
          .anyMatch(line -> line.endsWith("-redirect-page\">" + NL));
      if (hasRefresh != hasRedirectClass) {
        if (!hasRedirectClass) {
          throw new ZipException("Entry has refresh meta but does not have redirect body class: "
              + javadocJar + AT + name);
        } else {
          throw new ZipException("Entry has redirect body class but does not have refresh meta: "
              + javadocJar + AT + name);
        }
      }
      if (hasRefresh) {
        return NOINDEX_FOLLOW;
      } else if (name.equalsIgnoreCase(INDEX_HTML)) {
        int bodyElemPos = linesWithEof.indexOf("<body class=\"package-index-page\">" + NL);
        if (bodyElemPos == -1) {
          throw new ZipException("Entry has neither \"index-redirect-page\" body class nor \"package-index-page\": "
              + javadocJar + AT + name);
        }
        return null;
      } else if (name.equalsIgnoreCase(OVERVIEW_SUMMARY_HTML)) {
        throw new ZipException("Entry is only expected to be a redirect page: " + javadocJar + AT + name);
      } else {
        throw new AssertionError("Unexpected name: " + name);
      }
    } else {
      return null;
    }
  }

  /**
   * Reads all lines, splitting on lines while keeping the line endings.
   */
  static List<String> readLinesWithEof(File javadocJar, String zipEntryName, Reader in) throws IOException {
    List<String> linesWithEof = new ArrayList<>();
    StringBuilder lineSb = new StringBuilder(80);
    int ch;
    while ((ch = in.read()) != -1) {
      // Make sure only Unix newlines
      if (ch == '\r') {
        throw new ZipException("Carriage return in javadocs, requiring Unix newlines only: " + javadocJar + AT
            + zipEntryName + AT_LINE + (linesWithEof.size() + 1));
      }
      lineSb.append((char) ch);
      if (ch == NL) {
        linesWithEof.add(lineSb.toString());
        lineSb.setLength(0);
      }
    }
    if (lineSb.length() != 0) {
      linesWithEof.add(lineSb.toString());
    }
    return linesWithEof;
  }

  /**
   * Insert or update HTML.
   *
   * @param getValue from current value (which may be {@code null}) to new value (which may be {@code null}).
   *                 The new value must must be properly encoded; it will be added verbatim.
   */
  @SuppressWarnings("AssignmentToForLoopParameter")
  private static void insertOrUpdateHead(File javadocJar, ZipArchiveEntry zipEntry, List<String> linesWithEof,
      String lineStart, UnaryOperator<String> getValue, String lineEndWithEof, String msgPrefix,
      Consumer<Supplier<String>> debug
  ) throws ZipException {
    // Find the <head> line
    int headStartIndex = linesWithEof.indexOf(HEAD_ELEM_START);
    if (headStartIndex == -1) {
      throw new ZipException(msgPrefix + HEAD_ELEM_START.trim() + " not found: " + javadocJar + AT + zipEntry);
    }
    // Find the </head> line
    int headEndIndex = linesWithEof.indexOf(HEAD_ELEM_END);
    if (headEndIndex == -1) {
      throw new ZipException(msgPrefix + HEAD_ELEM_END.trim() + " not found: " + javadocJar + AT + zipEntry);
    }
    if (headEndIndex < headStartIndex) {
      throw new ZipException(msgPrefix + HEAD_ELEM_END.trim() + " before " + HEAD_ELEM_START.trim() + ": "
          + javadocJar + AT + zipEntry);
    }
    // Search for existing line to update
    int finishedIndex = -1;
    for (int lineIndex = headStartIndex + 1; lineIndex < headEndIndex; lineIndex++) {
      String lineWithEof = linesWithEof.get(lineIndex);
      if (lineWithEof.startsWith(lineStart)) {
        if (finishedIndex != -1) {
          throw new ZipException(msgPrefix + "duplicate element detected " + javadocJar + AT + zipEntry
              + " @ lines " + (finishedIndex + 1) + " and " + (lineIndex + 1));
        }
        if (!lineWithEof.endsWith(lineEndWithEof)) {
          throw new ZipException(msgPrefix + "Expected line ending (" + lineEndWithEof.trim() + ") missing: "
              + javadocJar + AT + zipEntry + AT_LINE + (lineIndex + 1));
        }
        String currentValue = lineWithEof.substring(lineStart.length(),
            lineWithEof.length() - lineEndWithEof.length());
        String newValue = getValue.apply(currentValue);
        if (currentValue.equals(newValue)) {
          debug.accept(() -> msgPrefix + "Existing value is correct: " + newValue);
        } else if (newValue == null) {
          debug.accept(() -> msgPrefix + "Removing old value: " + currentValue);
          linesWithEof.remove(lineIndex);
          lineIndex--;
          headEndIndex--;
        } else {
          debug.accept(() -> msgPrefix + "Replacing existing value: " + currentValue + " to " + newValue);
          linesWithEof.set(lineIndex, lineStart + newValue + lineEndWithEof);
        }
        finishedIndex = lineIndex;
      }
    }
    if (finishedIndex == -1) {
      String newValue = getValue.apply(null);
      if (newValue == null) {
        debug.accept(() -> msgPrefix + "Non-existing value is correct");
      } else {
        debug.accept(() -> msgPrefix + "Inserting new value: " + newValue);
        linesWithEof.add(headEndIndex, lineStart + newValue + lineEndWithEof);
      }
    }
  }

  private static void nofollowLinks(File javadocJar, ZipArchiveEntry zipEntry, List<String> linesWithEof,
      Iterable<String> nofollow, Iterable<String> follow, Consumer<Supplier<String>> debug) throws ZipException {
    // Find the </head> line
    int headEndIndex = linesWithEof.indexOf(HEAD_ELEM_END);
    if (headEndIndex == -1) {
      throw new ZipException(HEAD_ELEM_END.trim() + " not found: " + javadocJar + AT + zipEntry);
    }
    debug.accept(() -> "Filtering links in " + javadocJar + AT + zipEntry);
    for (int lineIndex = headEndIndex + 1; lineIndex < linesWithEof.size(); lineIndex++) {
      String line = linesWithEof.get(lineIndex);
      // Do not allow capital links
      int capitalStart = line.indexOf("<A ");
      if (capitalStart != -1) {
        throw new ZipException("Unexpected capitalized \"<A \" found: " + javadocJar + AT + zipEntry + AT_LINE
            + (lineIndex + 1));
      }
      // Do not allow single-quoted href
      int singleQuotedStart = line.indexOf("href='");
      if (singleQuotedStart != -1) {
        throw new ZipException("Unexpected single-quoted \"href='\" found: " + javadocJar + AT + zipEntry + AT_LINE
            + (lineIndex + 1));
      }
      // Do not allow single-quoted rel
      singleQuotedStart = line.indexOf("rel='");
      if (singleQuotedStart != -1) {
        throw new ZipException("Unexpected single-quoted \"rel='\" found: " + javadocJar + AT + zipEntry + AT_LINE
            + (lineIndex + 1));
      }
      StringBuilder newLine = new StringBuilder();
      int pos = 0;
      int len = line.length();
      while (pos < len) {
        String linkStartValue = "<a ";
        int linkStart = line.indexOf(linkStartValue, pos);
        if (linkStart == -1) {
          newLine.append(line, pos, len);
          pos = len;
        } else {
          // This assumes no > inside any quotes
          int linkEnd = line.indexOf('>', linkStart + linkStartValue.length());
          if (linkEnd == -1) {
            throw new ZipException("Link end not found: " + javadocJar + AT + zipEntry + AT_LINE
                + (lineIndex + 1));
          }
          // Find href=", but must be before linkEnd
          String hrefStr = " href=\"";
          int hrefPos = line.indexOf(hrefStr, linkStart);
          if (hrefPos == -1 || hrefPos >= linkEnd) {
            throw new ZipException("Link without href: " + javadocJar + AT + zipEntry + AT_LINE
                + (lineIndex + 1));
          }
          // Find closing quote, but must before linkEnd
          int hrefClosePos = line.indexOf('"', hrefPos + hrefStr.length());
          if (hrefClosePos == -1 || hrefClosePos >= linkEnd) {
            throw new ZipException("href without closing quote: " + javadocJar + AT + zipEntry + AT_LINE
                + (lineIndex + 1));
          }
          String hrefValue = line.substring(hrefPos + hrefStr.length(), hrefClosePos);
          // Find optional rel=", but must be before linkEnd
          String relStr = " rel=\"";
          int relPos = line.indexOf(relStr, linkStart);
          int relClosePos;
          String relValue;
          if (relPos == -1 || relPos >= linkEnd) {
            // no existing rel
            relClosePos = -1;
            relValue = null;
          } else {
            // Find closing quote, but must before linkEnd
            relClosePos = line.indexOf('"', relPos + relStr.length());
            if (relClosePos == -1 || relClosePos >= linkEnd) {
              throw new ZipException("rel without closing quote: " + javadocJar + AT + zipEntry + AT_LINE
                  + (lineIndex + 1));
            }
            relValue = line.substring(relPos + relStr.length(), relClosePos);
          }
          // Find the expected rel value
          String expectedRel;
          // Don't filter href starting with "#"
          if (hrefValue.startsWith("#")) {
            expectedRel = FOLLOW;
          } else {
            boolean hasScheme = SCHEME_PATTERN.matcher(hrefValue).matches();
            if (!hasScheme) {
              // No scheme, is relative URL
              expectedRel = FOLLOW;
            } else {
              expectedRel = null;
              for (String nofollowPrefix : nofollow) {
                if (ANY_URL.equals(nofollowPrefix) || StringUtils.startsWithIgnoreCase(hrefValue, nofollowPrefix)) {
                  expectedRel = NOFOLLOW;
                  break;
                }
              }
              if (expectedRel == null) {
                for (String followPrefix : follow) {
                  if (ANY_URL.equals(followPrefix) || StringUtils.startsWithIgnoreCase(hrefValue, followPrefix)) {
                    expectedRel = FOLLOW;
                    break;
                  }
                }
                if (expectedRel == null) {
                  throw new ZipException("URL not matched in any nofollow or follow prefix: " + javadocJar + AT
                      + zipEntry + AT_LINE + (lineIndex + 1) + " href = " + hrefValue);
                }
              }
            }
          }
          assert expectedRel != null;
          String expectedRelFinal = expectedRel;
          debug.accept(() -> "hrefValue = " + hrefValue + ", relValue = " + (relValue == null ? "[NULL]" : relValue)
              + ", linkEnd = " + linkEnd + ", expectedRel = " + expectedRelFinal);
          // Update / replace rel as-needed
          if (expectedRel.equals(FOLLOW)) {
            if (FOLLOW.equals(relValue) || NOFOLLOW.equals(relValue)) {
              // Remove rel
              newLine.append(line, pos, relPos).append(line, relClosePos + 1, linkEnd + 1);
            } else {
              // Nothing to change
              newLine.append(line, pos, linkEnd + 1);
            }
          } else if (!expectedRel.equals(relValue)) {
            if (relValue == null) {
              // Insert before href
              newLine.append(line, pos, hrefPos).append(relStr).append(expectedRel).append('"')
                  .append(line, hrefPos, linkEnd + 1);
            } else {
              // Update
              newLine.append(line, pos, relPos + relStr.length()).append(expectedRel).append(line, relClosePos, linkEnd + 1);
            }
          } else {
            // Nothing to change
            newLine.append(line, pos, linkEnd + 1);
          }
          pos = linkEnd + 1;
        }
      }
      linesWithEof.set(lineIndex, newLine.toString());
    }
  }

  /**
   * Implementation of {@link #filterJavadocJar(java.io.File)}
   * with provided logging.
   */
  static void filterJavadocJar(
      File javadocJar,
      String apidocsUrl,
      Iterable<String> nofollow,
      Iterable<String> follow,
      Consumer<Supplier<String>> debug,
      Consumer<Supplier<String>> info
  ) throws IOException {
    info.accept(() -> "SEO Javadoc filtering " + javadocJar);
    // Validate
    Objects.requireNonNull(javadocJar, "javadocJar required");
    if (!javadocJar.exists()) {
      throw new IOException("javadocJar does not exist: " + javadocJar);
    }
    if (!javadocJar.isFile()) {
      throw new IOException("javadocJar is not a regular file: " + javadocJar);
    }
    Objects.requireNonNull(apidocsUrl, "apidocsUrl required");
    String apidocsUrlWithSlash;
    if (!apidocsUrl.endsWith("/")) {
      apidocsUrlWithSlash = apidocsUrl + "/";
    } else {
      apidocsUrlWithSlash = apidocsUrl;
    }
    Objects.requireNonNull(nofollow, "nofollow required");
    Objects.requireNonNull(follow, "follow required");
    File tmpFile = File.createTempFile(javadocJar.getName() + "-", ".jar", javadocJar.getParentFile());
    Closeable deleteTmpFile = () -> {
      if (tmpFile.exists()) {
        FileUtils.delete(tmpFile);
      }
    };
    try (deleteTmpFile) {
      debug.accept(() -> "Writing temp file " + tmpFile);
      try (ZipArchiveOutputStream tmpZipOut = new ZipArchiveOutputStream(tmpFile)) {
        debug.accept(() -> "Reading " + javadocJar);
        try (ZipFile zipFile = new ZipFile(javadocJar)) {
          Enumeration<ZipArchiveEntry> zipEntries = zipFile.getEntriesInPhysicalOrder();
          while (zipEntries.hasMoreElements()) {
            ZipArchiveEntry zipEntry = zipEntries.nextElement();
            debug.accept(() -> "zipEntry: " + zipEntry);
            String zipEntryName = zipEntry.getName();
            // Require times on all entries
            long zipEntryTime = zipEntry.getTime();
            if (zipEntryTime == -1) {
              throw new ZipException("No time in entry: " + javadocJar + AT + zipEntryName);
            }
            // Anything not ending in *.html (which will include directories), just copy verbatim
            if (!StringUtils.endsWithIgnoreCase(zipEntryName, FILTER_EXTENSION)) {
              // Raw copy
              try (InputStream rawStream = zipFile.getRawInputStream(zipEntry)) {
                tmpZipOut.addRawArchiveEntry(zipEntry, rawStream);
              }
            } else {
              List<String> linesWithEof;
              try (Reader in = new BufferedReader(new InputStreamReader(zipFile.getInputStream(zipEntry), ENCODING))) {
                linesWithEof = readLinesWithEof(javadocJar, zipEntryName, in);
              }
              String originalHtml = StringUtils.join(linesWithEof, "");
              debug.accept(() -> zipEntryName + ": Read " + linesWithEof.size() + " lines, " + originalHtml.length()
                  + " characters");
              // Determine the canonical URL
              insertOrUpdateHead(javadocJar, zipEntry, linesWithEof, CANONICAL_PREFIX,
                  currentValue -> {
                    String expectedNonIndex = StringEscapeUtils.escapeHtml4(apidocsUrlWithSlash + zipEntryName);
                    if (currentValue == null || currentValue.equals(expectedNonIndex)) {
                      return expectedNonIndex;
                    } else if (zipEntryName.equals(INDEX_HTML)
                        || zipEntryName.equals(OVERVIEW_SUMMARY_HTML)) {
                      if (currentValue.startsWith(apidocsUrlWithSlash)) {
                        return currentValue;
                      } else {
                        return apidocsUrlWithSlash + currentValue;
                      }
                    } else {
                      throw new UncheckedIOException(new ZipException(
                          "Unexpected ZIP entry with non-default canonical URL: \"" + currentValue + '"' + AT
                              + javadocJar + AT + zipEntryName));
                    }
                  }, CANONICAL_SUFFIX, "Canonical URL: ", debug);
              // Determine the robots header value
              String robotsHeader = getRobotsHeader(javadocJar, zipEntry, linesWithEof);
              insertOrUpdateHead(javadocJar, zipEntry, linesWithEof, ROBOTS_PREFIX,
                  currentValue -> StringEscapeUtils.escapeHtml4(robotsHeader), ROBOTS_SUFFIX, "Robots: ", debug);
              nofollowLinks(javadocJar, zipEntry, linesWithEof, nofollow, follow, debug);
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
      if (!FileUtils.contentEquals(javadocJar, tmpFile)) {
        if (!tmpFile.renameTo(javadocJar)) {
          throw new IOException("Rename failed: " + tmpFile + " to " + javadocJar);
        }
      } else {
        info.accept(() -> "SEO Javadoc filtering: No changes made (javadocs already filtered?)");
      }
    }
  }

  /**
   * Filters a single JAR file with the transformations described in {@linkplain SeoJavadocFilter this class header}.
   *
   * @param javadocJar See {@link SeoJavadocFilterTask#setBuildDirectory(java.lang.String)}
   * @param apidocsUrl See {@link SeoJavadocFilterTask#setProjectUrl(java.lang.String)}
   *                   and {@link SeoJavadocFilterTask#setSubprojectSubpath(java.lang.String)}
   * @param nofollow   See {@link SeoJavadocFilterTask#setNofollow(java.lang.String)}
   * @param follow     See {@link SeoJavadocFilterTask#setFollow(java.lang.String)}
   */
  public static void filterJavadocJar(
      File javadocJar,
      String apidocsUrl,
      Iterable<String> nofollow,
      Iterable<String> follow
  ) throws IOException {
    if (nofollow == null) {
      nofollow = Collections.emptyList();
    }
    if (follow == null) {
      follow = Collections.emptyList();
    }
    SeoJavadocFilter.filterJavadocJar(
        javadocJar,
        apidocsUrl,
        nofollow,
        follow,
        logger::fine,
        logger::info
    );
  }
}
