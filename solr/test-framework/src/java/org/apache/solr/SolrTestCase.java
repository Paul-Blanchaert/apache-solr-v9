/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr;

import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.carrotsearch.randomizedtesting.RandomizedContext;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakLingering;
import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.QuickPatchThreadsFilter;
import org.apache.lucene.util.TimeUnits;
import org.apache.solr.client.solrj.embedded.SolrQueuedThreadPool;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.cloud.autoscaling.ScheduledTriggers;
import org.apache.solr.common.TimeTracker;
import org.apache.solr.servlet.SolrDispatchFilter;
import org.apache.solr.util.ExternalPaths;
import org.apache.solr.util.RandomizeSSL;
import org.apache.solr.util.RevertDefaultThreadHandlerRule;
import org.apache.solr.util.SSLTestConfig;
import org.apache.solr.util.StartupLoggingUtils;
import org.apache.solr.util.TestInjection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotsearch.randomizedtesting.rules.SystemPropertiesRestoreRule;

import static com.carrotsearch.randomizedtesting.RandomizedTest.systemPropertyAsBoolean;

/**
 * All Solr test cases should derive from this class eventually. This is originally a result of async logging, see:
 * SOLR-12055 and associated. To enable async logging, we must gracefully shut down logging. Many Solr tests subclass
 * LuceneTestCase.
 *
 * Rather than add the cruft from SolrTestCaseJ4 to all the Solr tests that currently subclass LuceneTestCase,
 * we'll add the shutdown to this class and subclass it.
 *
 * Other changes that should affect every Solr test case may go here if they don't require the added capabilities in
 * SolrTestCaseJ4.
 */
//0p-@TimeoutSuite(millis = 130 * TimeUnits.SECOND)
@ThreadLeakFilters(defaultFilters = true, filters = {
        SolrIgnoredThreadsFilter.class,
        QuickPatchThreadsFilter.class
})
@LuceneTestCase.SuppressSysoutChecks(bugUrl = "Solr dumps tons of logs to console.")
@LuceneTestCase.SuppressFileSystems("ExtrasFS") // might be ok, the failures with e.g. nightly runs might be "normal"
@RandomizeSSL()
@ThreadLeakLingering(linger = 0)
public class SolrTestCase extends LuceneTestCase {

  /**
   * <b>DO NOT REMOVE THIS LOGGER</b>
   * <p>
   * For reasons that aren't 100% obvious, the existence of this logger is neccessary to ensure
   * that the logging framework is properly initialized (even if concrete subclasses do not 
   * themselves initialize any loggers) so that the async logger threads can be properly shutdown
   * on completion of the test suite
   * </p>
   * @see <a href="https://issues.apache.org/jira/browse/SOLR-14247">SOLR-14247</a>
   * @see #shutdownLogger
   */
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @ClassRule
  public static TestRule solrClassRules = 
    RuleChain.outerRule(new SystemPropertiesRestoreRule())
             .around(new RevertDefaultThreadHandlerRule());
  private static volatile Random random;

  @Rule
  public TestRule solrTestRules =
          RuleChain.outerRule(new SystemPropertiesRestoreRule());

  public static final int DEFAULT_ZK_SESSION_TIMEOUT = 20000;  // default socket connection timeout in ms
  public static final int DEFAULT_CONNECTION_TIMEOUT = 10000;  // default socket connection timeout in ms
  public static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 15000;

  private static final int SOLR_TEST_TIMEOUT = Integer.getInteger("solr.test.timeout", 25);

  private static long testStartTime;

  // these are meant to be accessed sequentially, but are volatile just to ensure any test
  // thread will read the latest value
  protected static volatile SSLTestConfig sslConfig;

  private volatile static String interuptThreadWithNameContains;

  public static Random random() {
    return random;
  }

