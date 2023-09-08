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

import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.TimeZone;
import java.util.zip.ZipEntry;

/**
 * ZIP file utilities.
 */
final class ZipUtils {

  /** Make no instances. */
  private ZipUtils() {
    throw new AssertionError();
  }

  /**
   * Gets the time for a ZipEntry, converting from UTC as stored in the ZIP
   * entry to make times correct between time zones.
   *
   * @return  the time assuming UTC zone or {@code null} if not specified.
   *
   * @see #setTimeUtc(java.util.zip.ZipEntry, long)
   */
  // Copied from ao-lang:ZipUtils.java
  static Optional<Long> getTimeUtc(ZipEntry entry) {
    long time = entry.getTime();
    return time == -1 ? Optional.empty() : Optional.of(time + TimeZone.getDefault().getOffset(time));
  }

  /**
   * Sets the time for a ZipEntry, converting to UTC while storing to the ZIP
   * entry to make times correct between time zones.  The actual time stored
   * may be rounded to the nearest two-second interval.
   *
   * @see #getTimeUtc(java.util.zip.ZipEntry)
   */
  // Copied from ao-lang:ZipUtils.java
  static void setTimeUtc(ZipEntry entry, long time) {
    entry.setTime(time - TimeZone.getDefault().getOffset(time));
  }

  /**
   * Gets the creation time for a ZipEntry, converting from UTC as stored in the ZIP
   * entry to make times correct between time zones.
   *
   * @return  the creation time assuming UTC zone or {@code null} if not specified.
   *
   * @see #setCreationTimeUtc(java.util.zip.ZipEntry, long)
   */
  // Copied from ao-lang:ZipUtils.java
  static Optional<Long> getCreationTimeUtc(ZipEntry entry) {
    FileTime creationTime = entry.getCreationTime();
    if (creationTime == null) {
      return Optional.empty();
    } else {
      long millis = creationTime.toMillis();
      return Optional.of(millis + TimeZone.getDefault().getOffset(millis));
    }
  }

  /**
   * Sets the creation time for a ZipEntry, converting to UTC while storing to the ZIP
   * entry to make times correct between time zones.
   *
   * @see #getCreationTimeUtc(java.util.zip.ZipEntry)
   */
  // Copied from ao-lang:ZipUtils.java
  static void setCreationTimeUtc(ZipEntry entry, long creationTime) {
    entry.setCreationTime(FileTime.fromMillis(creationTime - TimeZone.getDefault().getOffset(creationTime)));
  }

  /**
   * Gets the last access time for a ZipEntry, converting from UTC as stored in the ZIP
   * entry to make times correct between time zones.
   *
   * @return  the last access time assuming UTC zone or {@code null} if not specified.
   *
   * @see #setLastAccessTimeUtc(java.util.zip.ZipEntry, long)
   */
  // Copied from ao-lang:ZipUtils.java
  static Optional<Long> getLastAccessTimeUtc(ZipEntry entry) {
    FileTime lastAccess = entry.getLastAccessTime();
    if (lastAccess == null) {
      return Optional.empty();
    } else {
      long millis = lastAccess.toMillis();
      return Optional.of(millis + TimeZone.getDefault().getOffset(millis));
    }
  }

  /**
   * Sets the last access time for a ZipEntry, converting to UTC while storing to the ZIP
   * entry to make times correct between time zones.
   *
   * @see #getLastAccessTimeUtc(java.util.zip.ZipEntry)
   */
  // Copied from ao-lang:ZipUtils.java
  static void setLastAccessTimeUtc(ZipEntry entry, long lastAccessTime) {
    entry.setCreationTime(FileTime.fromMillis(lastAccessTime - TimeZone.getDefault().getOffset(lastAccessTime)));
  }

  /**
   * Gets the last modified time for a ZipEntry, converting from UTC as stored in the ZIP
   * entry to make times correct between time zones.
   *
   * @return  the last modified time assuming UTC zone or {@code null} if not specified.
   *
   * @see #setLastModifiedTimeUtc(java.util.zip.ZipEntry, long)
   */
  // Copied from ao-lang:ZipUtils.java
  static Optional<Long> getLastModifiedTimeUtc(ZipEntry entry) {
    FileTime lastModifiedTime = entry.getLastModifiedTime();
    if (lastModifiedTime == null) {
      return Optional.empty();
    } else {
      long millis = lastModifiedTime.toMillis();
      return Optional.of(millis + TimeZone.getDefault().getOffset(millis));
    }
  }

  /**
   * Sets the last modified time for a ZipEntry, converting to UTC while storing to the ZIP
   * entry to make times correct between time zones.
   *
   * @see #getLastModifiedTimeUtc(java.util.zip.ZipEntry)
   */
  // Copied from ao-lang:ZipUtils.java
  static void setLastModifiedTimeUtc(ZipEntry entry, long lastModifiedTime) {
    entry.setCreationTime(FileTime.fromMillis(lastModifiedTime - TimeZone.getDefault().getOffset(lastModifiedTime)));
  }
}
