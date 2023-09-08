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

import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

/**
 * Tests {@link ZipUtils}.
 */
public class ZipUtilsTest {

  /**
   * Tests {@link ZipUtils} cannot be constructed.
   */
  @Test
  @SuppressWarnings("ThrowableResultIgnored")
  public void testNoConstructor() throws ReflectiveOperationException {
    var constructor = ZipUtils.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    assertThrows("Make no instances.", AssertionError.class, () -> {
      try {
        constructor.newInstance();
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    });
  }
}
