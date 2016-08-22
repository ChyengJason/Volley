/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.volley;

import java.util.Collections;
import java.util.Map;

/**
 * 由一个key为string，值为Entry实体，Entry主要是一个byte数组
 */
public interface Cache {
    /**
     * Retrieves an entry from the cache.
     * @param key Cache key
     * @return An {@link Entry} or null in the event of a cache miss
     */
    public Entry get(String key);

    /**
     * Adds or replaces an entry to the cache.
     * @param key Cache key
     * @param entry Data to store and metadata for cache coherency, TTL, etc.
     */
    public void put(String key, Entry entry);

    /**
     * Performs any potentially long-running actions needed to initialize the cache;
     * will be called from a worker thread.
     * 初始化
     */
    public void initialize();

    /**
     * 设为无效
     * @param key Cache key
     * @param fullExpire True to fully expire the entry, false to soft expire
     */
    public void invalidate(String key, boolean fullExpire);

    /**
     * Removes an entry from the cache.
     * @param key Cache key
     */
    public void remove(String key);

    /**
     * Empties the cache.
     */
    public void clear();

    /**
     *Cache中的实体类
     */
    public static class Entry {
        /** The data returned from cache. */
        public byte[] data;

        /** ETag for cache coherency. --我也不懂--*/
        public String etag;

        /** 服务器回应的日期. */
        public long serverDate;

        /** TTL 存活时间 */
        public long ttl;

        /** Soft TTL for this record. --我也不懂- */
        public long softTtl;

        /**响应头，不可以为空 */
        public Map<String, String> responseHeaders = Collections.emptyMap();

        /** 判断是否失效*/
        public boolean isExpired() {
            return this.ttl < System.currentTimeMillis();
        }

        /** 是否可刷新 */
        public boolean refreshNeeded() {
            return this.softTtl < System.currentTimeMillis();
        }
    }

}
