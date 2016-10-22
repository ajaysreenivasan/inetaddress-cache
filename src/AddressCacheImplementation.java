import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Semaphore;

public class AddressCacheImplementation implements AddressCache {
	private Map<String, CacheElement> mInetAddressList;
	private float mCacheTime; // TTL of elements
	private Semaphore mInetAddressAvailableSemaphore; // number of elements
														// available in cache
	private CleanupTask mCleanupTask;
	private volatile boolean mIsClosed; // indicate if the cache is closing

	public AddressCacheImplementation(float cacheTime) {
		// constructor allows the linkedlist within LinkedHashMap to maintain
		// order
		// with respect to calls to "get", "put" and "putAll". (i.e. maintaining
		// the most recently accessed/inserted elements at the front of the
		// cache)
		mInetAddressList = new LinkedHashMap<>(10, 0.75f, true);

		// element expiration time (TTL)
		mCacheTime = cacheTime;

		// zero initial permits
		// first in, first out distribution of permits as they become available
		mInetAddressAvailableSemaphore = new Semaphore(0, true);
		mIsClosed = false;

		// create a cleanup task and schedule it to start after 5 seconds, then
		// run
		// every 5 seconds
		mCleanupTask = new CleanupTask();
		Timer taskTimer = new Timer();
		taskTimer.scheduleAtFixedRate(mCleanupTask, 5000, 5000);
	}

	@Override
	// O(1)
	public boolean offer(InetAddress address) {
		synchronized (mInetAddressList) {
			// mark when the element was created to determine expiration
			long elementCreateTime = System.currentTimeMillis();

			// if the element isn't found in the cache, add it to the cache
			// if it is found, the linkedhashmap will automatically move it to
			// the front
			// when it is accessed
			// i assumed that the insert should return true for a new insertion
			// and false if it already exists
			if (!this.contains(address)) {
				// if it's a new element
				// insert it into the linkedhashmap and
				// increment the semaphore's available permits
				CacheElement newCacheElement = new CacheElement(address, elementCreateTime);
				mInetAddressList.put(address.toString(), newCacheElement);
				mInetAddressAvailableSemaphore.release();

				return true;
			} else {
				// get operation moves element to front of cache
				// reset the time to live
				mInetAddressList.get(address.toString()).setCreateTime(elementCreateTime);

				return false;
			}
		}
	}

	@Override
	// O(1)
	// using the linkedhashmap's features
	public boolean contains(InetAddress address) {
		synchronized (mInetAddressList) {
			return mInetAddressList.containsKey(address.toString());
		}
	}

	@Override
	// O(1)
	public boolean remove(InetAddress address) {
		// check if the address exists within the linkedhashmap
		if (this.contains(address)) {
			// acquire from the semaphore when removing an element from the
			// cache (ie. reduce the total number of permits)
			try {
				mInetAddressAvailableSemaphore.acquire();
				synchronized (mInetAddressList) {
					mInetAddressList.remove(address.toString());
				}
			} catch (InterruptedException e) {
				System.err.println("Unable to remove: " + address.toString());
				e.printStackTrace();
			}

			return true;
		}

		return false;
	}

	@Override
	// O(1)
	public InetAddress peek() {
		synchronized (mInetAddressList) {
			InetAddress mostRecentElement = null;

			// retrieve the set of keys, order of access is maintained, so the
			// key
			// at the end of the list will be the most recently added element
			if (!this.isEmpty()) {
				List<String> cacheKeySet = new ArrayList<>(mInetAddressList.keySet());
				mostRecentElement = mInetAddressList.get(cacheKeySet.get(cacheKeySet.size() - 1)).getInetAddress();
			}

			return mostRecentElement;
		}
	}

