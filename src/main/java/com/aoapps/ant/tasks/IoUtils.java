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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * I/O utilities.
 */
final class IoUtils {

  /** Make no instances. */
  private IoUtils() {
    throw new AssertionError();
  }

  /**
   * copies without flush.
   *
   * @see #copy(java.io.InputStream, java.io.OutputStream, boolean)
   */
  // Note: Copied from ao-lang:IoUtils.java
  static long copy(InputStream in, OutputStream out) throws IOException {
    return copy(in, out, false);
  }

  /**
   * Copies all information from one stream to another.  Internally reuses thread-local
   * buffers to avoid initial buffer zeroing cost and later garbage collection overhead.
   *
   * @return  the number of bytes copied
   *
   * @see  BufferManager#getBytes()
   */
  // Note: Copied from ao-lang:IoUtils.java, but without using BufferManager
  private static long copy(InputStream in, OutputStream out, boolean flush) throws IOException {
    final int bufferSize = 4096;
    byte[] buff = new byte[bufferSize];
    long totalBytes = 0;
    int numBytes;
    while ((numBytes = in.read(buff, 0, bufferSize)) != -1) {
      out.write(buff, 0, numBytes);
      if (flush) {
        out.flush();
      }
      totalBytes += numBytes;
    }
    return totalBytes;
  }
}
