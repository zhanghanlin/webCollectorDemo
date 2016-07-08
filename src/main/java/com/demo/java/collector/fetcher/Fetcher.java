package com.demo.java.collector.fetcher;

import com.demo.java.collector.crawldb.DBManager;
import com.demo.java.collector.crawldb.Generator;
import com.demo.java.collector.model.CrawlDatum;
import com.demo.java.collector.model.CrawlDatums;
import com.demo.java.collector.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 抓取器
 */
public class Fetcher {

    public static final Logger LOG = LoggerFactory.getLogger(Fetcher.class);

    public DBManager dbManager;
    public Executor executor;
    private AtomicInteger activeThreads;
    private AtomicInteger startedThreads;
    private AtomicInteger spinWaiting;
    private AtomicLong lastRequestStart;
    private QueueFeeder feeder;
    private FetchQueue fetchQueue;
    private long executeInterval = 0;
    public static final int FETCH_SUCCESS = 1;
    public static final int FETCH_FAILED = 2;
    private int threads = 50;

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public static class FetchItem {
        public CrawlDatum datum;

        public FetchItem(CrawlDatum datum) {
            this.datum = datum;
        }
    }

    public static class FetchQueue {

        public AtomicInteger totalSize = new AtomicInteger(0);

        public final List<FetchItem> queue = Collections.synchronizedList(new LinkedList<FetchItem>());

        public void clear() {
            queue.clear();
        }

        public int getSize() {
            return queue.size();
        }

        public synchronized void addFetchItem(FetchItem item) {
            if (item == null) {
                return;
            }
            queue.add(item);
            totalSize.incrementAndGet();
        }

        public synchronized FetchItem getFetchItem() {
            if (queue.isEmpty()) {
                return null;
            }
            return queue.remove(0);
        }

        public synchronized void dump() {
            for (int i = 0; i < queue.size(); i++) {
                FetchItem it = queue.get(i);
                LOG.info("  " + i + ". " + it.datum.getUrl());
            }
        }
    }

    public static class QueueFeeder extends Thread {

        public FetchQueue queue;

        public Generator generator;

        public int size;

        public QueueFeeder(FetchQueue queue, Generator generator, int size) {
            this.queue = queue;
            this.generator = generator;
            this.size = size;
        }

