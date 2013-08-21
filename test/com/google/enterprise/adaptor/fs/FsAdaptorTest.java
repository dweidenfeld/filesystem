// Copyright 2013 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.adaptor.fs;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.rules.ExpectedException;

import org.junit.Test;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Test cases for {@link FsAdaptor}. */
public class FsAdaptorTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testGetPathName() throws Exception {
    FsAdaptor adaptor = new FsAdaptor();
    assertEquals("share", adaptor.getPathName(Paths.get("\\\\host/share/")));
    assertEquals("folder2", 
        adaptor.getPathName(Paths.get("C:/folder1/folder2/")));
  }
}
