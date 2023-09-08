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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.DateTimeException;
import java.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests {@link ZipTimestampMergeTask}.
 * <p>
 * We are not doing any Ant-specific unit testing as documented at
 * <a href="https://ant.apache.org/manual/tutorial-writing-tasks.html#TestingTasks">Test the Task</a>.
 * Ant (version 1.10.14 currently) is not JPMS enabled, and there are packages present in both ant.jar and
 * ant-test-util.jar.  Given that the Ant task is a thin wrapper around {@link ZipTimestampMerge}, which is thoroughly
 * tested, it is not worth the complexity of trying to work around this issue for marginally higher test coverage
 * statistics.
 * </p>
 */
public class ZipTimestampMergeTaskTest {

  private static Instant getOutputTimestamp(ZipTimestampMergeTask task) throws ReflectiveOperationException {
    Field field = ZipTimestampMergeTask.class.getDeclaredField("outputTimestamp");
    field.setAccessible(true);
    return (Instant) field.get(task);
  }

  /**
   * Tests {@link ZipTimestampMergeTask#setOutputTimestamp(java.lang.String)}.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testSetOutputTimestamp() throws ReflectiveOperationException {
    ZipTimestampMergeTask task = new ZipTimestampMergeTask();
    assertNull("null default", getOutputTimestamp(task));
    assertThrows("null", NullPointerException.class, () -> task.setOutputTimestamp(null));
    assertThrows("invalid", DateTimeException.class, () -> task.setOutputTimestamp("invalid"));
    task.setOutputTimestamp("2023-09-07T01:38:34Z");
    assertEquals("seconds", 1694050714, getOutputTimestamp(task).getEpochSecond());
    assertEquals("no nanos", 0, getOutputTimestamp(task).getNano());
  }

  private static boolean getBuildReproducible(ZipTimestampMergeTask task) throws ReflectiveOperationException {
    Field field = ZipTimestampMergeTask.class.getDeclaredField("buildReproducible");
    field.setAccessible(true);
    return (Boolean) field.get(task);
  }

  /**
   * Tests {@link ZipTimestampMergeTask#setBuildReproducible(boolean)}.
   */
  @Test
  public void testSetBuildReproducible() throws ReflectiveOperationException {
    ZipTimestampMergeTask task = new ZipTimestampMergeTask();
    assertTrue("defaults to true", getBuildReproducible(task));
    task.setBuildReproducible(false);
    assertFalse("is now false", getBuildReproducible(task));
    task.setBuildReproducible(true);
    assertTrue("is now true", getBuildReproducible(task));
  }

  private static boolean getRequireLastBuild(ZipTimestampMergeTask task) throws ReflectiveOperationException {
    Field field = ZipTimestampMergeTask.class.getDeclaredField("requireLastBuild");
    field.setAccessible(true);
    return (Boolean) field.get(task);
  }

  /**
   * Tests {@link ZipTimestampMergeTask#setRequireLastBuild(boolean)}.
   */
  @Test
  public void testSetRequireLastBuild() throws ReflectiveOperationException {
    ZipTimestampMergeTask task = new ZipTimestampMergeTask();
    assertTrue("defaults to true", getRequireLastBuild(task));
    task.setRequireLastBuild(false);
    assertFalse("is now false", getRequireLastBuild(task));
    task.setRequireLastBuild(true);
    assertTrue("is now true", getRequireLastBuild(task));
  }

  private static File getLastBuildDirectory(ZipTimestampMergeTask task) throws ReflectiveOperationException {
    Field field = ZipTimestampMergeTask.class.getDeclaredField("lastBuildDirectory");
    field.setAccessible(true);
    return (File) field.get(task);
  }

  @Rule
  public final TemporaryFolder temporaryFolder = TemporaryFolder.builder().assureDeletion().build();

  /**
   * Tests {@link ZipTimestampMergeTask#setLastBuildDirectory(java.lang.String)}.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testSetLastBuildDirectory() throws ReflectiveOperationException, IOException {
    ZipTimestampMergeTask task = new ZipTimestampMergeTask();
    assertNull("null default", getLastBuildDirectory(task));
    assertThrows("null", NullPointerException.class, () -> task.setLastBuildDirectory(null));
    File lastBuildDirectory = temporaryFolder.newFolder("lastBuildDirectory");
    task.setLastBuildDirectory(lastBuildDirectory.getPath());
    assertEquals("equal", lastBuildDirectory, getLastBuildDirectory(task));
    assertNotSame("but not same", lastBuildDirectory, getLastBuildDirectory(task));
  }

  private static File getBuildDirectory(ZipTimestampMergeTask task) throws ReflectiveOperationException {
    Field field = ZipTimestampMergeTask.class.getDeclaredField("buildDirectory");
    field.setAccessible(true);
    return (File) field.get(task);
  }

  /**
   * Tests {@link ZipTimestampMergeTask#setBuildDirectory(java.lang.String)}.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testSetBuildDirectory() throws ReflectiveOperationException, IOException {
    ZipTimestampMergeTask task = new ZipTimestampMergeTask();
    assertNull("null default", getBuildDirectory(task));
    assertThrows("null", NullPointerException.class, () -> task.setBuildDirectory(null));
    File buildDirectory = temporaryFolder.newFolder("buildDirectory");
    task.setBuildDirectory(buildDirectory.getPath());
    assertEquals("equal", buildDirectory, getBuildDirectory(task));
    assertNotSame("but not same", buildDirectory, getBuildDirectory(task));
  }
}
