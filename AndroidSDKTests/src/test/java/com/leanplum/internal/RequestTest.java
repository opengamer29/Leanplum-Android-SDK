/*
 * Copyright 2016, Leanplum, Inc. All rights reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.leanplum.internal;

import android.app.Application;

import com.leanplum.Leanplum;
import com.leanplum.__setup.LeanplumTestApp;
import com.leanplum._whitebox.utilities.SynchronousExecutor;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * @author Ben Marten
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 16,
    application = LeanplumTestApp.class
)
@PowerMockIgnore({"org.mockito.*", "org.robolectric.*", "org.json.*", "org.powermock.*"})
public class RequestTest extends TestCase {
  /**
   * Runs before every test case.
   */
  @Before
  public void setUp() {
    Application context = RuntimeEnvironment.application;
    assertNotNull(context);
    Leanplum.setApplicationContext(context);
    ReflectionHelpers.setStaticField(LeanplumEventDataManager.class, "sqlLiteThreadExecutor",  new SynchronousExecutor());
    LeanplumEventDataManagerTest.setDatabaseToNull();
  }

  /**
   * Tests the testRemoveIrrelevantBackgroundStartRequests method.
   * <p>
   * <p>
   * <code>start(B), start(B), start(F), track, start(B), track, start(F), resumeSession</code>
   * <p>
   * where <code>start(B)</code> indicates a start in the background, and <code>start(F)</code> one
   * in the foreground.
   * <p>
   * In this case the first two <code>start(B)</code> can be dropped because they don't contribute
   * any relevant information for the batch call.
   * <p>
   * Essentially we drop every <code>start(B)</code> call, that is directly followed by any kind of
   * a <code>start</code> call.
   */
  @Test
  public void testRemoveIrrelevantBackgroundStartRequests() throws NoSuchMethodException,
      InvocationTargetException, IllegalAccessException {
    LeanplumEventDataManager.init(Leanplum.getContext());
    // Prepare testable objects and method.
    Request request = new Request("POST", Constants.Methods.START, null);
    Method removeIrrelevantBackgroundStartRequests =
        Request.class.getDeclaredMethod("removeIrrelevantBackgroundStartRequests", List.class);
    removeIrrelevantBackgroundStartRequests.setAccessible(true);

    // Invoke method with specific test data.
    // Expectation: No request returned.
    List unsentRequests = request.getUnsentRequests();
    assertNotNull(unsentRequests);
    assertEquals(0, unsentRequests.size());

    // Regular start request.
    // Expectation: One request returned.
    request.sendEventually();
    unsentRequests = request.getUnsentRequests();
    assertNotNull(unsentRequests);
    assertEquals(1, unsentRequests.size());
    Request.deleteSentRequests(unsentRequests.size());

    // Two foreground start requests.
    // Expectation: Both foreground start request returned.
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("fg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("fg", "2");
    }}).sendEventually();
    unsentRequests = request.getUnsentRequests();
    assertNotNull(unsentRequests);
    assertEquals(2, unsentRequests.size());
    Request.deleteSentRequests(unsentRequests.size());

    // One background start request followed by a foreground start request.
    // Expectation: Only one foreground start request returned.
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(true));
      put("bg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("fg", "1");
    }}).sendEventually();
    unsentRequests = request.getUnsentRequests();
    List unsentRequestsData =
        (List) removeIrrelevantBackgroundStartRequests.invoke(Request.class, unsentRequests);
    assertNotNull(unsentRequestsData);
    assertEquals(1, unsentRequestsData.size());
    assertEquals("1", ((Map) unsentRequestsData.get(0)).get("fg"));
    Request.deleteSentRequests(unsentRequests.size());

    // Two background start request followed by a foreground start requests.
    // Expectation: Only one foreground start request returned.
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(true));
      put("bg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(true));
      put("bg", "2");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("fg", "1");
    }}).sendEventually();
    unsentRequests = request.getUnsentRequests();

    assertNotNull(unsentRequests);
    unsentRequestsData =
        (List) removeIrrelevantBackgroundStartRequests.invoke(Request.class, unsentRequests);
    assertEquals(1, unsentRequestsData.size());
    assertEquals("1", ((Map) unsentRequestsData.get(0)).get("fg"));
    Request.deleteSentRequests(unsentRequests.size());

    // A foreground start request followed by two background start requests.
    // Expectation: Should keep the foreground and the last background start request returned.
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("fg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(true));
      put("bg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(true));
      put("bg", "2");
    }}).sendEventually();
    unsentRequests = request.getUnsentRequests();
    assertNotNull(unsentRequests);
    unsentRequestsData =
        (List) removeIrrelevantBackgroundStartRequests.invoke(Request.class, unsentRequests);
    assertEquals(2, unsentRequestsData.size());
    assertEquals("2", ((Map) unsentRequestsData.get(1)).get("bg"));
    Request.deleteSentRequests(unsentRequests.size());

    // A foreground start request followed by two background start requests.
    // Expectation: Should keep the foreground and the last background start request returned.
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("fg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(false));
      put("bg", "1");
    }}).sendEventually();
    new Request("POST", Constants.Methods.START, new HashMap<String, Object>() {{
      put(Constants.Params.BACKGROUND, Boolean.toString(true));
      put("bg", "2");
    }}).sendEventually();
    unsentRequests = request.getUnsentRequests();
    unsentRequestsData =
        (List) removeIrrelevantBackgroundStartRequests.invoke(Request.class, unsentRequests);
    assertNotNull(unsentRequestsData);
    assertEquals(3, unsentRequestsData.size());
    Request.deleteSentRequests(unsentRequests.size());
    LeanplumEventDataManagerTest.setDatabaseToNull();
  }

  // Given a list of unsent requests
  // we want to get the list of strings to send
  // We should get back the correct list of requests to send
  @Test
  public void testJsonEncodeUnsentRequests() {
    List<Map<String, Object>> requests = mockRequests(4);

    Request realRequest = new Request("POST", Constants.Methods.START, null);
    Request request = spy(realRequest);
    when(request.getUnsentRequests()).thenReturn(requests);

    Request.RequestsWithEncoding requestsWithEncoding = request.getRequestsWithEncodedStringStoredRequests();

    assertEquals(4, requestsWithEncoding .unsentRequests.size());
    assertEquals(4, requestsWithEncoding .requestsToSend.size());
    final String expectedJson =  "{\"data\":[{\"0\":\"testData\"},{\"1\":\"testData\"},{\"2\":\"testData\"},{\"3\":\"testData\"}]}";
    assertEquals(expectedJson, requestsWithEncoding.jsonEncodedString);
  }

  // Given a list of requests
  // we want to encode to a JSON String
  // The String should have the expected format
  @Test
  public void testGetRequestsWithEncodedStringStoredRequests() {
    List<Map<String, Object>> requests = mockRequests(4);
    String json = Request.jsonEncodeUnsentRequests(requests);

    final String expectedJson =  "{\"data\":[{\"0\":\"testData\"},{\"1\":\"testData\"},{\"2\":\"testData\"},{\"3\":\"testData\"}]}";
    assertEquals(json, expectedJson);
  }

  private List<Map<String, Object>> mockRequests(int requestSize) {
    List<Map<String, Object>> requests = new ArrayList<>();

    for (int i=0; i < requestSize; i++) {
      Map<String, Object> request = new HashMap<String, Object>();
      request.put(Integer.toString(i), "testData");
      requests.add(request);
    }
    return requests;
  }

}
