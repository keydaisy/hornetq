/*
 * Copyright 2010 Red Hat, Inc.
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.hornetq.tests.stress.journal;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.hornetq.api.core.HornetQExceptionType;
import org.hornetq.core.asyncio.impl.AsynchronousFileImpl;
import org.hornetq.core.config.impl.ConfigurationImpl;
import org.hornetq.core.journal.IOAsyncTask;
import org.hornetq.core.journal.PreparedTransactionInfo;
import org.hornetq.core.journal.RecordInfo;
import org.hornetq.core.journal.SequentialFileFactory;
import org.hornetq.core.journal.TransactionFailureCallback;
import org.hornetq.core.journal.impl.AIOSequentialFileFactory;
import org.hornetq.core.journal.impl.JournalImpl;
import org.hornetq.core.journal.impl.NIOSequentialFileFactory;
import org.hornetq.core.persistence.impl.journal.OperationContextImpl;
import org.hornetq.tests.util.RandomUtil;
import org.hornetq.tests.util.ServiceTestBase;
import org.hornetq.utils.HornetQThreadFactory;
import org.hornetq.utils.OrderedExecutorFactory;
import org.hornetq.utils.SimpleIDGenerator;

/**
 * A SoakJournal
 *
 * @author <a href="mailto:clebert.suconic@jboss.org">Clebert Suconic</a>
 *
 *
 */
public class JournalCleanupCompactStressTest extends ServiceTestBase
{

   public static SimpleIDGenerator idGen = new SimpleIDGenerator(1);

   private static final int MAX_WRITES = 20000;
   
   private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

   // We want to maximize the difference between appends and deles, or we could get out of memory
   public Semaphore maxRecords;

   private volatile boolean running;

   private AtomicInteger errors = new AtomicInteger(0);

   private AtomicInteger numberOfRecords = new AtomicInteger(0);

   private AtomicInteger numberOfUpdates = new AtomicInteger(0);

   private AtomicInteger numberOfDeletes = new AtomicInteger(0);

   private JournalImpl journal;

   ThreadFactory tFactory = new HornetQThreadFactory("SoakTest" + System.identityHashCode(this),
                                                     false,
                                                     JournalCleanupCompactStressTest.class.getClassLoader());

   private ExecutorService threadPool;

   private OrderedExecutorFactory executorFactory = new OrderedExecutorFactory(threadPool);

   Executor testExecutor;

   protected long getTotalTimeMilliseconds()
   {
      return TimeUnit.MINUTES.toMillis(2);
   }


   @Override
   public void setUp() throws Exception
   {
      super.setUp();

      threadPool = Executors.newFixedThreadPool(20, tFactory);
      executorFactory = new OrderedExecutorFactory(threadPool);
      testExecutor = executorFactory.getExecutor();

      maxRecords = new Semaphore(MAX_WRITES);

      errors.set(0);

      File dir = new File(getTemporaryDir());
      dir.mkdirs();

      SequentialFileFactory factory;

      int maxAIO;
      if (AsynchronousFileImpl.isLoaded())
      {
         factory = new AIOSequentialFileFactory(dir.getPath());
         maxAIO = ConfigurationImpl.DEFAULT_JOURNAL_MAX_IO_AIO;
      }
      else
      {
         factory = new NIOSequentialFileFactory(dir.getPath(), true);
         maxAIO = ConfigurationImpl.DEFAULT_JOURNAL_MAX_IO_NIO;
      }

      journal = new JournalImpl(50 * 1024,
                                20,
                                50, 
                                ConfigurationImpl.DEFAULT_JOURNAL_COMPACT_PERCENTAGE,
                                factory,
                                "hornetq-data",
                                "hq",
                                maxAIO)
      {
         protected void onCompactLockingTheJournal() throws Exception
         {
         }

         protected void onCompactStart() throws Exception
         {
            testExecutor.execute(new Runnable()
            {
               public void run()
               {
                  try
                  {
                     // System.out.println("OnCompactStart enter");
                     if (running)
                     {
                        long id = idGen.generateID();
                        journal.appendAddRecord(id, (byte)0, new byte[] { 1, 2, 3 }, false);
                        journal.forceMoveNextFile();
                        journal.appendDeleteRecord(id, id == 20);
                     }
                     // System.out.println("OnCompactStart leave");
                  }
                  catch (Exception e)
                  {
                     e.printStackTrace();
                     errors.incrementAndGet();
                  }
               }
            });

         }

      };

      journal.start();
      journal.loadInternalOnly();

   }

   @Override
   public void tearDown() throws Exception
   {
      try
      {
         if (journal.isStarted())
         {
            journal.stop();
         }
      }
      catch (Exception e)
      {
         // don't care :-)
      }

      threadPool.shutdown();
   }