        public void stopFeeder() {
            running = false;
            while (this.isAlive()) {
                try {
                    Thread.sleep(1000);
                    LOG.info("stopping feeder......");
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }

        public boolean running = true;

        @Override
        public void run() {
            boolean hasMore = true;
            running = true;
            while (hasMore && running) {
                int feed = size - queue.getSize();
                if (feed <= 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                    continue;
                }
                while (feed > 0 && hasMore && running) {
                    CrawlDatum datum = generator.next();
                    hasMore = (datum != null);
                    if (hasMore) {
                        queue.addFetchItem(new FetchItem(datum));
                        feed--;
                    }
                }
            }
        }
    }

    private class FetcherThread extends Thread {
        @Override
        public void run() {
            startedThreads.incrementAndGet();
            activeThreads.incrementAndGet();
            FetchItem item;
            try {
                while (running) {
                    try {
                        item = fetchQueue.getFetchItem();
                        if (item == null) {
                            if (feeder.isAlive() || fetchQueue.getSize() > 0) {
                                spinWaiting.incrementAndGet();
                                try {
                                    Thread.sleep(500);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                                spinWaiting.decrementAndGet();
                                continue;
                            } else {
                                return;
                            }
                        }
                        lastRequestStart.set(System.currentTimeMillis());
                        CrawlDatum crawlDatum = item.datum;
                        CrawlDatums next = new CrawlDatums();
                        try {
                            executor.execute(crawlDatum, next);
                            LOG.info("done: " + crawlDatum.getKey());
                            crawlDatum.setStatus(CrawlDatum.STATUS_DB_SUCCESS);
                        } catch (Exception ex) {
                            LOG.error("failed: " + crawlDatum.getKey(), ex);
                            crawlDatum.setStatus(CrawlDatum.STATUS_DB_FAILED);
                        }
                        crawlDatum.incrExecuteCount(1);
                        crawlDatum.setExecuteTime(System.currentTimeMillis());
                        try {
                            dbManager.writeFetchSegment(crawlDatum);
                            if (crawlDatum.getStatus() == CrawlDatum.STATUS_DB_SUCCESS && !next.isEmpty()) {
                                dbManager.writeParseSegment(next);
                            }
                        } catch (Exception ex) {
                            LOG.error("Exception when updating db", ex);
                        }
                        if (executeInterval > 0) {
                            try {
                                Thread.sleep(executeInterval);
                            } catch (Exception sleepEx) {
                                sleepEx.printStackTrace();
                            }
                        }
                    } catch (Exception ex) {
                        LOG.error("Exception", ex);
                    }
                }
            } catch (Exception ex) {
                LOG.error("Exception", ex);
            } finally {
                activeThreads.decrementAndGet();
            }
        }
    }

    /**
     * 抓取当前所有任务，会阻塞到爬取完成
     *
     * @param generator 给抓取提供任务的Generator(抓取任务生成器)
     * @throws IOException 异常
     */
    public void fetchAll(Generator generator) throws Exception {
        if (executor == null) {
            LOG.info("Please Specify A Executor!");
            return;
        }
        try {
            if (dbManager.isLocked()) {
                dbManager.merge();
                dbManager.unlock();
            }
        } catch (Exception ex) {
            LOG.error("Exception when merging history");
        }
        try {
            dbManager.lock();
            generator.open();
            LOG.info("open generator:" + generator.getClass().getName());
            dbManager.initSegmentWriter();
            LOG.info("init segmentWriter:" + dbManager.getClass().getName());
            running = true;
            lastRequestStart = new AtomicLong(System.currentTimeMillis());
            activeThreads = new AtomicInteger(0);
            startedThreads = new AtomicInteger(0);
            spinWaiting = new AtomicInteger(0);
            fetchQueue = new FetchQueue();
            feeder = new QueueFeeder(fetchQueue, generator, 1000);
            feeder.start();
            FetcherThread[] fetcherThreads = new FetcherThread[threads];
            for (int i = 0; i < threads; i++) {
                fetcherThreads[i] = new FetcherThread();
                fetcherThreads[i].start();
            }
            do {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
                LOG.info("-activeThreads=" + activeThreads.get()
                        + ", spinWaiting=" + spinWaiting.get() + ", fetchQueue.size="
                        + fetchQueue.getSize());
                if (!feeder.isAlive() && fetchQueue.getSize() < 5) {
                    fetchQueue.dump();
                }
                if ((System.currentTimeMillis() - lastRequestStart.get()) > Config.THREAD_KILLER) {
                    LOG.info("Aborting with " + activeThreads + " hung threads.");
                    break;
                }
            } while (running && (startedThreads.get() != threads || activeThreads.get() > 0));
            running = false;
            long waitThreadEndStartTime = System.currentTimeMillis();
            if (activeThreads.get() > 0) {
                LOG.info("wait for activeThreads to end");
            }
            /*等待存活线程结束*/
            while (activeThreads.get() > 0) {
                LOG.info("-activeThreads=" + activeThreads.get());
                try {
                    Thread.sleep(500);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                if (System.currentTimeMillis() - waitThreadEndStartTime > Config.WAIT_THREAD_END_TIME) {
                    LOG.info("kill threads");
                    for (int i = 0; i < fetcherThreads.length; i++) {
                        if (fetcherThreads[i].isAlive()) {
                            try {
                                fetcherThreads[i].stop();
                                LOG.info("kill thread " + i);
                            } catch (Exception ex) {
                                LOG.error("Exception", ex);
                            }
                        }
                    }
                    break;
                }
            }
            LOG.info("clear all activeThread");
            feeder.stopFeeder();
            fetchQueue.clear();
        } finally {
            generator.close();
            LOG.info("close generator:" + generator.getClass().getName());
            dbManager.closeSegmentWriter();
            LOG.info("close segmentwriter:" + dbManager.getClass().getName());
            dbManager.merge();
            dbManager.unlock();
        }
    }

    volatile boolean running;

    /**
     * 停止爬取
     */
    public void stop() {
        running = false;
    }

    /**
     * 返回爬虫的线程数
     *
     * @return 爬虫的线程数
     */
    public int getThreads() {
        return threads;
    }

    /**
     * 设置爬虫的线程数
     *
     * @param threads 爬虫的线程数
     */
    public void setThreads(int threads) {
        this.threads = threads;
    }

    public DBManager getDBManager() {
        return dbManager;
    }

    public void setDBManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }

    public long getExecuteInterval() {
        return executeInterval;
    }

    public void setExecuteInterval(long executeInterval) {
        this.executeInterval = executeInterval;
    }
}
