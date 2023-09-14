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
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipLong;
import org.apache.commons.compress.archivers.zip.ZipUtil;
import org.apache.commons.compress.utils.ByteUtils;

/**
 * Standalone implementation of ZIP-file timestamp merging.
 * <p>
 * Note: This task should be performed before {@link GenerateJavadocSitemap} in order to have correct timestamps
 * inside the generated sitemaps.
 * </p>
 * <p>
 * This does not have any direct Ant dependencies.
 * If only using this class, it is permissible to exclude the ant dependencies.
 * </p>
 * <p>
 * See <a href="https://users.cs.jmu.edu/buchhofp/forensics/formats/pkzip.html">The structure of a PKZip file</a>.
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

  /**
   * The starting size of byte[] buffers.
   */
  private static final int BUFFER_SIZE = 4096;

  private static final int LOCAL_TIME_OFFSET = 0x0a;

  private static final byte[] CENTRAL_HEADER_SIGNATURE = {0x50, 0x4b, 0x01, 0x02};
  private static final byte[] END_CENTRAL_HEADER_SIGNATURE = {0x50, 0x4b, 0x05, 0x06};
  private static final int CENTRAL_HEADER_LEN = 0x2e - CENTRAL_HEADER_SIGNATURE.length;
  private static final int CENTRAL_HEADER_TIME_OFFSET = 0x0c - CENTRAL_HEADER_SIGNATURE.length;
  private static final int CENTRAL_HEADER_FILENAME_LEN_OFFSET = 0x1c - CENTRAL_HEADER_SIGNATURE.length;
  private static final int CENTRAL_HEADER_EXTRA_LEN_OFFSET = 0x1e - CENTRAL_HEADER_SIGNATURE.length;
  private static final int CENTRAL_HEADER_LOCAL_HEADER_OFFSET = 0x2a - CENTRAL_HEADER_SIGNATURE.length;

  static {
    assert CENTRAL_HEADER_SIGNATURE.length == END_CENTRAL_HEADER_SIGNATURE.length;
  }

  /**
   * Reads a ZIP "word", which is four bytes little-endian.
   */
  private static long getZipWord(byte[] buff, int offset) {
    long value = ByteUtils.fromLittleEndian(buff, offset, 4);
    assert value > 0L;
    assert value < 0x100000000L;
    return value;
  }

  /**
   * Reads a ZIP "short", which is two bytes little-endian.
   */
  private static int getZipShort(byte[] buff, int offset) {
    long value = ByteUtils.fromLittleEndian(buff, offset, 2);
    assert value > 0L;
    assert value < 0x10000L;
    return (int) value;
  }

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
   * Offsets a time from ZIP entry time to Java time in milliseconds since Epoch.
   */
  static long offsetFromZipToUtc(long entryTime) {
    return entryTime + TimeZone.getDefault().getOffset(entryTime);
  }

  /**
   * Gets the time in UTC.
   *
   * @throws ZipException if no time set ({@link ZipArchiveEntry#getTime()} returned -1).
   */
  // Note: Based on ao-lang:ZipUtils.getTimeUtc
  private static long getTimeUtc(File artifact, ZipArchiveEntry entry) throws ZipException {
    long entryTime = entry.getTime();
    if (entryTime == -1) {
      throw new ZipException("Entry has no timestamp, cannot patch: " + entry.getName() + " in " + artifact);
    }
    long javaTime = offsetFromZipToUtc(entryTime);
    if (javaTime == -1) {
      throw new ZipException("Time is -1 after offset: " + artifact + "!" + entry.getName());
    }
    return offsetFromZipToUtc(entryTime);
  }

  /**
   * Round to 2-second interval for ZIP time compatibility.
   */
  private static long roundDownDosTime(long millis) {
    // TODO: Round up with " + 1999"?
    return Math.floorDiv(millis, 2000) * 2000;
  }

  /**
   * Checks if two streams are byte-for-byte compatible.
   * Simply checks one byte at a time, caller should buffer as-needed.
   */
  private static boolean streamsMatch(InputStream in1, InputStream in2, byte[] buff1, byte[] buff2) throws IOException {
    if (buff1.length != buff2.length) {
      throw new IllegalArgumentException("Mismatched buffer sizes");
    }
    while (true) {
      int bytes1 = in1.read(buff1);
      if (bytes1 == -1) {
        return in2.read() == -1;
      }
      int bytes2 = in2.readNBytes(buff2, 0, bytes1);
      if (bytes1 != bytes2) {
        assert in2.read() == -1 : "in2 is at end of file";
        return false;
      }
      if (!Arrays.equals(buff1, 0, bytes1, buff2, 0, bytes1)) {
        return false;
      }
    }
  }

  /**
   * One patch that has been identified to be applied after comparison.
   */
  private static final class Patch {

    private final long offset;
    private final byte[] expected;
    private final byte[] replacement;

    private Patch(long offset, byte[] expected, byte[] replacement) {
      if (expected.length != replacement.length) {
        throw new IllegalArgumentException("Mismatched lengths");
      }
      if (Arrays.equals(expected, replacement)) {
        throw new IllegalArgumentException("replacement equals expected, no patch needed");
      }
      this.offset = offset;
      this.expected = expected;
      this.replacement = replacement;
    }
  }

  /**
   * Gets the bytes representing time and date in DOS format in UTC time zone.
   */
  // Note: Based on ao-lang:ZipUtils.setTimeUtc
  private static byte[] getDosTimeDate(long time) throws ZipException {
    long offset = time - TimeZone.getDefault().getOffset(time);
    if (offset == -1) {
      throw new ZipException("Time is -1 after offset");
    }
    byte[] dosTimeDate = new byte[4];
    ZipUtil.toDosTime(offset, dosTimeDate, 0);
    return dosTimeDate;
  }

  private static final Field zipFileCentralDirectoryStartOffset;

  static {
    try {
      // TODO: Accessing centralDirectoryStartOffset via reflection is a hack to avoid writing our own parser
      Field centralDirectoryStartOffset = ZipFile.class.getDeclaredField("centralDirectoryStartOffset");
      centralDirectoryStartOffset.setAccessible(true);
      zipFileCentralDirectoryStartOffset = centralDirectoryStartOffset;
    } catch (NoSuchFieldException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static long getCentralDirectoryStartOffset(ZipFile zipFile) {
    try {
      return zipFileCentralDirectoryStartOffset.getLong(zipFile);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private static final class CentralDirectoryEntry {

    private final long position;
    private final byte[] rawFilename;

    private CentralDirectoryEntry(long position, byte[] rawFilename) {
      this.position = position;
      this.rawFilename = rawFilename;
    }
  }

  /**
   * Reads the central directory beginning at the given offset, indexing each entry by local header offset.
   */
  private static SortedMap<Long, CentralDirectoryEntry> readCentralDirectory(Consumer<Supplier<String>> debug,
      File buildArtifact, ZipFile buildZipFile) throws IOException {
    long centralDirectoryStartOffset = getCentralDirectoryStartOffset(buildZipFile);
    debug.accept(() -> "centralDirectoryStartOffset = 0x" + Long.toHexString(centralDirectoryStartOffset));
    SortedMap<Long, CentralDirectoryEntry> map = new TreeMap<>();
    debug.accept(() -> "Opening buildArtifactRaf: " + buildArtifact);
    try (RandomAccessFile buildArtifactRaf = new RandomAccessFile(buildArtifact, "r")) {
      buildArtifactRaf.seek(centralDirectoryStartOffset);
      byte[] signature = new byte[CENTRAL_HEADER_SIGNATURE.length];
      byte[] centralDirectoryHeader = new byte[CENTRAL_HEADER_LEN];
      buildArtifactRaf.readFully(signature);
      debug.accept(() -> "signature @ 0x" + Long.toHexString(centralDirectoryStartOffset) + " is " + bytesToHex(signature));
      while (Arrays.equals(signature, CENTRAL_HEADER_SIGNATURE)) {
        long centralDirectoryPosition = buildArtifactRaf.getFilePointer();
        debug.accept(() -> "centralDirectoryPosition = 0x" + Long.toHexString(centralDirectoryPosition));
        buildArtifactRaf.readFully(centralDirectoryHeader);
        // Read raw filename
        int filenameLen = getZipShort(centralDirectoryHeader, CENTRAL_HEADER_FILENAME_LEN_OFFSET);
        debug.accept(() -> "filenameLen = " + filenameLen);
        if (filenameLen < 0) {
          throw new ZipException("Invalid filename length: " + filenameLen);
        }
        byte[] rawFilename = new byte[filenameLen];
        buildArtifactRaf.readFully(rawFilename);
        int extraLen = getZipShort(centralDirectoryHeader, CENTRAL_HEADER_EXTRA_LEN_OFFSET);
        debug.accept(() -> "extraLen = " + extraLen);
        if (extraLen < 0) {
          throw new ZipException("Invalid extra length: " + extraLen);
        }
        byte[] rawExtra = new byte[extraLen];
        buildArtifactRaf.readFully(rawExtra);
        // Look for relative offset match
        long relativeOffset = getZipWord(centralDirectoryHeader, CENTRAL_HEADER_LOCAL_HEADER_OFFSET);
        debug.accept(() -> "relativeOffset = 0x" + Long.toHexString(relativeOffset));
        long localHeaderOffset = relativeOffset + buildZipFile.getFirstLocalFileHeaderOffset();
        debug.accept(() -> "localHeaderOffset = 0x" + Long.toHexString(localHeaderOffset));
        CentralDirectoryEntry newEntry = new CentralDirectoryEntry(centralDirectoryPosition, rawFilename);
        CentralDirectoryEntry existing = map.put(localHeaderOffset, newEntry);
        if (existing != null) {
          throw new ZipException("Duplicate central directory entries point to same local header (0x"
              + Long.toHexString(localHeaderOffset) + "): 0x" + Long.toHexString(existing.position) + " and 0x"
              + Long.toHexString(newEntry.position));
        }
        long sigPos = buildArtifactRaf.getFilePointer();
        buildArtifactRaf.readFully(signature);
        debug.accept(() -> "signature @ 0x" + Long.toHexString(sigPos) + " is " + bytesToHex(signature));
      }
      if (!Arrays.equals(signature, END_CENTRAL_HEADER_SIGNATURE)) {
        throw new ZipException("sig is not END_SIG: " + bytesToHex(signature));
      }
    }
    return map;
  }

  private static void addTimePatches(List<Patch> patches,
      SortedMap<Long, CentralDirectoryEntry> centralDirectory, ZipArchiveEntry buildEntry,
      long buildEntryTime, long newTime
  ) throws ZipException, IOException {
    if (buildEntryTime == newTime) {
      throw new IllegalArgumentException("Times equal, nothing to patch for " + buildEntry);
    }
    byte[] expected = getDosTimeDate(buildEntryTime);
    byte[] replacement = getDosTimeDate(newTime);
    assert expected.length == replacement.length;
    if (Arrays.equals(expected, replacement)) {
      throw new ZipException("DOS times same, rounding? expected = " + bytesToHex(expected));
    }
    // Local header
    long localHeaderOffset = buildEntry.getLocalHeaderOffset();
    patches.add(new Patch(localHeaderOffset + LOCAL_TIME_OFFSET, expected, replacement));
    // Central Directory header
    CentralDirectoryEntry centralDirectoryEntry = centralDirectory.get(localHeaderOffset);
    if (centralDirectoryEntry == null) {
      throw new ZipException("No central directory entry found for local header: 0x"
          + Long.toHexString(localHeaderOffset));
    }
    // raw filename must match
    byte[] expectedRawName = buildEntry.getRawName();
    if (!Arrays.equals(centralDirectoryEntry.rawFilename, expectedRawName)) {
      throw new ZipException("raw filename mismatch: " + bytesToHex(centralDirectoryEntry.rawFilename) + " != "
          + bytesToHex(expectedRawName));
    }
    patches.add(new Patch(centralDirectoryEntry.position + CENTRAL_HEADER_TIME_OFFSET, expected, replacement));
  }

  // See https://stackoverflow.com/a/9855338
  // Java 17: Use HexFormat
  private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

  private static String bytesToHex(byte[] bytes, int len) {
    char[] hexChars = new char[len * 2];
    for (int j = 0; j < len; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = HEX_ARRAY[v >>> 4];
      hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
    }
    return new String(hexChars);
  }

  private static String bytesToHex(byte[] bytes) {
    return bytesToHex(bytes, bytes.length);
  }

  private static Date decodeDosTime(byte[] bytes) {
    return ZipUtil.fromDosTime(new ZipLong(ZipLong.getValue(bytes)));
  }

  /**
   * Applies the given set of patches to a file.
   */
  private static void applyPatches(String logPrefix, Consumer<Supplier<String>> debug, Consumer<Supplier<String>> info,
      List<Patch> patches, File file, int totalEntries) throws IOException {
    debug.accept(() -> logPrefix + file);
    info.accept(() -> logPrefix + "Patching " + (patches.size() / 2) + " of " + totalEntries
        + (totalEntries == 1 ? " timestamp" : " timestamps"));
    try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
      byte[] buff = null;
      for (Patch patch : patches) {
        long offset = patch.offset;
        int len = patch.expected.length;
        debug.accept(() -> logPrefix + "Patching "
            + bytesToHex(patch.expected, len) + " (" + decodeDosTime(patch.expected) + ") to "
            + bytesToHex(patch.replacement, len) + " (" + decodeDosTime(patch.replacement) + ") at "
            + offset);
        raf.seek(offset);
        if (buff == null || len > buff.length) {
          buff = new byte[Math.min(len, BUFFER_SIZE)];
        }
        raf.readFully(buff, 0, len);
        if (!Arrays.equals(buff, 0, len, patch.expected, 0, len)) {
          throw new IOException("Unexpected data in patch position: offset = " + patch.offset
              + ", expected = " + bytesToHex(patch.expected, len) + " (" + decodeDosTime(patch.expected)
              + "), actual = " + bytesToHex(buff, len) + " (" + decodeDosTime(buff) + ')');
        }
        raf.seek(offset);
        raf.write(patch.replacement);
      }
    }
  }

  /**
   * Gets the direct children names of the given entry, if any.
   */
  private static SortedSet<String> getDirectChildren(Consumer<Supplier<String>> debug, ZipFile zipFile,
      ZipArchiveEntry directory) throws ZipException {
    String directoryName = directory.getName();
    if (!directoryName.endsWith("/")) {
      throw new IllegalArgumentException("directory does not end in \"/\": " + directoryName);
    }
    SortedSet<String> children = new TreeSet<>();
    Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
    while (entries.hasMoreElements()) {
      ZipArchiveEntry entry = entries.nextElement();
      String name = entry.getName();
      if (name.startsWith(directoryName)) {
        String childName = name.substring(directoryName.length());
        if (!childName.isEmpty() && childName.indexOf('/') == -1) {
          if (!children.add(childName)) {
            throw new ZipException("Duplicate child name of " + directoryName + ": " + childName);
          }
        }
      }
    }
    debug.accept(() -> "Children of " + directory + ": " + children);
    return children;
  }

  /**
   * Implementation of {@link #mergeFile(java.time.Instant, boolean, java.io.File, java.io.File)}
   * with provided logging.
   */
  private static void mergeFile(
      long currentTime,
      Instant outputTimestamp,
      boolean buildReproducible,
      File lastBuildArtifact,
      File buildArtifact,
      Consumer<Supplier<String>> debug,
      Consumer<Supplier<String>> info,
      Consumer<Supplier<String>> warn
  ) throws IOException {
    info.accept(() -> "Merging timestamps from " + lastBuildArtifact + " into " + buildArtifact);
    // Validate
    Objects.requireNonNull(outputTimestamp, "outputTimestamp required");
    long outputTimestampMillis = outputTimestamp.toEpochMilli();
    long outputTimestampRounded = roundDownDosTime(outputTimestampMillis);
    long currentTimeRounded = roundDownDosTime(currentTime);
    // Track the specific patches to be performed.  Will remain empty when nothing to change.
    List<Patch> patches = new ArrayList<>();
    debug.accept(() -> "Reading buildArtifact: " + buildArtifact);
    String reproducibleLogPrefix = buildReproducible ? "patch non-reproducible: " : "validate reproducible: ";
    int buildEntryCount = 0;
    try (ZipFile buildZipFile = new ZipFile(buildArtifact)) {
      debug.accept(() -> reproducibleLogPrefix + buildArtifact);
      Enumeration<ZipArchiveEntry> buildEntries = buildZipFile.getEntriesInPhysicalOrder();
      SortedMap<Long, CentralDirectoryEntry> centralDirectory = buildReproducible ? null :
          readCentralDirectory(debug, buildArtifact, buildZipFile);
      while (buildEntries.hasMoreElements()) {
        ZipArchiveEntry buildEntry = buildEntries.nextElement();
        buildEntryCount++;
        // Verify time
        long buildEntryTime = getTimeUtc(buildArtifact, buildEntry);
        //debug.accept("buildEntryTime = " + new Date(buildEntryTime));
        if (buildEntryTime != outputTimestampRounded
            /*&& zipRoundTime(entryTime) != zipRoundTime(outputTimestampMillis)*/) {
          if (buildReproducible) {
            throw new ZipException(reproducibleLogPrefix + "Mismatched entry.time: expected " + outputTimestampRounded + " ("
                + new Date(outputTimestampRounded) + "), got " + buildEntryTime + " (" + new Date(buildEntryTime)
                + ") on ZIP entry: " + buildArtifact + " @ " + buildEntry.getName());
          } else {
            // Patch
            addTimePatches(patches, centralDirectory, buildEntry, buildEntryTime, outputTimestampMillis);
          }
        }
        // Fail if has extra-based last modified time, since we aren't patching that
        for (ZipExtraField extraField : buildEntry.getExtraFields()) {
          if (extraField.getHeaderId() == X5455_ExtendedTimestamp.HEADER_ID) {
            assert extraField instanceof X5455_ExtendedTimestamp;
            throw new ZipException("X5455_ExtendedTimestamp patching not implemented: "
                + buildArtifact + " @ " + buildEntry.getName());
          }
        }
      }
    }
    // Apply reproducible patches now
    if (!patches.isEmpty()) {
      assert !buildReproducible;
      applyPatches(reproducibleLogPrefix, debug, info, patches, buildArtifact, buildEntryCount);
      patches.clear();
    }
    debug.accept(() -> "Reading buildArtifact: " + buildArtifact);
    try (ZipFile buildZipFile = new ZipFile(buildArtifact)) {
      debug.accept(() -> "Reading lastBuildArtifact: " + lastBuildArtifact);
      try (ZipFile lastBuildZipFile = new ZipFile(lastBuildArtifact)) {
        byte[] buff1 = new byte[BUFFER_SIZE];
        byte[] buff2 = new byte[BUFFER_SIZE];
        Enumeration<ZipArchiveEntry> buildEntries = buildZipFile.getEntriesInPhysicalOrder();
        SortedMap<Long, CentralDirectoryEntry> centralDirectory =
            readCentralDirectory(debug, buildArtifact, buildZipFile);
        while (buildEntries.hasMoreElements()) {
          ZipArchiveEntry buildEntry = buildEntries.nextElement();
          debug.accept(() -> "buildEntry: " + buildEntry);
          String entryName = buildEntry.getName();
          Iterator<ZipArchiveEntry> lastBuildEntriesIterator = lastBuildZipFile.getEntries(entryName).iterator();
          if (lastBuildEntriesIterator.hasNext()) {
            ZipArchiveEntry lastBuildEntry = lastBuildEntriesIterator.next();
            if (lastBuildEntriesIterator.hasNext()) {
              throw new ZipException("More than one entry from " + entryName + " found in " + lastBuildArtifact);
            }
            assert buildEntry.isDirectory() == lastBuildEntry.isDirectory();
            debug.accept(() -> "lastBuildEntry: " + lastBuildEntry);
            // If timestamps already match, there would be nothing to even patch
            long buildEntryTime = getTimeUtc(buildArtifact, buildEntry);
            if (buildEntryTime > currentTimeRounded) {
              warn.accept(() -> "buildEntry(" + buildEntry + ".time (" + new Date(buildEntryTime)
                  + " in future");
            }
            long lastBuildEntryTime = getTimeUtc(lastBuildArtifact, lastBuildEntry);
            if (lastBuildEntryTime > currentTimeRounded) {
              warn.accept(() -> "lastBuildEntry(" + lastBuildEntry + ".time (" + new Date(lastBuildEntryTime)
                  + " in future");
            }
            boolean updated;
            if (buildEntry.getSize() != lastBuildEntry.getSize()) {
              updated = true;
            } else if (buildEntry.isDirectory()) {
              assert buildEntry.getSize() == 0;
              // A directory is modified only when an immediate child entry is added or removed
              SortedSet<String> buildChildren = getDirectChildren(debug, buildZipFile, buildEntry);
              SortedSet<String> lastBuildChildren = getDirectChildren(debug, lastBuildZipFile, lastBuildEntry);
              updated = !buildChildren.equals(lastBuildChildren);
              if (updated) {
                info.accept(() -> {
                  StringBuilder sb = new StringBuilder(entryName + ": Directory is modified:");
                  for (String buildChild : buildChildren) {
                    if (!lastBuildChildren.contains(buildChild)) {
                      sb.append(System.lineSeparator()).append("  Added: ").append(buildChild);
                    }
                  }
                  for (String lastBuildChild : lastBuildChildren) {
                    if (!buildChildren.contains(lastBuildChild)) {
                      sb.append(System.lineSeparator()).append("  Removed: ").append(lastBuildChild);
                    }
                  }
                  return sb.toString();
                });
              }
            } else {
              int buildMethod = buildEntry.getMethod();
              int lastBuildMethod = lastBuildEntry.getMethod();
              boolean readRaw = buildMethod != -1 && buildMethod == lastBuildMethod;
              try (
                  InputStream buildInput = readRaw ? buildZipFile.getRawInputStream(buildEntry)
                      : buildZipFile.getInputStream(buildEntry);
                  InputStream lastBuildInput = readRaw ? lastBuildZipFile.getRawInputStream(lastBuildEntry)
                      : lastBuildZipFile.getInputStream(lastBuildEntry)) {
                updated = !streamsMatch(buildInput, lastBuildInput, buff1, buff2);
              }
            }
            debug.accept(() -> "updated: " + updated);
            long expectedTime;
            if (updated) {
              if (lastBuildEntryTime < buildEntryTime) {
                // last build is before build, use build time
                expectedTime = buildEntryTime;
              } else {
                // use current time to avoid going back in time
                expectedTime = currentTimeRounded;
              }
            } else {
              // Not updated, keep time from last build even if in the future
              expectedTime = lastBuildEntryTime;
            }
            if (buildEntryTime != expectedTime) {
              addTimePatches(patches, centralDirectory, buildEntry, buildEntryTime, expectedTime);
            } else {
              debug.accept(() -> "entry already at expected timestamp: " + buildEntry);
            }
          } else {
            info.accept(() -> "New entry not found in last build: " + buildEntry);
          }
        }
      }
    }
    if (!patches.isEmpty()) {
      // Patch in-place
      applyPatches("patch buildArtifact: ", debug, info, patches, buildArtifact, buildEntryCount);
    }
  }

  /**
   * Creates a ZIP file with contents matching {@code buildArtifact} but with timestamps derived from
   * {@code lastBuildArtifact}.
   * <p>
   * For each entry, if the content is byte-for-byte equal, maintains the {@linkplain ZipArchiveEntry#getTime() time}.
   * </p>
   * <p>
   * TODO: Describe more about creation time management
   * </p>
   * <p>
   * TODO: Describe when an entry is found in a different location
   * </p>
   * <p>
   * TODO: When nothing altered, resulting ZIP file is verified to match buildArtifact byte-for-byte.
   * </p>
   * <p>
   * TODO: Which file modified timestamp used for mergedZip?
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
        System.currentTimeMillis(),
        outputTimestamp,
        buildReproducible,
        lastBuildArtifact,
        buildArtifact,
        logger::fine,
        logger::info,
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
      Consumer<Supplier<String>> debug,
      Consumer<Supplier<String>> info,
      Consumer<Supplier<String>> warn
  ) throws IOException, ParseException {
    // Validate
    Objects.requireNonNull(outputTimestamp, "outputTimestamp required");
    long currentTime = System.currentTimeMillis();
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
      debug.accept(() -> identifier + ": buildArtifact: " + buildArtifact);
      File lastBuildArtifact = lastBuildArtifacts.get(identifier);
      if (lastBuildArtifact != null) {
        debug.accept(() -> identifier + ": lastBuildArtifact: " + lastBuildArtifact);
        mergeFile(
            currentTime,
            outputTimestamp,
            buildReproducible,
            lastBuildArtifact,
            buildArtifact,
            // Prepend identifier on log messages
            msg -> debug.accept(() -> identifier + ": " + msg.get()),
            msg -> info.accept(() -> identifier + ": " + msg.get()),
            msg -> warn.accept(() -> identifier + ": " + msg.get())
        );
      } else {
        assert !requireLastBuild : "one-to-one mapping already enforced";
        warn.accept(() -> identifier + ": not found in lastBuildDirectory: " + lastBuildDirectory);
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
        logger::warning
    );
  }
}