   public void testAppend() throws Exception
   {

      running = true;
      SlowAppenderNoTX t1 = new SlowAppenderNoTX();

      int NTHREADS = 5;

      FastAppenderTx appenders[] = new FastAppenderTx[NTHREADS];
      FastUpdateTx updaters[] = new FastUpdateTx[NTHREADS];

      for (int i = 0; i < NTHREADS; i++)
      {
         appenders[i] = new FastAppenderTx();
         updaters[i] = new FastUpdateTx(appenders[i].queue);
      }

      t1.start();

      Thread.sleep(1000);

      for (int i = 0; i < NTHREADS; i++)
      {
         appenders[i].start();
         updaters[i].start();
      }

      long timeToEnd = System.currentTimeMillis() + getTotalTimeMilliseconds();

      while (System.currentTimeMillis() < timeToEnd)
      {
         System.out.println("Append = " + numberOfRecords +
                            ", Update = " +
                            numberOfUpdates +
                            ", Delete = " +
                            numberOfDeletes +
                            ", liveRecords = " +
                            (numberOfRecords.get() - numberOfDeletes.get()));
         Thread.sleep(TimeUnit.SECONDS.toMillis(10));
         rwLock.writeLock().lock();
         System.out.println("Restarting server");
         journal.stop();
         journal.start();
         reloadJournal();
         rwLock.writeLock().unlock();
      }

      running = false;

      // Release Semaphore after setting running to false or the threads may never finish
      maxRecords.release(MAX_WRITES - maxRecords.availablePermits());

      for (Thread t : appenders)
      {
         t.join();
      }

      for (Thread t : updaters)
      {
         t.join();
      }

      t1.join();

      final CountDownLatch latchExecutorDone = new CountDownLatch(1);
      testExecutor.execute(new Runnable()
      {
         public void run()
         {
            latchExecutorDone.countDown();
         }
      });

      latchExecutorDone.await();

      journal.stop();

      journal.start();

      reloadJournal();
      
      Collection<Long> records = journal.getRecords().keySet();
      
      System.out.println("Deleting everything!");
      for (Long delInfo : records)
      {
         journal.appendDeleteRecord(delInfo, false);
      }
      
      journal.forceMoveNextFile();
      
      journal.checkReclaimStatus();
      
      Thread.sleep(5000);
      
      assertEquals(0, journal.getDataFilesCount());

      journal.stop();
   }

   /**
    * @throws Exception
    */
   private void reloadJournal() throws Exception
   {
      assertEquals(0, errors.get());
      
      ArrayList<RecordInfo> committedRecords = new ArrayList<RecordInfo>();
      ArrayList<PreparedTransactionInfo> preparedTransactions = new ArrayList<PreparedTransactionInfo>();
      journal.load(committedRecords, preparedTransactions, new TransactionFailureCallback()
      {
         public void failedTransaction(long transactionID, List<RecordInfo> records, List<RecordInfo> recordsToDelete)
         {
         }
      });

      long appends = 0, updates = 0;

      for (RecordInfo record : committedRecords)
      {
         if (record.isUpdate)
         {
            updates++;
         }
         else
         {
            appends++;
         }
      }

      assertEquals(numberOfRecords.get() - numberOfDeletes.get(), appends);
   }

   private byte[] generateRecord()
   {
      int size = RandomUtil.randomPositiveInt() % 10000;
      if (size == 0)
      {
         size = 10000;
      }
      return RandomUtil.randomBytes(size);
   }

   class FastAppenderTx extends Thread
   {
      LinkedBlockingDeque<Long> queue = new LinkedBlockingDeque<Long>();

      OperationContextImpl ctx = new OperationContextImpl(executorFactory.getExecutor());

      public FastAppenderTx()
      {
         super("FastAppenderTX");
      }

      @Override
      public void run()
      {
         rwLock.readLock().lock();
         
         try
         {
            while (running)
            {
               final int txSize = RandomUtil.randomMax(100);

               long txID = JournalCleanupCompactStressTest.idGen.generateID();

               long rollbackTXID = JournalCleanupCompactStressTest.idGen.generateID();

               final long ids[] = new long[txSize];

               for (int i = 0; i < txSize; i++)
               {
                  ids[i] = JournalCleanupCompactStressTest.idGen.generateID();
               }

               journal.appendAddRecordTransactional(rollbackTXID, ids[0], (byte)0, generateRecord());
               journal.appendRollbackRecord(rollbackTXID, true);

               for (int i = 0; i < txSize; i++)
               {
                  long id = ids[i];
                  journal.appendAddRecordTransactional(txID, id, (byte)0, generateRecord());
                  maxRecords.acquire();
               }
               journal.appendCommitRecord(txID, true, ctx);

               ctx.executeOnCompletion(new IOAsyncTask()
               {

                  public void onError(final int errorCode, final String errorMessage)
                  {
                  }

                  public void done()
                  {
                     numberOfRecords.addAndGet(txSize);
                     for (Long id : ids)
                     {
                        queue.add(id);
                     }
                  }
               });
               
               rwLock.readLock().unlock();
               
               Thread.yield();
             
               rwLock.readLock().lock();
               

            }
         }
         catch (Exception e)
         {
            e.printStackTrace();
            running = false;
            errors.incrementAndGet();
         }
         finally
         {
            rwLock.readLock().unlock();
         }
      }
   }

