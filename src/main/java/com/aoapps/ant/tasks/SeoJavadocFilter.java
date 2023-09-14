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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.ExtraFieldUtils;
import org.apache.commons.compress.archivers.zip.GeneralPurposeBit;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

/**
 * Filters javadocs for search engine optimization.  Performs the following transformations:
 * <p>
 * Note: This task should be performed after {@link ZipTimestampMerge} in order to have correct timestamps inside
 * the generated sitemaps.
 * </p>
 * <ol>
 * <li>
 *   Adds <a href="https://support.google.com/webmasters/answer/139066">Canonical URLs</a> to each page.
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
 * <li>
 *   Generates <a href="https://www.sitemaps.org/">sitemap</a> at <code>sitemap.xml</code> with an index at
 *   <code>META-INF/sitemap-index.xml</code>. Excludes pages containing robots noindex.
 *   The sitemap indexes will be automatically picked-up by
 *   <a href="https://semanticcms.com/core/sitemap/">SemanticCMS Core Sitemap</a> and merged into the site's
 *   total sitemap index.
 * </li>
 * </ol>
 * <p>
 * All existing ZIP entry timestamps are preserved.  The timestamp of the added <code>sitemap.xml</code> and
 * <code>META-INF/sitemap-index.xml</code> will be based on the most recent modified time they contain.
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

  private static final Charset ENCODING = StandardCharsets.UTF_8;

  /**
   * The value used to match any URL for {@link SeoJavadocFilterTask#setNofollow(java.lang.String)} or
   * {@link SeoJavadocFilterTask#setFollow(java.lang.String)}.
   */
  public static final String ANY_URL = "*";

  /**
   * The entry name pattern that will be filtered in the javadocs (case-insensitive).
   */
  private static final String FILTER_EXTENSION = ".html";

  static {
    assert FILTER_EXTENSION.equals(FILTER_EXTENSION.toLowerCase(Locale.ROOT));
  }

  private static final String NOINDEX = "noindex";

  private static final String FOLLOW = "follow";

  private static final String NOFOLLOW = "nofollow";

  private static final String NOINDEX_FOLLOW = NOINDEX + ", " + FOLLOW;

  private static final String NOINDEX_NOFOLLOW = NOINDEX + ", " + NOFOLLOW;

  /**
   * The required newline character.
   */
  private static final char NL = '\n';

  private static final String HEAD_ELEM_START = "<head>" + NL;

  private static final String HEAD_ELEM_END = "</head>" + NL;

  private static final String CANONICAL_PREFIX = "<link rel=\"canonical\" href=\"";

  private static final String CANONICAL_SUFFIX = "\">" + NL;

  private static final String ROBOTS_PREFIX = "<meta name=\"robots\" content=\"";

  private static final String ROBOTS_SUFFIX = "\">" + NL;

  /**
   * Pattern to determine if a URL starts with a scheme.
   * Per <a href="http://www.ietf.org/rfc/rfc2396.txt">RFC 2396</a>:
   * <code>scheme        = alpha *( alpha | digit | "+" | "-" | "." )</code>
   */
  private static final Pattern SCHEME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*:.*");

  /**
   * The comment added to generated items.  This does not include any version number for reproducibility of manipulated
   * artifacts.
   */
  private static final String GENERATED_COMMENT = "Generated by " + SeoJavadocFilter.class.getName();

  /**
   * The ZIP entry containing the sitemap.
   */
  private static final String SITEMAP_NAME = "sitemap.xml";

  /**
   * The ZIP entry for <code>META-INF/</code> directory.
   */
  private static final String META_INF_DIRECTORY = "META-INF/";

  /**
   * Treat generated javadocs with a fairly low priority.
   * See <a href="https://www.sitemaps.org/protocol.html#prioritydef">sitemaps.org - Protocol - &lt;priority&gt;</a>.
   */
  private static final String SITEMAP_PRIORITY = "0.1";

  /**
   * The ZIP entry containing the sitemap index.
   */
  private static final String SITEMAP_INDEX_NAME = META_INF_DIRECTORY + "sitemap-index.xml";

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
        name.equalsIgnoreCase("help-doc.html")
        || name.equalsIgnoreCase("index-all.html")
        || name.equalsIgnoreCase("overview-tree.html")
        || name.equalsIgnoreCase("package-tree.html")
    ) {
      return NOINDEX_NOFOLLOW;
    } else if (
        name.equalsIgnoreCase("index.html")
        || name.equalsIgnoreCase("overview-summary.html")
    ) {
      boolean hasRefresh = linesWithEof.stream()
          .anyMatch(line -> line.startsWith("<meta http-equiv=\"Refresh\" content=\"0;"));
      boolean hasRedirectClass = linesWithEof.stream()
          .anyMatch(line -> line.endsWith("-redirect-page\">" + NL));
      if (hasRefresh != hasRedirectClass) {
        if (!hasRedirectClass) {
          throw new ZipException("Entry has refresh meta but does not have redirect body class: "
              + javadocJar + " @ " + name);
        } else {
          throw new ZipException("Entry has redirect body class but does not have refresh meta: "
              + javadocJar + " @ " + name);
        }
      }
      if (hasRefresh) {
        return NOINDEX_FOLLOW;
      } else if (name.equalsIgnoreCase("index.html")) {
        int bodyElemPos = linesWithEof.indexOf("<body class=\"package-index-page\">" + NL);
        if (bodyElemPos == -1) {
          throw new ZipException("Entry has neither \"index-redirect-page\" body class nor \"package-index-page\": "
              + javadocJar + " @ " + name);
        }
        return null;
      } else if (name.equalsIgnoreCase("overview-summary.html")) {
        throw new ZipException("Entry is only expected to be a redirect page: " + javadocJar + " @ " + name);
      } else {
        throw new AssertionError("Unexpected name: " + name);
      }
    } else {
      return null;
    }
  }

  /**
   * One sitemap URL.
   */
  private static class SitemapPath implements Comparable<SitemapPath> {

    private final String entryName;
    private final long entryTime;

    private SitemapPath(String entryName, long entryTime) throws ZipException {
      this.entryName = entryName;
      if (entryTime == -1) {
        throw new IllegalArgumentException("entryTime == -1");
      }
      this.entryTime = entryTime;
    }

    @Override
    public int hashCode() {
      return (int) entryTime + entryName.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof SitemapPath)) {
        return false;
      }
      SitemapPath other = (SitemapPath) obj;
      return entryTime == other.entryTime && entryName.equals(other.entryName);
    }

    /**
     * Ordered by time descending then by name ascending.
     */
    @Override
    public int compareTo(SitemapPath other) {
      // Time descending
      int diff = Long.compare(other.entryTime, entryTime); // Reversed order for descending
      if (diff != 0) {
        return diff;
      }
      // Name ascending
      return entryName.compareTo(other.entryName);
    }
  }

  /**
   * Reads all lines, splitting on lines while keeping the line endings.
   */
  private static List<String> readLinesWithEof(File javadocJar, String zipEntryName, Reader in) throws IOException {
    List<String> linesWithEof = new ArrayList<>();
    StringBuilder lineSb = new StringBuilder(80);
    int ch;
    while ((ch = in.read()) != -1) {
      // Make sure only Unix newlines
      if (ch == '\r') {
        throw new ZipException("Carriage return in javadocs, requiring Unix newlines only: " + javadocJar + " @ "
            + zipEntryName + " @ line " + (linesWithEof.size() + 1));
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
  private static void insertOrUpdateHead(File javadocJar, ZipArchiveEntry zipEntry, List<String> linesWithEof,
      String lineStart, Function<String, String> getValue, String lineEndWithEof, String msgPrefix,
      Consumer<Supplier<String>> debug
  ) throws ZipException {
    // Find the <head> line
    int headStartIndex = linesWithEof.indexOf(HEAD_ELEM_START);
    if (headStartIndex == -1) {
      throw new ZipException(msgPrefix + HEAD_ELEM_START.trim() + " not found: " + javadocJar + " @ " + zipEntry);
    }
    // Find the </head> line
    int headEndIndex = linesWithEof.indexOf(HEAD_ELEM_END);
    if (headEndIndex == -1) {
      throw new ZipException(msgPrefix + HEAD_ELEM_END.trim() + " not found: " + javadocJar + " @ " + zipEntry);
    }
    if (headEndIndex < headStartIndex) {
      throw new ZipException(msgPrefix + HEAD_ELEM_END.trim() + " before " + HEAD_ELEM_START.trim() + ": "
          + javadocJar + " @ " + zipEntry);
    }
    // Search for existing line to update
    int finishedIndex = -1;
    for (int lineIndex = headStartIndex + 1; lineIndex < headEndIndex; lineIndex++) {
      String lineWithEof = linesWithEof.get(lineIndex);
      if (lineWithEof.startsWith(lineStart)) {
        if (finishedIndex != -1) {
          throw new ZipException(msgPrefix + "duplicate element detected " + javadocJar + " @ " + zipEntry
              + " @ lines " + (finishedIndex + 1) + " and " + (lineIndex + 1));
        }
        if (!lineWithEof.endsWith(lineEndWithEof)) {
          throw new ZipException(msgPrefix + "Expected line ending (" + lineEndWithEof.trim() + ") missing: "
              + javadocJar + " @ " + zipEntry + " @ line " + (lineIndex + 1));
        }
        String currentValue = lineWithEof.substring(lineStart.length(),
            lineWithEof.length() - lineEndWithEof.length());
        String newValue = getValue.apply(currentValue);
        if (currentValue.equals(newValue)) {
          debug.accept(() -> msgPrefix + "Existing value is correct: " + newValue);
        } else if (newValue == null) {
          debug.accept(() -> msgPrefix + "Removing old value: " + currentValue);
          linesWithEof.remove(lineIndex);
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
      throw new ZipException(HEAD_ELEM_END.trim() + " not found: " + javadocJar + " @ " + zipEntry);
    }
    debug.accept(() -> "Filtering links in " + javadocJar + " @ " + zipEntry);
    for (int lineIndex = headEndIndex + 1; lineIndex < linesWithEof.size(); lineIndex++) {
      String line = linesWithEof.get(lineIndex);
      // Do not allow capital links
      int capitalStart = line.indexOf("<A ");
      if (capitalStart != -1) {
        throw new ZipException("Unexpected capitalized \"<A \" found: " + javadocJar + " @ " + zipEntry + " @ line "
            + (lineIndex + 1));
      }
      // Do not allow single-quoted href
      int singleQuotedStart = line.indexOf("href='");
      if (singleQuotedStart != -1) {
        throw new ZipException("Unexpected single-quoted \"href='\" found: " + javadocJar + " @ " + zipEntry + " @ line "
            + (lineIndex + 1));
      }
      // Do not allow single-quoted rel
      singleQuotedStart = line.indexOf("rel='");
      if (singleQuotedStart != -1) {
        throw new ZipException("Unexpected single-quoted \"rel='\" found: " + javadocJar + " @ " + zipEntry + " @ line "
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
            throw new ZipException("Link end not found: " + javadocJar + " @ " + zipEntry + " @ line "
                + (lineIndex + 1));
          }
          // Find href=", but must be before linkEnd
          String hrefStr = " href=\"";
          int hrefPos = line.indexOf(hrefStr, linkStart);
          if (hrefPos == -1 || hrefPos >= linkEnd) {
            throw new ZipException("Link without href: " + javadocJar + " @ " + zipEntry + " @ line "
                + (lineIndex + 1));
          }
          // Find closing quote, but must before linkEnd
          int hrefClosePos = line.indexOf('"', hrefPos + hrefStr.length());
          if (hrefClosePos == -1 || hrefClosePos >= linkEnd) {
            throw new ZipException("href without closing quote: " + javadocJar + " @ " + zipEntry + " @ line "
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
              throw new ZipException("rel without closing quote: " + javadocJar + " @ " + zipEntry + " @ line "
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
                  throw new ZipException("URL not matched in any nofollow or follow prefix: " + javadocJar + " @ "
                      + zipEntry + " @ line " + (lineIndex + 1) + " href = " + hrefValue);
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
   * Creates a date formatter.
   * See <a href="https://stackoverflow.com/a/3914498/7121505">java - How to get current moment in ISO 8601 format with
   * date, hour, and minute? - Stack Overflow</a>.
   */
  private static DateFormat createIso8601Format() {
    TimeZone tz = TimeZone.getTimeZone("UTC");
    DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"); // Quoted "Z" to indicate UTC, no timezone offset
    df.setTimeZone(tz);
    return df;
  }

  /**
   * Generates the sitemap.
   */
  private static String generateSitemap(String apidocsUrlWithSlash, SortedSet<SitemapPath> sitemapPaths) {
    String apidocsUrlWithSlashXmlEscaped = StringEscapeUtils.escapeXml10(apidocsUrlWithSlash);
    DateFormat iso8601 = createIso8601Format();
    StringBuilder sitemap = new StringBuilder();
    sitemap.append("<?xml version=\"1.0\" encoding=\"").append(ENCODING).append("\"?>").append(NL);
    sitemap.append("<!-- ").append(StringEscapeUtils.escapeXml10(GENERATED_COMMENT)).append(" -->").append(NL);
    sitemap.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">").append(NL);
    for (SitemapPath sitemapPath : sitemapPaths) {
      sitemap.append("  <url>").append(NL);
      sitemap.append("    <loc>");
      sitemap.append(apidocsUrlWithSlashXmlEscaped);
      sitemap.append(StringEscapeUtils.escapeXml10(sitemapPath.entryName));
      sitemap.append("</loc>").append(NL);
      sitemap.append("    <lastmod>");
      // Convert time to UTC
      long time = ZipTimestampMerge.offsetFromZipToUtc(sitemapPath.entryTime);
      sitemap.append(StringEscapeUtils.escapeXml10(iso8601.format(new Date(time))));
      sitemap.append("</lastmod>").append(NL);
      sitemap.append("    <priority>").append(SITEMAP_PRIORITY).append("</priority>").append(NL);
      sitemap.append("  </url>").append(NL);
    }
    sitemap.append("</urlset>").append(NL);
    return sitemap.toString();
  }

  /**
   * Generates the sitemap index.
   */
  private static String generateSitemapIndex(String apidocsUrlWithSlash, long sitemapLastModified) {
    StringBuilder sitemapIndex = new StringBuilder();
    sitemapIndex.append("<?xml version=\"1.0\" encoding=\"").append(ENCODING).append("\"?>").append(NL);
    sitemapIndex.append("<!-- ").append(StringEscapeUtils.escapeXml10(GENERATED_COMMENT)).append(" -->").append(NL);
    sitemapIndex.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">").append(NL);
    sitemapIndex.append("  <sitemap>").append(NL);
    sitemapIndex.append("    <loc>");
    String apidocsUrlWithSlashXmlEscaped = StringEscapeUtils.escapeXml10(apidocsUrlWithSlash);
    sitemapIndex.append(apidocsUrlWithSlashXmlEscaped);
    sitemapIndex.append(StringEscapeUtils.escapeXml10(SITEMAP_NAME));
    sitemapIndex.append("</loc>").append(NL);
    sitemapIndex.append("    <lastmod>");
    // Convert time to UTC
    DateFormat iso8601 = createIso8601Format();
    long time = ZipTimestampMerge.offsetFromZipToUtc(sitemapLastModified);
    sitemapIndex.append(StringEscapeUtils.escapeXml10(iso8601.format(new Date(time))));
    sitemapIndex.append("</lastmod>").append(NL);
    sitemapIndex.append("  </sitemap>").append(NL);
    sitemapIndex.append("</sitemapindex>").append(NL);
    return sitemapIndex.toString();
  }

  /**
   * Copies some zip meta information from one entry to another.
   *
   * @see  ZipArchiveEntry#ZipArchiveEntry(java.util.zip.ZipEntry)
   * @see  ZipArchiveEntry#ZipArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry)
   */
  private static void copyZipMeta(ZipArchiveEntry from, ZipArchiveEntry to) throws ZipException {
    // ZipArchiveEntry#ZipArchiveEntry(java.util.zip.ZipEntry)
    final byte[] extra = from.getExtra();
    if (extra != null) {
      to.setExtraFields(ExtraFieldUtils.parse(extra, true, ZipArchiveEntry.ExtraFieldParsingMode.BEST_EFFORT));
    }
    to.setMethod(from.getMethod());

    // ZipArchiveEntry#ZipArchiveEntry(org.apache.commons.compress.archivers.zip.ZipArchiveEntry)
    to.setInternalAttributes(from.getInternalAttributes());
    to.setExternalAttributes(from.getExternalAttributes());
    to.setExtraFields(from.getExtraFields(true));
    if (from.getPlatform() == ZipArchiveEntry.PLATFORM_UNIX) {
      to.setUnixMode(from.getUnixMode());
    }
    final GeneralPurposeBit other = from.getGeneralPurposeBit();
    to.setGeneralPurposeBit(other == null ? null : (GeneralPurposeBit) other.clone());
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
      Consumer<Supplier<String>> info,
      Consumer<Supplier<String>> warn
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
    try (Closeable c = () -> {
      if (tmpFile.exists()) {
        FileUtils.delete(tmpFile);
      }
    }) {
      debug.accept(() -> "Writing temp file " + tmpFile);
      try (ZipArchiveOutputStream tmpZipOut = new ZipArchiveOutputStream(tmpFile)) {
        debug.accept(() -> "Reading " + javadocJar);
        try (ZipFile zipFile = new ZipFile(javadocJar)) {
          Set<String> sitemapNames = new HashSet<>();
          SortedSet<SitemapPath> sitemapPaths = new TreeSet<>();
          Enumeration<ZipArchiveEntry> zipEntries = zipFile.getEntriesInPhysicalOrder();
          while (zipEntries.hasMoreElements()) {
            ZipArchiveEntry zipEntry = zipEntries.nextElement();
            debug.accept(() -> "zipEntry: " + zipEntry);
            String zipEntryName = zipEntry.getName();
            // Require times on all entries
            long zipEntryTime = zipEntry.getTime();
            if (zipEntryTime == -1) {
              throw new ZipException("No time in entry: " + javadocJar + " @ " + zipEntryName);
            }
            // Anything not ending in *.html (which will include directories), just copy verbatim
            if (zipEntryName.equals(SITEMAP_NAME) || zipEntryName.equals(SITEMAP_INDEX_NAME)) {
              debug.accept(() -> zipEntryName + ": Dropping existing sitemap");
            } else if (!zipEntryName.toLowerCase(Locale.ROOT).endsWith(FILTER_EXTENSION)) {
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
                    } else if (zipEntryName.equals("index.html")
                        || zipEntryName.equals("overview-summary.html")) {
                      if (currentValue.startsWith(apidocsUrlWithSlash)) {
                        return currentValue;
                      } else {
                        return apidocsUrlWithSlash + currentValue;
                      }
                    } else {
                      throw new RuntimeException("Unexpected ZIP entry with non-default canonical URL: \""
                          + currentValue + "\" @ " + javadocJar + " @ " + zipEntryName);
                    }
                  }, CANONICAL_SUFFIX, "Canonical URL: ", debug);
              // Determine the robots header value
              String robotsHeader = getRobotsHeader(javadocJar, zipEntry, linesWithEof);
              insertOrUpdateHead(javadocJar, zipEntry, linesWithEof, ROBOTS_PREFIX,
                  currentValue -> StringEscapeUtils.escapeHtml4(robotsHeader), ROBOTS_SUFFIX, "Robots: ", debug);
              // Add to sitemap when not noindex
              if (robotsHeader == null || !robotsHeader.contains(NOINDEX)) {
                if (!sitemapNames.add(zipEntryName)) {
                  throw new ZipException("Duplicate name in " + javadocJar + ": " + zipEntryName);
                }
                if (!sitemapPaths.add(new SitemapPath(zipEntryName, zipEntryTime))) {
                  throw new AssertionError();
                }
              }
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
          // Refuse to create empty sitemaps
          if (sitemapPaths.isEmpty()) {
            throw new ZipException("Sitemap is empty, empty JAR?: " + javadocJar);
          }
          // Copy most ZIP entry attributes from the first entry used in the sitemap (unix mode, permissions, ...)
          ZipArchiveEntry referenceEntry = zipFile.getEntry(sitemapPaths.first().entryName);
          assert referenceEntry != null;
          // Generate sitemap.xml
          long sitemapLastModified = sitemapPaths.first().entryTime;
          assert sitemapLastModified >= sitemapPaths.last().entryTime : "Most recent is first";
          ZipArchiveEntry sitemapEntry = new ZipArchiveEntry(SITEMAP_NAME);
          copyZipMeta(referenceEntry, sitemapEntry);
          sitemapEntry.setTime(sitemapLastModified);
          sitemapEntry.setComment(GENERATED_COMMENT);
          tmpZipOut.putArchiveEntry(sitemapEntry);
          tmpZipOut.write(generateSitemap(apidocsUrlWithSlash, sitemapPaths).getBytes(ENCODING));
          tmpZipOut.closeArchiveEntry();
          // Require META-INF directory
          if (zipFile.getEntry(META_INF_DIRECTORY) == null) {
            throw new ZipException("Missing " + META_INF_DIRECTORY + " directory: " + javadocJar);
          }
          // Generate sitemap-index.xml
          ZipArchiveEntry sitemapIndexEntry = new ZipArchiveEntry(SITEMAP_INDEX_NAME);
          copyZipMeta(referenceEntry, sitemapIndexEntry);
          sitemapIndexEntry.setTime(sitemapLastModified);
          sitemapIndexEntry.setComment(GENERATED_COMMENT);
          tmpZipOut.putArchiveEntry(sitemapIndexEntry);
          tmpZipOut.write(generateSitemapIndex(apidocsUrlWithSlash, sitemapLastModified).getBytes(ENCODING));
          tmpZipOut.closeArchiveEntry();
        }
      }
      // Ovewrite if anything changed, delete otherwise
      if (FileUtils.contentEquals(javadocJar, tmpFile)) {
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
   * @param apidocsUrl See {@link SeoJavadocFilterTask#setApidocsUrl(java.lang.String)}
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
        logger::info,
        logger::warning
    );
  }
}
