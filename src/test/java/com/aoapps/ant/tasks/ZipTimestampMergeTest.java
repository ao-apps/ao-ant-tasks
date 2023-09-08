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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.FilenameFilter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import org.junit.Test;

/**
 * Tests {@link ZipTimestampMerge}.
 */
public class ZipTimestampMergeTest {

  /**
   * Tests {@link ZipTimestampMerge} cannot be constructed.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testNoConstructor() throws ReflectiveOperationException {
    var constructor = ZipTimestampMerge.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    assertThrows("Make no instances.", AssertionError.class, () -> {
      try {
        constructor.newInstance();
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    });
  }

  private static FilenameFilter getFilter() throws ReflectiveOperationException {
    Field field = ZipTimestampMerge.class.getDeclaredField("FILTER");
    field.setAccessible(true);
    return (FilenameFilter) field.get(null);
  }

  /**
   * Tests {@link ZipTimestampMerge#FILTER}.
   */
  @Test
  public void testFilter() throws ReflectiveOperationException {
    FilenameFilter filter = getFilter();
    // *.aar
    assertTrue(filter.accept(null, "blarg.aar"));
    assertTrue(filter.accept(null, "blarg.Aar"));
    assertTrue(filter.accept(null, ".aar"));
    assertTrue(filter.accept(null, ".Aar"));
    assertFalse(filter.accept(null, "blarg.aar "));
    assertFalse(filter.accept(null, "aar"));
    // *.jar
    assertTrue(filter.accept(null, "blarg.jar"));
    assertTrue(filter.accept(null, "blarg.Jar"));
    assertTrue(filter.accept(null, ".jar"));
    assertTrue(filter.accept(null, ".Jar"));
    assertFalse(filter.accept(null, "blarg.jar "));
    assertFalse(filter.accept(null, "jar"));
    // *.war
    assertTrue(filter.accept(null, "blarg.war"));
    assertTrue(filter.accept(null, "blarg.War"));
    assertTrue(filter.accept(null, ".war"));
    assertTrue(filter.accept(null, ".War"));
    assertFalse(filter.accept(null, "blarg.war "));
    assertFalse(filter.accept(null, "war"));
    // *.zip
    assertTrue(filter.accept(null, "blarg.zip"));
    assertTrue(filter.accept(null, "blarg.Zip"));
    assertTrue(filter.accept(null, ".zip"));
    assertTrue(filter.accept(null, ".Zip"));
    assertFalse(filter.accept(null, "blarg.zip "));
    assertFalse(filter.accept(null, "zip"));
    // Not *.pom
    assertFalse(filter.accept(null, "blarg.pom"));
    assertFalse(filter.accept(null, "blarg.Pom"));
    assertFalse(filter.accept(null, ".pom"));
    assertFalse(filter.accept(null, ".Pom"));
    assertFalse(filter.accept(null, "blarg.pom "));
    assertFalse(filter.accept(null, "pom"));
  }

  private static String parseArtifactId(String filename) throws Throwable {
    Method method = ZipTimestampMerge.class.getDeclaredMethod("parseArtifactId", String.class);
    method.setAccessible(true);
    try {
      return (String) method.invoke(null, filename);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /**
   * Tests {@link ZipTimestampMerge#parseArtifactId(java.lang.String)}.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseArtifactId() throws Throwable {
    assertThrows("null", NullPointerException.class, () -> parseArtifactId(null));
    assertThrows("empty", ParseException.class, () -> parseArtifactId(""));
    assertThrows("missing", ParseException.class, () -> parseArtifactId("-1.2.3-SNAPSHOT.jar"));
    assertEquals("valid version", "artifact", parseArtifactId("artifact-1.2.3-SNAPSHOT.jar"));
    assertThrows("invalid version", ParseException.class, () -> parseArtifactId("artifact-v1.2.3-SNAPSHOT.jar"));
  }

  private static String parseType(String filename) throws Throwable {
    Method method = ZipTimestampMerge.class.getDeclaredMethod("parseType", String.class);
    method.setAccessible(true);
    try {
      return (String) method.invoke(null, filename);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /**
   * Tests {@link ZipTimestampMerge#parseType(java.lang.String)}.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseType() throws Throwable {
    assertThrows("null", NullPointerException.class, () -> parseType(null));
    assertThrows("empty", ParseException.class, () -> parseType(""));
    assertThrows("missing dot", ParseException.class, () -> parseType("artifact-1.2.3-SNAPSHOT."));
    assertThrows("missing none", ParseException.class, () -> parseType("artifact-1.2.3-SNAPSHOT"));
    assertEquals("lowercase type", "jar", parseType("artifact-1.2.3-SNAPSHOT.jar"));
    assertEquals("mixed case type", "Jar", parseType("artifact-1.2.3-SNAPSHOT.Jar"));
    assertEquals("double extension", "zip", parseType("artifact-1.2.3-SNAPSHOT.jar.zip"));
  }

  private static String parseClassifier(String filename, String type) throws Throwable {
    Method method = ZipTimestampMerge.class.getDeclaredMethod("parseClassifier", String.class, String.class);
    method.setAccessible(true);
    try {
      return (String) method.invoke(null, filename, type);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  /**
   * Tests {@link ZipTimestampMerge#parseClassifier(java.lang.String, java.lang.String)}.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testParseClassifier() throws Throwable {
    assertThrows("null", NullPointerException.class, () -> parseClassifier(null, "jar"));
    assertThrows("null", NullPointerException.class, () -> parseClassifier("artifact-1.2.3-SNAPSHOT.jar", null));
    assertThrows("type filename mismatch", AssertionError.class, () -> parseClassifier("artifact-1.2.3-SNAPSHOT.jar", "zip"));
    assertThrows("type filename mismatch", AssertionError.class, () -> parseClassifier("artifact-1.2.3-SNAPSHOT.jar", ".jar"));
    assertEquals("no classifier", "", parseClassifier("artifact-1.2.3-SNAPSHOT.jar", "jar"));
    assertEquals("javadoc classifier", "javadoc", parseClassifier("artifact-1.2.3-SNAPSHOT-javadoc.jar", "jar"));
    assertEquals("longer classifier", "test-javadoc", parseClassifier("artifact-1.2.3-SNAPSHOT-test-javadoc.jar", "jar"));
    assertEquals("only lowercase", "", parseClassifier("artifact-1.2.3-SNAPSHOT-javadoC.jar", "jar"));
  }

  /**
   * Tests {@link ZipTimestampMerge#mergeFile(java.time.Instant, boolean, java.io.File, java.io.File)}.
   */
  @Test
  public void testMergeFile() throws Throwable {
  }

  /**
   * Tests {@link ZipTimestampMerge#mergeDirectory(java.time.Instant, boolean, boolean, java.io.File, java.io.File)}.
   */
  @Test
  public void testMergeDirectory() throws Throwable {
  }
}