   class FastUpdateTx extends Thread
   {
      final LinkedBlockingDeque<Long> queue;

      OperationContextImpl ctx = new OperationContextImpl(executorFactory.getExecutor());

      public FastUpdateTx(final LinkedBlockingDeque<Long> queue)
      {
         super("FastUpdateTX");
         this.queue = queue;
      }

      @Override
      public void run()
      {

         rwLock.readLock().lock();

         try
         {
            int txSize = RandomUtil.randomMax(100);
            int txCount = 0;
            long ids[] = new long[txSize];

            long txID = JournalCleanupCompactStressTest.idGen.generateID();
            
            while (running)
            {

               Long id = queue.poll(10, TimeUnit.SECONDS);
               if (id != null)
               {
                  ids[txCount++] = id;
                  journal.appendUpdateRecordTransactional(txID, id, (byte)0, generateRecord());
               }
               if (txCount == txSize || id == null)
               {
                  if (txCount > 0)
                  {
                     journal.appendCommitRecord(txID, true, ctx);
                     ctx.executeOnCompletion(new DeleteTask(ids));
                  }
                  
                  rwLock.readLock().unlock();
                  
                  Thread.yield();
                  
                  rwLock.readLock().lock();
                  
                  txCount = 0;
                  txSize = RandomUtil.randomMax(100);
                  txID = JournalCleanupCompactStressTest.idGen.generateID();
                  ids = new long[txSize];
               }
            }

            if (txCount > 0)
            {
               journal.appendCommitRecord(txID, true);
            }
         }
         catch (Exception e)
         {
            e.printStackTrace();
            running = false;
            errors.incrementAndGet();
         }
         finally
         {
            rwLock.readLock().unlock();
         }
      }
   }

   class DeleteTask implements IOAsyncTask
   {
      final long ids[];

      DeleteTask(final long ids[])
      {
         this.ids = ids;
      }

      public void done()
      {
         rwLock.readLock().lock();
         numberOfUpdates.addAndGet(ids.length);
         try
         {
            for (long id : ids)
            {
               if (id != 0)
               {
                  journal.appendDeleteRecord(id, false);
                  maxRecords.release();
                  numberOfDeletes.incrementAndGet();
               }
            }
         }
         catch (Exception e)
         {
            System.err.println("Can't delete id");
            e.printStackTrace();
            running = false;
            errors.incrementAndGet();
         }
         finally
         {
            rwLock.readLock().unlock();
         }
      }

      public void onError(final int errorCode, final String errorMessage)
      {
      }

   }

   /** Adds stuff to the journal, but it will take a long time to remove them.
    *  This will cause cleanup and compacting to happen more often
    */
   class SlowAppenderNoTX extends Thread
   {

      public SlowAppenderNoTX()
      {
         super("SlowAppender");
      }

      @Override
      public void run()
      {
         rwLock.readLock().lock();
         try
         {
            while (running)
            {
               long ids[] = new long[5];
               // Append
               for (int i = 0; running & i < ids.length; i++)
               {
                  System.out.println("append slow");
                  ids[i] = JournalCleanupCompactStressTest.idGen.generateID();
                  maxRecords.acquire();
                  journal.appendAddRecord(ids[i], (byte)1, generateRecord(), true);
                  numberOfRecords.incrementAndGet();

                  rwLock.readLock().unlock();
                  
                  Thread.sleep(TimeUnit.SECONDS.toMillis(50));
                  
                  rwLock.readLock().lock();
               }
               // Delete
               for (int i = 0; running & i < ids.length; i++)
               {
                  System.out.println("Deleting");
                  maxRecords.release();
                  journal.appendDeleteRecord(ids[i], false);
                  numberOfDeletes.incrementAndGet();
               }
            }
         }
         catch (Exception e)
         {
            e.printStackTrace();
            System.exit(-1);
         }
         finally
         {
            rwLock.readLock().unlock();
         }
      }
   }

   // Constants -----------------------------------------------------

   // Attributes ----------------------------------------------------

   // Static --------------------------------------------------------

   // Constructors --------------------------------------------------

   // Public --------------------------------------------------------

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   // Private -------------------------------------------------------

   // Inner classes -------------------------------------------------

}