  /**
   * Sets the <code>solr.default.confdir</code> system property to the value of 
   * {@link ExternalPaths#DEFAULT_CONFIGSET} if and only if the system property is not already set, 
   * and the <code>DEFAULT_CONFIGSET</code> exists and is a readable directory.
   * <p>
   * Logs INFO/WARNing messages as appropriate based on these 2 conditions.
   * </p>
   * @see SolrDispatchFilter#SOLR_DEFAULT_CONFDIR_ATTRIBUTE
   */
  @BeforeClass
  public static void setDefaultConfigDirSysPropIfNotSet() throws Exception {
    // random is expensive, you are supposed to cache it
    random = LuceneTestCase.random();

    testStartTime = System.nanoTime();
    // stop zkserver threads that can linger
    //interruptThreadsOnTearDown("nioEventLoopGroup", false);

    sslConfig = buildSSLConfig();

    HttpClientUtil.setSocketFactoryRegistryProvider(sslConfig.buildClientSocketFactoryRegistryProvider());
    Http2SolrClient.setDefaultSSLConfig(sslConfig.buildClientSSLConfig());
    // based on randomized SSL config, set SocketFactoryRegistryProvider appropriately
    if(isSSLMode()) {
      // SolrCloud tests should usually clear this
      System.setProperty("urlScheme", "https");
    } else {
      System.setProperty("urlScheme", "http");
    }

    System.setProperty("solr.zkclienttimeout", "30000");
    System.setProperty("solr.v2RealPath", "true");
    System.setProperty("zookeeper.forceSync", "no");
    System.setProperty("jetty.testMode", "true");
    System.setProperty("enable.update.log", usually() ? "true" : "false");
    System.setProperty("tests.shardhandler.randomSeed", Long.toString(random().nextLong()));
    System.setProperty("solr.clustering.enabled", "false");
    System.setProperty("solr.peerSync.useRangeVersions", String.valueOf(random().nextBoolean()));
    System.setProperty("zookeeper.nio.directBufferBytes", Integer.toString(64 * 1024 * 2));


    if (!TEST_NIGHTLY) {
      TestInjection.randomDelayMaxInCoreCreationInSec = 2;


      System.setProperty("zookeeper.nio.numSelectorThreads", "1");
      System.setProperty("zookeeper.nio.numWorkerThreads", "3");
      System.setProperty("zookeeper.commitProcessor.numWorkerThreads", "1");
      System.setProperty("zookeeper.skipACL", "true");
      System.setProperty("zookeeper.nio.shutdownTimeout", "10");

      // can make things quite slow
      System.setProperty("solr.disableJmxReporter", "true");
      System.setProperty("solr.skipCommitOnClose", "true");
     // System.setProperty("solr.skipReloadSPI", "true");

      System.setProperty("solr.OverseerStateUpdateDelay", "1000");

      System.setProperty("solr.leaderThrottle", "0");
      System.setProperty("solr.recoveryThrottle", "0");

      System.setProperty("solr.suppressDefaultConfigBootstrap", "true");

      System.setProperty("solr.defaultCollectionActiveWait", "10");

      System.setProperty("solr.http2solrclient.maxpool.size", "6");
      System.setProperty("solr.http2solrclient.pool.keepalive", "5000");

      System.setProperty("solr.disablePublicKeyHandler", "true");
      System.setProperty("solr.dependentupdate.timeout", "1"); // seconds

      System.setProperty("lucene.cms.override_core_count", "2");
      System.setProperty("lucene.cms.override_spins", "false");

      System.setProperty("solr.maxContainerThreads", "300");
      System.setProperty("solr.lowContainerThreadsThreshold", "-1");
      System.setProperty("solr.minContainerThreads", "20");

      ScheduledTriggers.DEFAULT_COOLDOWN_PERIOD_SECONDS = 1;
      ScheduledTriggers.DEFAULT_ACTION_THROTTLE_PERIOD_SECONDS =1;
      ScheduledTriggers.DEFAULT_TRIGGER_CORE_POOL_SIZE = 2;

      System.setProperty("solr.tests.maxBufferedDocs", "1000000");
      System.setProperty("solr.tests.ramBufferSizeMB", "20");
      System.setProperty("solr.tests.ramPerThreadHardLimitMB", "4");


      System.setProperty("distribUpdateSoTimeout", "10000");
      System.setProperty("socketTimeout", "15000");
      System.setProperty("connTimeout", "10000");
      System.setProperty("solr.test.socketTimeout.default", "15000");
      System.setProperty("solr.connect_timeout.default", "10000");
      System.setProperty("solr.so_commit_timeout.default", "15000");
      System.setProperty("solr.httpclient.defaultConnectTimeout", "10000");
      System.setProperty("solr.httpclient.defaultSoTimeout", "15000");
      // System.setProperty("solr.containerThreadsIdle", "30000"); no need to set

      System.setProperty("solr.indexfetcher.sotimeout", "15000");
      System.setProperty("solr.indexfetch.so_timeout.default", "15000");

      System.setProperty("prepRecoveryReadTimeoutExtraWait", "0");
      System.setProperty("validateAfterInactivity", "-1");
      System.setProperty("leaderVoteWait", "5000"); // this is also apparently controlling how long we wait for a leader on register nocommit
      System.setProperty("leaderConflictResolveWait", "10000");

      System.setProperty("solr.recovery.recoveryThrottle", "250");
      System.setProperty("solr.recovery.leaderThrottle", "50");

      System.setProperty("bucketVersionLockTimeoutMs", "8000");
      System.setProperty("socketTimeout", "30000");
      System.setProperty("connTimeout", "10000");
      System.setProperty("solr.cloud.wait-for-updates-with-stale-state-pause", "0");
      System.setProperty("solr.cloud.starting-recovery-delay-milli-seconds", "0");

      System.setProperty("solr.waitForState", "5"); // secs

      System.setProperty("solr.default.collection_op_timeout", "15000");


      System.setProperty("solr.httpclient.retries", "1");
      System.setProperty("solr.retries.on.forward", "1");
      System.setProperty("solr.retries.to.followers", "1");

      SolrTestCaseJ4.useFactory("org.apache.solr.core.RAMDirectoryFactory");
      System.setProperty("solr.lock.type", "single");
      System.setProperty("solr.tests.lockType", "single");

      System.setProperty("solr.tests.mergePolicyFactory", "org.apache.solr.index.NoMergePolicyFactory");
      System.setProperty("solr.tests.mergeScheduler", "org.apache.lucene.index.ConcurrentMergeScheduler");
      System.setProperty("solr.mscheduler", "org.apache.lucene.index.ConcurrentMergeScheduler");

      System.setProperty("solr.codec", "solr.SchemaCodecFactory");
      System.setProperty("tests.COMPRESSION_MODE", "BEST_COMPRESSION");
    }


    final String existingValue = System.getProperty(SolrDispatchFilter.SOLR_DEFAULT_CONFDIR_ATTRIBUTE);
    if (null != existingValue) {
      log.info("Test env includes configset dir system property '{}'='{}'", SolrDispatchFilter.SOLR_DEFAULT_CONFDIR_ATTRIBUTE, existingValue);
      return;
    }
    final File extPath = new File(ExternalPaths.DEFAULT_CONFIGSET);
    if (extPath.canRead(/* implies exists() */) && extPath.isDirectory()) {
      log.info("Setting '{}' system property to test-framework derived value of '{}'",
               SolrDispatchFilter.SOLR_DEFAULT_CONFDIR_ATTRIBUTE, ExternalPaths.DEFAULT_CONFIGSET);
      assert null == existingValue;
      System.setProperty(SolrDispatchFilter.SOLR_DEFAULT_CONFDIR_ATTRIBUTE, ExternalPaths.DEFAULT_CONFIGSET);
    } else {
      log.warn("System property '{}' is not already set, but test-framework derived value ('{}') either " +
               "does not exist or is not a readable directory, you may need to set the property yourself " +
               "for tests to run properly",
               SolrDispatchFilter.SOLR_DEFAULT_CONFDIR_ATTRIBUTE, ExternalPaths.DEFAULT_CONFIGSET);
    }
  }

