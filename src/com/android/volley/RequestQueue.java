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

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * http://blog.csdn.net/guolin_blog/article/details/17656437
 * http://www.jianshu.com/p/9e17727f31a1
 * http://www.ezlippi.com/blog/2015/08/volley-source-code.html
 * RequestQueue�����2���ǳ���Ҫ��PriorityBlockingQueue���͵ĳ�Ա�ֶ�mCacheQueue mNetworkQueue ����PriorityBlockingQueueΪjava1.5�������ṩ������
 * �����м�����Ҫ�ķ���������take()Ϊ�Ӷ�����ȡ�ö���������в����ڶ��󣬽��ᱻ������ֱ�������д����ж���������Looper.loop()
 * 
 * ʵ����һ��request���󣬵���RequestQueue.add(request),��request������������棬���ᱻ�����mNetworkQueue�����У������NetworkDispatcher�߳�take()ȡ������
 * �����request���Ա����棬��request���ᱻ�����mCacheQueue�����У���mCacheDispatcher�̴߳�mCacheQueue.take()ȡ������
 * �����request��mCache�в�����ƥ��Ļ���ʱ����request���ᱻ�ƽ������mNetworkQueue�����У������������ɺ󣬽��ؼ�ͷ��Ϣ�����mCache������ȥ��
 */
public class RequestQueue {

	/** ������������ĵ������������к� */
	private AtomicInteger mSequenceGenerator = new AtomicInteger();

	/**
	 *  �ȴ��������: ��������request�����Ѿ���һ����ͬ��������������У�������������ȴ����У��ȴ���ͬ������������ٷ���
	 */
	private final Map<String, Queue<Request<?>>> mWaitingRequests =
			new HashMap<String, Queue<Request<?>>>();

	/**
	 * ���ڴ����RequestQueue����set���洢�Ĳ�ͬ��request
	 */
	private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

	/** . 
	 * �������
	 */
	private final PriorityBlockingQueue<Request<?>> mCacheQueue =
			new PriorityBlockingQueue<Request<?>>();

	/**
	 * ������У����Ǹ����ȶ��У��������ȼ��������ȼ��ں�����ἰ
	 */
	private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
			new PriorityBlockingQueue<Request<?>>();

	/**
	 * ������������߳�����
	 */
	private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

	/** ����ӿ� */
	private final Cache mCache;

	/** ����ص��ӿڣ�������һ������response���� */
	private final Network mNetwork;

	/**��Ӧ�Ĵ��ݷַ�����. */
	private final ResponseDelivery mDelivery;

	/** ��������ַ�. */
	private NetworkDispatcher[] mDispatchers;

	/** ��������ַ�. */
	private CacheDispatcher mCacheDispatcher;

	/**
	 * Creates the worker pool. Processing will not begin until {@link #start()} is called.
	 *
	 * @param cache A Cache to use for persisting responses to disk
	 * @param network A Network interface for performing HTTP requests
	 * @param threadPoolSize Number of network dispatcher threads to create
	 * @param delivery A ResponseDelivery interface for posting responses and errors
	 */
	public RequestQueue(Cache cache, Network network, int threadPoolSize,
			ResponseDelivery delivery) {
		mCache = cache;
		mNetwork = network;
		mDispatchers = new NetworkDispatcher[threadPoolSize];
		mDelivery = delivery;
	}

	/**
	 * Creates the worker pool. Processing will not begin until {@link #start()} is called.
	 *
	 * @param cache A Cache to use for persisting responses to disk
	 * @param network A Network interface for performing HTTP requests
	 * @param threadPoolSize Number of network dispatcher threads to create
	 */
	public RequestQueue(Cache cache, Network network, int threadPoolSize) {
		this(cache, network, threadPoolSize,
				new ExecutorDelivery(new Handler(Looper.getMainLooper())));
	}

	/**
	 * Creates the worker pool. Processing will not begin until {@link #start()} is called.
	 *
	 * @param cache A Cache to use for persisting responses to disk
	 * @param network A Network interface for performing HTTP requests
	 */
	public RequestQueue(Cache cache, Network network) {
		this(cache, network, DEFAULT_NETWORK_THREAD_POOL_SIZE);
	}

	/**
	 * Starts the dispatchers in this queue.
	 * �����request���Ա����棬��request���ᱻ�����mCacheQueue�����У���mCacheDispatcher�̴߳�mCacheQueue.take()ȡ������
	 * �����request��mCache�в�����ƥ��Ļ���ʱ����request���ᱻ�ƽ������mNetworkQueue�����У������������ɺ󣬽��ؼ�ͷ��Ϣ�����mCache������ȥ��
	 */
	public void start() {
		stop();  // Make sure any currently running dispatchers are stopped.
		
		// ����Cache�ķַ�
		mCacheDispatcher = new CacheDispatcher(mCacheQueue, mNetworkQueue, mCache, mDelivery);
		mCacheDispatcher.start();

		// Create network dispatchers (and corresponding threads) up to the pool size.
		for (int i = 0; i < mDispatchers.length; i++) {
			NetworkDispatcher networkDispatcher = new NetworkDispatcher(mNetworkQueue, mNetwork,
					mCache, mDelivery);
			mDispatchers[i] = networkDispatcher;
			networkDispatcher.start();
		}
	}