	@Override
	// O(1)
	public InetAddress remove() {
		InetAddress mostRecentElement = null;

		if (!this.isEmpty()) {
			try {
				// decrement the total number of permits the semaphore has
				mInetAddressAvailableSemaphore.acquire();

				// as before, the insertion/access order is maintained so the
				// most recently accessed object is
				// always the last element in the list
				// access it directly and remove it from the linkedhashmap and
				// return it
				synchronized (mInetAddressList) {
					List<String> cacheKeySet = new ArrayList<>(mInetAddressList.keySet());
					if (cacheKeySet.size() > 0) {
						mostRecentElement = mInetAddressList.get(cacheKeySet.get(cacheKeySet.size() - 1))
								.getInetAddress();
						mInetAddressList.remove(mostRecentElement.toString());
					}
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		return mostRecentElement;
	}

	@Override
	// O(1)
	public InetAddress take() throws InterruptedException {
		InetAddress mostRecentElement = null;

		// block until an element becomes available
		// fairness is preserved (threads are unblocked in arrival order)
		mInetAddressAvailableSemaphore.acquire();

		// check if the cache is closed
		// throw an exception if it is closed
		if (isClosed()) {
			throw new InterruptedException();
		}

		// find the most recently added element and remove and return it
		synchronized (mInetAddressList) {
			List<String> cacheKeySet = new ArrayList<>(mInetAddressList.keySet());
			if (cacheKeySet.size() > 0) {
				mostRecentElement = mInetAddressList.get(cacheKeySet.get(cacheKeySet.size() - 1)).getInetAddress();
				mInetAddressList.remove(mostRecentElement.toString());
			}
		}

		return mostRecentElement;
	}

	@Override
	// O(1)
	public void close() {
		// set the isClosed flag to show cache is closed
		// stop the periodic cleanup task from running anymore
		// empty the linkedhashmap maintaining the addresses
		synchronized (mInetAddressList) {
			mIsClosed = true;
			mCleanupTask.cancel();
			mInetAddressList.clear();

			// unblock the blocked threads
			// when they resume, they'll throw an interrupted exception
			// there's only one place where they'll be blocked (in take())
			while (mInetAddressAvailableSemaphore.hasQueuedThreads()) {
				mInetAddressAvailableSemaphore.release();
			}
		}
	}

	@Override
	// O(1)
	public int size() {
		return mInetAddressList.size();
	}

	@Override
	// O(1)
	public boolean isEmpty() {
		return mInetAddressList.isEmpty();
	}

	// O(1)
	public float getCacheTime(InetAddress address) {
		return mCacheTime;
	}

	// O(1)
	public void setCacheTime(InetAddress address, float cacheTime) {
		mCacheTime = cacheTime;
	}

	// O(1)
	public boolean isClosed() {
		return mIsClosed;
	}

	// Testing Methods
	// O(n)
	// print the contents within the cache
	@SuppressWarnings("unchecked")
	public void printCacheContents() {
		synchronized (mInetAddressList) {
			Iterator<?> cacheIterator = mInetAddressList.entrySet().iterator();
			while (cacheIterator.hasNext()) {
				Map.Entry<String, CacheElement> cacheEntry = (Map.Entry<String, CacheElement>) cacheIterator.next();
				System.out.println("Key: " + cacheEntry.getKey());
				System.out.println("Value: " + cacheEntry.getValue());
			}
		}
	}

	// O(1)
	public void printSemaphoreInfo() {
		synchronized (mInetAddressList) {
			System.out.println("Permits Available: " + mInetAddressAvailableSemaphore.availablePermits());
		}
	}

	// O(n)
	class CleanupTask extends TimerTask {
		@SuppressWarnings("unchecked")
		@Override
		public void run() {
			// check if the time elapsed between element creation and current
			// time is more than the TTL
			// if it is, remove the element from the cache
			synchronized (mInetAddressList) {
				Iterator<?> cacheIterator = mInetAddressList.entrySet().iterator();
				while (cacheIterator.hasNext()) {
					Map.Entry<String, CacheElement> cacheEntry = (Map.Entry<String, CacheElement>) cacheIterator.next();
					if (System.currentTimeMillis() - cacheEntry.getValue().getCreateTime() >= mCacheTime * 1000) {
						cacheIterator.remove();
					}
				}
			}
		}
	}
}

// simple class to store the InetAddress and its time of creation to
// calculate TTL
class CacheElement {
	private InetAddress mInetAddress;
	private long mCreateTime;

	public CacheElement(InetAddress address, long createTime) {
		mInetAddress = address;
		mCreateTime = createTime;
	}

	public InetAddress getInetAddress() {
		return mInetAddress;
	}

	public void setInetAddress(InetAddress address) {
		mInetAddress = address;
	}

	public long getCreateTime() {
		return mCreateTime;
	}

	public void setCreateTime(long createTime) {
		mCreateTime = createTime;
	}

	public String toString() {
		StringBuilder outputBuilder = new StringBuilder();
		outputBuilder.append("AddressName: ").append(mInetAddress.toString());
		outputBuilder.append(", ");
		outputBuilder.append("CreateTime: ").append(mCreateTime);

		return outputBuilder.toString();
	}
}
