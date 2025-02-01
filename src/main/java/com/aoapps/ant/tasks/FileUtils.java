/*
 * ao-ant-tasks - Ant tasks used in building AO-supported projects.
 * Copyright (C) 2025  AO Industries, Inc.
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Date;

/**
 * File utilities.
 */
final class FileUtils {

  /** Make no instances. */
  private FileUtils() {
    throw new AssertionError();
  }

  /**
   * Copies a stream to a file.
   *
   * @return  the number of bytes copied
   */
  // Note: Copied from ao-lang:FileUtils.java
  private static long copyToFile(InputStream in, File file) throws IOException {
    try (OutputStream out = new FileOutputStream(file)) {
      return IoUtils.copy(in, out);
    }
  }

  /**
   * Sets the last modified, throwing IOException when unsuccessful.
   */
  // Note: Copied from ao-lang:FileUtils.java
  private static void setLastModified(File file, long time) throws IOException {
    if (!file.setLastModified(time)) {
      throw new IOException("Unable to set last modified of \"" + file + "\" to \"" + new Date(time) + '"');
    }
  }

  /**
   * Copies one file over another, possibly creating if needed.
   *
   * @return  the number of bytes copied
   */
  // Note: Copied from ao-lang:FileUtils.java
  private static long copy(File from, File to) throws IOException {
    try (InputStream in = new FileInputStream(from)) {
      long modified = from.lastModified();
      long bytes = copyToFile(in, to);
      if (modified != 0) {
        setLastModified(to, modified);
      }
      return bytes;
    }
  }

  /**
   * Renames one file to another, throwing IOException when unsuccessful.
   * Allow a non-atomic delete/rename pair when the underlying system is unable
   * to rename one file over another, such as in Microsoft Windows.
   */
  // Note: Copied from ao-lang:FileUtils.java
  static void renameAllowNonAtomic(File from, File to) throws IOException {
    // Try atomic rename first
    if (!from.renameTo(to)) {
      try {
        // Try overwrite in-place for Windows
        copy(from, to);
        Files.delete(from.toPath());
      } catch (IOException e) {
        throw new IOException("Unable to non-atomically rename \"" + from + "\" to \"" + to + '"', e);
      }
    }
  }
}