  protected static boolean isSSLMode() {
    return sslConfig != null && sslConfig.isSSLMode();
  }
  
  /** 
   * Special hook for sanity checking if any tests trigger failures when an
   * Assumption failure occures in a {@link BeforeClass} method
   * @lucene.internal
   */
  @BeforeClass
  public static void checkSyspropForceBeforeClassAssumptionFailure() {
    // ant test -Dargs="-Dtests.force.assumption.failure.beforeclass=true"
    final String PROP = "tests.force.assumption.failure.beforeclass";
    assumeFalse(PROP + " == true",
                systemPropertyAsBoolean(PROP, false));
  }
  
  /** 
   * Special hook for sanity checking if any tests trigger failures when an
   * Assumption failure occures in a {@link Before} method
   * @lucene.internal
   */
  @Before
  public void checkSyspropForceBeforeAssumptionFailure() {
    // ant test -Dargs="-Dtests.force.assumption.failure.before=true"
    final String PROP = "tests.force.assumption.failure.before";
    assumeFalse(PROP + " == true",
                systemPropertyAsBoolean(PROP, false));
  }
  
  @AfterClass
  public static void shutdownLogger() throws Exception {

    try {
      HttpClientUtil.resetHttpClientBuilder();
      Http2SolrClient.resetSslContextFactory();
      TestInjection.reset();
      StartupLoggingUtils.shutdown();

      checkForInterruptRequest();

      long testTime = TimeUnit.SECONDS.convert(System.nanoTime() - testStartTime, TimeUnit.NANOSECONDS);
      if (suiteFailureMarker.wasSuccessful() && !TEST_NIGHTLY && testTime > SOLR_TEST_TIMEOUT) {
        log.error("This test suite is too long for non @Nightly runs! Please improve it's performance, break it up, make parts of it @Nightly or make the whole suite @Nightly: "
                + testTime);
//          fail(
//              "This test suite is too long for non @Nightly runs! Please improve it's performance, break it up, make parts of it @Nightly or make the whole suite @Nightly: "
//                  + testTime);
      }
    } finally {
      // nocommit - this should not be necessary, check
      TimeTracker.CLOSE_TIMES.clear();
    }
  }

