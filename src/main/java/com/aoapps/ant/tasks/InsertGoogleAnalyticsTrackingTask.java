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
import java.io.IOException;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.LogLevel;

/**
 * Ant task that invokes {@link InsertGoogleAnalyticsTracking#addTrackingCodeToZip(java.io.File, java.lang.String)}.
 *
 * @author  AO Industries, Inc.
 */
@SuppressWarnings("CloneableImplementsClone")
public class InsertGoogleAnalyticsTrackingTask extends Task {

  private File file;
  private String googleAnalyticsTrackingId;

  /**
   * The ZIP file to add Google Analytics tracking codes to.
   * Must exist and be a ZIP file.
   */
  public void setFile(String file) {
    this.file = new File(file);
  }

  /**
   * The modern Google Analytics <a href="https://support.google.com/analytics/answer/1008080?hl=en&amp;ref_topic=1008079#GA">Global Site Tag</a>
   * tracking ID, which will currently be the Google Analytics 4 (GA4) Measurement ID.
   *
   * @param googleAnalyticsTrackingId  No script will be written when {@code null} or empty (after trimming)
   */
  public void setGoogleAnalyticsTrackingId(String googleAnalyticsTrackingId) {
    this.googleAnalyticsTrackingId = googleAnalyticsTrackingId;
  }

  /**
   * Calls {@link InsertGoogleAnalyticsTracking#addTrackingCodeToZip(java.io.File, java.lang.String)} with the given ZIP
   * file while logging to {@link InsertGoogleAnalyticsTrackingTask#log(java.lang.String, int)}.
   */
  @Override
  public void execute() throws BuildException {
    try {
      if (file == null) {
        throw new BuildException("file required");
      }
      if (!file.exists()) {
        throw new IOException("file does not exist: " + file);
      }
      if (!file.isFile()) {
        throw new IOException("file is not a regular file: " + file);
      }
      InsertGoogleAnalyticsTracking.addTrackingCodeToZip(
          file,
          googleAnalyticsTrackingId,
          msg -> log(msg.get(), LogLevel.DEBUG.getLevel()),
          msg -> log(msg.get(), LogLevel.INFO.getLevel()),
          msg -> log(msg.get(), LogLevel.WARN.getLevel())
      );
    } catch (IOException e) {
      throw new BuildException(e);
    }
  }
}
