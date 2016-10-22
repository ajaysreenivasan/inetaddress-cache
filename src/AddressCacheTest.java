import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Timer;
import java.util.TimerTask;

public class AddressCacheTest {
    AddressCacheImplementation mAddressCache;

    // one producer thread to insert elements into the cache
    // two consumer threads to perform the other operations
    // only operations tested here were offer(), remove(InetAddress address),
    // peek(), remove(), take(), and close()
    // the other methods, contains(InetAddress address), size(), and isEmpty()
    // are rather trivial and call on the linkedhashmap's functionalities
    Producer producer;
    Consumer consumer1;
    Consumer consumer2;

    public static void main(String[] args) {
        AddressCacheTest newTest = new AddressCacheTest();
        newTest.runTest();
    }

    public void runTest() {
        // modify the cache elements' TTL with cacheTime
        float cacheTime = 0.1f;
        mAddressCache = new AddressCacheImplementation(cacheTime);

        producer = new Producer("Producer", mAddressCache);
        consumer1 = new Consumer("Consumer_1", mAddressCache);
        consumer2 = new Consumer("Consumer_2", mAddressCache);

        producer.start();
        consumer1.start();
        consumer2.start();

        // create a cleanup task and schedule it to wait 5 seconds, then run
        // every 5 seconds
        CloseCacheTask closeCacheTask = new CloseCacheTask();
        Timer taskTimer = new Timer();
        taskTimer.schedule(closeCacheTask, 15000);
    }

    // task to close the cache after a given amount of time
    // time can be set when task is scheduled
    class CloseCacheTask extends TimerTask {
        @Override
        public void run() {
            System.out.println("Cache Closed.");
            mAddressCache.close();
        }
    }
}

class Producer implements Runnable {
    private Thread mProducerThread;
    private String mThreadName;
    private AddressCacheImplementation mAddressCache;

    Producer(String threadName, AddressCacheImplementation addressCache) {
        mThreadName = threadName;
        mAddressCache = addressCache;
    }

    // use a 10 element long list of hostnames for testing
    // randomly pick one hostname and offer() it to the cache
    public void run() {
        String[] hostNameList = { "www.google.com", "www.facebook.com", "www.youtube.com", "www.baidu.com", "www.yahoo.com", "www.wikipedia.org.",
                "www.amazon.com", "www.twitter.com", "www.linkedin.com", "www.imgur.com" };

        // run until the cache is closed or the thread is interrupted
        while (!mAddressCache.isClosed() && !Thread.interrupted()) {
            try {
                int index = (int) (Math.random() * 10);

                System.out.println(mThreadName + ": Offering - " + hostNameList[index]);
                InetAddress newInetAddress = InetAddress.getByName(hostNameList[index]);
                System.out.println(mThreadName + ": OfferingAccepted - " + mAddressCache.offer(newInetAddress));
            }
            catch (UnknownHostException e) {
                System.err.println(mThreadName + ": Failed to retrieve address by name.");
                e.printStackTrace();
            }
        }
    }

    public void start() {
        if (mProducerThread == null) {
            mProducerThread = new Thread(this, mThreadName);
            mProducerThread.start();
        }
    }
}

class Consumer implements Runnable {
    private Thread mConsumerThread;
    private String mThreadName;
    private AddressCacheImplementation mAddressCache;

    Consumer(String threadName, AddressCacheImplementation addressCache) {
        mThreadName = threadName;
        mAddressCache = addressCache;
    }

    // the same list of hostnames as in producer
    public void run() {
        String[] hostNameList = { "www.google.com", "www.facebook.com", "www.youtube.com", "www.baidu.com", "www.yahoo.com", "www.wikipedia.org.",
                "www.amazon.com", "www.twitter.com", "www.linkedin.com", "www.imgur.com" };

        // run until the cache is closed or the thread is interrupted
        // randomly generate an index to find a hostname to remove or peek
        // randomly choose an action from remove(InetAddress), remove(), peek() and take()
        // can select one test only by fixing the action value to a constant
        while (!mAddressCache.isClosed() && !Thread.interrupted()) {
            // System.out.println(mThreadName + ": " + mAddressCache.size());
            int index = (int) (Math.random() * 10);
            int action = (int) (Math.random() * 10) % 4;

            if (action == 0) { // remove(InetAddress) test
                InetAddress toRemove;
                try {
                    toRemove = InetAddress.getByName(hostNameList[index]);
                    System.out.println(mThreadName + ": Removing - " + hostNameList[index]);
                    System.out.println(mThreadName + ": Removal - " + mAddressCache.remove(toRemove));
                }
                catch (UnknownHostException e) {
                    System.err.println(mThreadName + ": Failed to retrieve address by name.");
                    e.printStackTrace();
                }
            }
            else if (action == 1) { // peek() test
                System.out.println(mThreadName + ": Peeking");
                System.out.println(mThreadName + ": Peek found - " + mAddressCache.peek());
            }
            else if (action == 2) { // remove() test
                System.out.println(mThreadName + ": Removing first element");
                System.out.println(mThreadName + ": Removed - " + mAddressCache.remove());
            }
            else if (action == 3) { // take() test
                try {
                    System.out.println(mThreadName + ": Taking " + mAddressCache.take());
                }
                catch (InterruptedException e) {
                    System.out.println(mThreadName + ": Unable to take most recently added element. Cache may be unavailable.");
                    // e.printStackTrace();
                }
            }
        }
    }

    public void start() {
        if (mConsumerThread == null) {
            mConsumerThread = new Thread(this, mThreadName);
            mConsumerThread.start();
        }
    }
}