/*
 * Copyright (c) 2016 Open Baton (http://www.openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openbaton.vnfm.juju.utils;

import java.util.*;

/**
 * The purpose of this class is to help writing the charm's metadata file. This file may contain a
 * map with duplicate keys. This implementation of the Map interface works as a Map that can store
 * duplicate keys. Key and value are determined to be Strings. Some typical map methods are not
 * available and marked as deprecated.
 */
public class InterfaceMap implements Map<String, String> {

  private Map<String, List<String>> internalMap = new HashMap<>();

  @Override
  public int size() {
    int size = 0;
    for (List<String> values : internalMap.values()) size += values.size();
    return size;
  }

  @Override
  public boolean isEmpty() {
    return internalMap.isEmpty();
  }

  @Override
  public boolean containsKey(Object o) {
    return internalMap.containsKey(o);
  }

  @Override
  public boolean containsValue(Object o) {
    for (List<String> values : internalMap.values()) {
      if (values.contains(o)) return true;
    }
    return false;
  }

  @Override
  @Deprecated
  public String get(Object o) {
    return null;
  }

  @Override
  public synchronized String put(String s, String s2) {
    if (internalMap.containsKey(s)) internalMap.get(s).add(s2);
    else {
      List<String> values = new LinkedList<>();
      values.add(s2);
      internalMap.put(s, values);
    }
    return s2;
  }

  @Override
  @Deprecated
  public String remove(Object o) {
    return null;
  }

  @Override
  @Deprecated
  public void putAll(Map<? extends String, ? extends String> map) {}

  @Override
  @Deprecated
  public void clear() {}

  @Override
  public Set<String> keySet() {
    return internalMap.keySet();
  }

  @Override
  public Collection<String> values() {
    Collection<String> valueList = new LinkedList<>();
    for (List<String> values : internalMap.values()) {
      valueList.addAll(values);
    }
    return valueList;
  }

  @Override
  public Set<Entry<String, String>> entrySet() {
    Set<Entry<String, String>> entries = new HashSet<>();
    for (Entry<String, List<String>> entry : internalMap.entrySet()) {
      for (String value : entry.getValue())
        entries.add(new AbstractMap.SimpleEntry<String, String>(entry.getKey(), value));
    }
    return entries;
  }
}