	/**
	 * Stops the cache and network dispatchers.
	 */
	public void stop() {
		if (mCacheDispatcher != null) {
			mCacheDispatcher.quit();
		}
		for (int i = 0; i < mDispatchers.length; i++) {
			if (mDispatchers[i] != null) {
				mDispatchers[i].quit();
			}
		}
	}

	/**
	 * Gets a sequence number.
	 */
	public int getSequenceNumber() {
		return mSequenceGenerator.incrementAndGet();
	}

	/**
	 * Gets the {@link Cache} instance being used.
	 */
	public Cache getCache() {
		return mCache;
	}

	/**
	 * A simple predicate or filter interface for Requests, for use by
	 * {@link RequestQueue#cancelAll(RequestFilter)}.
	 */
	public interface RequestFilter {
		public boolean apply(Request<?> request);
	}

	/**
	 * Cancels all requests in this queue for which the given filter applies.
	 * @param filter The filtering function to use
	 */
	public void cancelAll(RequestFilter filter) {
		synchronized (mCurrentRequests) {
			for (Request<?> request : mCurrentRequests) {
				if (filter.apply(request)) {
					request.cancel();
				}
			}
		}
	}

	/**
	 * Cancels all requests in this queue with the given tag. Tag must be non-null
	 * and equality is by identity.
	 */
	public void cancelAll(final Object tag) {
		if (tag == null) {
			throw new IllegalArgumentException("Cannot cancelAll with a null tag");
		}
		cancelAll(new RequestFilter() {
			@Override
			public boolean apply(Request<?> request) {
				return request.getTag() == tag;
			}
		});
	}

	/**
	 * Adds a Request to the dispatch queue.
	 * ��������ӵ�������
	 * @param request The request to service
	 * @return The passed-in request
	 */
	public <T> Request<T> add(Request<T> request) {
		// Tag the request as belonging to this queue and add it to the set of current requests.
		//����request����
		request.setRequestQueue(this);
		
		//��ӵ����ڴ����RequestQueue��,��ΪmCurrentRequests��set��������������ͬ�����
		synchronized (mCurrentRequests) {
			mCurrentRequests.add(request);
		}

		// �������к�.
		request.setSequence(getSequenceNumber());
		request.addMarker("add-to-queue");

		//���������Ϊ������У���Ϊ�������(���ȼ�����)��Ĭ���ǿ��Ի���
		if (!request.shouldCache()) {
			mNetworkQueue.add(request);
			return request;
		}

		// Insert request into stage if there's already a request with the same cache key in flight.
		synchronized (mWaitingRequests) {
			String cacheKey = request.getCacheKey();
			if (mWaitingRequests.containsKey(cacheKey)) {
				// There is already a request in flight. Queue up.
				Queue<Request<?>> stagedRequests = mWaitingRequests.get(cacheKey);
				if (stagedRequests == null) {
					stagedRequests = new LinkedList<Request<?>>();
				}
				stagedRequests.add(request);
				mWaitingRequests.put(cacheKey, stagedRequests);
				if (VolleyLog.DEBUG) {
					VolleyLog.v("Request for cacheKey=%s is in flight, putting on hold.", cacheKey);
				}
			} else {
				// Insert 'null' queue for this cacheKey, indicating there is now a request in
				// flight.
				mWaitingRequests.put(cacheKey, null);
				mCacheQueue.add(request);
			}
			return request;
		}
	}

	/**
	 * Called from {@link Request#finish(String)}, indicating that processing of the given request
	 * has finished.
	 *
	 * <p>Releases waiting requests for <code>request.getCacheKey()</code> if
	 *      <code>request.shouldCache()</code>.</p>
	 */
	void finish(Request<?> request) {
		// Remove from the set of requests currently being processed.
		synchronized (mCurrentRequests) {
			mCurrentRequests.remove(request);
		}

		if (request.shouldCache()) {
			synchronized (mWaitingRequests) {
				String cacheKey = request.getCacheKey();
				Queue<Request<?>> waitingRequests = mWaitingRequests.remove(cacheKey);
				if (waitingRequests != null) {
					if (VolleyLog.DEBUG) {
						VolleyLog.v("Releasing %d waiting requests for cacheKey=%s.",
								waitingRequests.size(), cacheKey);
					}
					// Process all queued up requests. They won't be considered as in flight, but
					// that's not a problem as the cache has been primed by 'request'.
					mCacheQueue.addAll(waitingRequests);
				}
			}
		}
	}
}
