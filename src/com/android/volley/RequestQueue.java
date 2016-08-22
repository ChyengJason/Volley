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
 * RequestQueue类存在2个非常重要的PriorityBlockingQueue类型的成员字段mCacheQueue mNetworkQueue ，该PriorityBlockingQueue为java1.5并发库提供的新类
 * 其中有几个重要的方法，比如take()为从队列中取得对象，如果队列不存在对象，将会被阻塞，直到队列中存在有对象，类似于Looper.loop()
 * 
 * 实例化一个request对象，调用RequestQueue.add(request),该request如果不允许被缓存，将会被添加至mNetworkQueue队列中，待多个NetworkDispatcher线程take()取出对象
 * 如果该request可以被缓存，该request将会被添加至mCacheQueue队列中，待mCacheDispatcher线程从mCacheQueue.take()取出对象，
 * 如果该request在mCache中不存在匹配的缓存时，该request将会被移交添加至mNetworkQueue队列中，待网络访问完成后，将关键头信息添加至mCache缓存中去！
 */
public class RequestQueue {

	/** 用于生成请求的单调递增的序列号 */
	private AtomicInteger mSequenceGenerator = new AtomicInteger();

	/**
	 *  等待请求队列: 如果加入的request请求已经有一个相同的请求在网络队列，则将这个请求加入等待队列，等待相同的请求结束后，再返回
	 */
	private final Map<String, Queue<Request<?>>> mWaitingRequests =
			new HashMap<String, Queue<Request<?>>>();

	/**
	 * 正在处理的RequestQueue集，set：存储的不同的request
	 */
	private final Set<Request<?>> mCurrentRequests = new HashSet<Request<?>>();

	/** . 
	 * 缓存队列
	 */
	private final PriorityBlockingQueue<Request<?>> mCacheQueue =
			new PriorityBlockingQueue<Request<?>>();

	/**
	 * 网络队列，这是个优先队列，根据优先级排序，优先级在后面会提及
	 */
	private final PriorityBlockingQueue<Request<?>> mNetworkQueue =
			new PriorityBlockingQueue<Request<?>>();

	/**
	 * 网络请求调度线程数量
	 */
	private static final int DEFAULT_NETWORK_THREAD_POOL_SIZE = 4;

	/** 缓存接口 */
	private final Cache mCache;

	/** 网络回调接口，里面有一个处理response请求 */
	private final Network mNetwork;

	/**响应的传递分发机制. */
	private final ResponseDelivery mDelivery;

	/** 网络请求分发. */
	private NetworkDispatcher[] mDispatchers;

	/** 缓存请求分发. */
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
	 * 如果该request可以被缓存，该request将会被添加至mCacheQueue队列中，待mCacheDispatcher线程从mCacheQueue.take()取出对象，
	 * 如果该request在mCache中不存在匹配的缓存时，该request将会被移交添加至mNetworkQueue队列中，待网络访问完成后，将关键头信息添加至mCache缓存中去！
	 */
	public void start() {
		stop();  // Make sure any currently running dispatchers are stopped.
		
		// 创建Cache的分发
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
	 * 将请求添加到队列中
	 * @param request The request to service
	 * @return The passed-in request
	 */
	public <T> Request<T> add(Request<T> request) {
		// Tag the request as belonging to this queue and add it to the set of current requests.
		//设置request队列
		request.setRequestQueue(this);
		
		//添加到正在处理的RequestQueue集,因为mCurrentRequests是set，如果里面存有相同则不添加
		synchronized (mCurrentRequests) {
			mCurrentRequests.add(request);
		}

		// 设置序列号.
		request.setSequence(getSequenceNumber());
		request.addMarker("add-to-queue");

		//如果不允许为缓存队列，则为网络队列(优先级队列)，默认是可以缓存
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