  private static SSLTestConfig buildSSLConfig() {

    if (!TEST_NIGHTLY) {
      return new SSLTestConfig();
    }

    RandomizeSSL.SSLRandomizer sslRandomizer =
            RandomizeSSL.SSLRandomizer.getSSLRandomizerForClass(RandomizedContext.current().getTargetClass());

    if (Constants.MAC_OS_X) {
      // see SOLR-9039
      // If a solution is found to remove this, please make sure to also update
      // TestMiniSolrCloudClusterSSL.testSslAndClientAuth as well.
      sslRandomizer = new RandomizeSSL.SSLRandomizer(sslRandomizer.ssl, 0.0D, (sslRandomizer.debug + " w/ MAC_OS_X supressed clientAuth"));
    }

    SSLTestConfig result = sslRandomizer.createSSLTestConfig();
    if (log.isInfoEnabled()) {
      log.info("Randomized ssl ({}) and clientAuth ({}) via: {}",
              result.isSSLMode(), result.isClientAuthMode(), sslRandomizer.debug);
    }
    return result;
  }

  private static void checkForInterruptRequest() {
    try {
      String interruptThread = interuptThreadWithNameContains;
      if (interruptThread != null) {
        interruptThreadsOnTearDown(interruptThread, true);
        interuptThreadWithNameContains = null;
      }
    } catch (Exception e) {
      log.error("", e);
    }
  }


  // expert - for special cases
  public static void interruptThreadsOnTearDown(String nameContains, boolean now) {
    if (!now) {
      interuptThreadWithNameContains = nameContains;
      return;
    }

    System.out.println("DO FORCED INTTERUPTS");
    //  we need to filter and only do this for known threads? dont want users to count on this behavior unless necessary
    String testThread = Thread.currentThread().getName();
    //System.out.println("test thread:" + testThread);
    ThreadGroup tg = Thread.currentThread().getThreadGroup();
    //System.out.println("test group:" + tg.getName());
    Set<Map.Entry<Thread,StackTraceElement[]>> threadSet = Thread.getAllStackTraces().entrySet();
    //System.out.println("thread count: " + threadSet.size());
    for (Map.Entry<Thread,StackTraceElement[]> threadEntry : threadSet) {
      Thread thread = threadEntry.getKey();
      ThreadGroup threadGroup = thread.getThreadGroup();
      if (threadGroup != null) {
        if (threadGroup.getName().equals(tg.getName()) && !thread.getName().startsWith("SUITE") && !thread.getName().startsWith("Log4j2")) {
          interrupt(thread, nameContains);
          continue;
        }
      }

      while (threadGroup != null && threadGroup.getParent() != null && !thread.getName().startsWith("SUITE") && !thread.getName().startsWith("Log4j2")) {
        threadGroup = threadGroup.getParent();
        if (threadGroup.getName().equals(tg.getName())) {
          interrupt(thread, nameContains);
          continue;
        }
      }
    }
  }

  private static void interrupt(Thread thread, String nameContains) {
    if (thread.getName().contains(nameContains)) {
      System.out.println("do interrupt on " + thread.getName());
      thread.interrupt();
    }
  }

}
