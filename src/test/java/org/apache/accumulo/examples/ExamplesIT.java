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
package org.apache.accumulo.examples;

import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.accumulo.cluster.standalone.StandaloneAccumuloCluster;
import org.apache.accumulo.cluster.standalone.StandaloneClusterControl;
import org.apache.accumulo.core.cli.BatchWriterOpts;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.AgeOffFilter;
import org.apache.accumulo.core.iterators.user.SummingCombiner;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.examples.client.Flush;
import org.apache.accumulo.examples.client.RandomBatchScanner;
import org.apache.accumulo.examples.client.RandomBatchWriter;
import org.apache.accumulo.examples.client.ReadWriteExample;
import org.apache.accumulo.examples.client.RowOperations;
import org.apache.accumulo.examples.client.SequentialBatchWriter;
import org.apache.accumulo.examples.client.TraceDumpExample;
import org.apache.accumulo.examples.client.TracingExample;
import org.apache.accumulo.examples.combiner.StatsCombiner;
import org.apache.accumulo.examples.constraints.MaxMutationSize;
import org.apache.accumulo.examples.dirlist.Ingest;
import org.apache.accumulo.examples.dirlist.QueryUtil;
import org.apache.accumulo.examples.helloworld.InsertWithBatchWriter;
import org.apache.accumulo.examples.helloworld.ReadData;
import org.apache.accumulo.examples.isolation.InterferenceTest;
import org.apache.accumulo.examples.mapreduce.RegexExample;
import org.apache.accumulo.examples.mapreduce.RowHash;
import org.apache.accumulo.examples.mapreduce.TableToFile;
import org.apache.accumulo.examples.mapreduce.TeraSortIngest;
import org.apache.accumulo.examples.mapreduce.WordCount;
import org.apache.accumulo.examples.mapreduce.bulk.BulkIngestExample;
import org.apache.accumulo.examples.mapreduce.bulk.GenerateTestData;
import org.apache.accumulo.examples.mapreduce.bulk.SetupTable;
import org.apache.accumulo.examples.mapreduce.bulk.VerifyIngest;
import org.apache.accumulo.examples.shard.ContinuousQuery;
import org.apache.accumulo.examples.shard.Index;
import org.apache.accumulo.examples.shard.Query;
import org.apache.accumulo.examples.shard.Reverse;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.accumulo.minicluster.MemoryUnit;
import org.apache.accumulo.minicluster.impl.MiniAccumuloClusterImpl;
import org.apache.accumulo.minicluster.impl.MiniAccumuloClusterImpl.LogWriter;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.start.Main;
import org.apache.accumulo.test.TestIngest;
import org.apache.accumulo.tracer.TraceServer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.Tool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterators;

public class ExamplesIT extends AccumuloClusterHarness {
  private static final Logger log = LoggerFactory.getLogger(ExamplesIT.class);
  private static final BatchWriterOpts bwOpts = new BatchWriterOpts();
  private static final BatchWriterConfig bwc = new BatchWriterConfig();
  private static final String visibility = "A|B";
  private static final String auths = "A,B";

  Connector c;
  BatchWriter bw;
  IteratorSetting is;
  String dir;
  FileSystem fs;
  Authorizations origAuths;

  @Override
  public void configureMiniCluster(MiniAccumuloConfigImpl cfg, Configuration hadoopConf) {
    // 128MB * 3
    cfg.setDefaultMemory(cfg.getDefaultMemory() * 3, MemoryUnit.BYTE);
    cfg.setProperty(Property.TSERV_NATIVEMAP_ENABLED, "false");
  }

  @Before
  public void getClusterInfo() throws Exception {
    c = getConnector();
    String user = getAdminPrincipal();
    String instance = c.getInstance().getInstanceName();
    String keepers = c.getInstance().getZooKeepers();
    AuthenticationToken token = getAdminToken();
    if (token instanceof PasswordToken) {
      String passwd = new String(((PasswordToken) getAdminToken()).getPassword(), UTF_8);
      writeConnectionFile(getConnectionFile(), instance, keepers, user, passwd);
    } else {
      Assert.fail("Unknown token type: " + token);
    }
    fs = getCluster().getFileSystem();
    dir = new Path(cluster.getTemporaryPath(), getClass().getName()).toString();

    origAuths = c.securityOperations().getUserAuthorizations(user);
    c.securityOperations().changeUserAuthorizations(user, new Authorizations(auths.split(",")));
  }

  @After
  public void resetAuths() throws Exception {
    if (null != origAuths) {
      getConnector().securityOperations().changeUserAuthorizations(getAdminPrincipal(), origAuths);
    }
  }

  public static void writeConnectionFile(String file, String instance, String keepers, String user, String password) throws IOException {
    try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(file))) {
      writer.write("instance.zookeeper.host=" + keepers + "\n");
      writer.write("instance.name=" + instance + "\n");
      writer.write("accumulo.examples.principal=" + user + "\n");
      writer.write("accumulo.examples.password=" + password + "\n");
    }
  }

  private String getConnectionFile() {
    return System.getProperty("user.dir") + "/target/examples.conf";
  }

  @Override
  public int defaultTimeoutSeconds() {
    return 6 * 60;
  }

  @Test
  public void testTrace() throws Exception {
    Process trace = null;
    if (ClusterType.MINI == getClusterType()) {
      MiniAccumuloClusterImpl impl = (MiniAccumuloClusterImpl) cluster;
      trace = impl.exec(TraceServer.class);
      while (!c.tableOperations().exists("trace"))
        sleepUninterruptibly(500, TimeUnit.MILLISECONDS);
    }
    String[] args = new String[] {"-c", getConnectionFile(), "--createtable", "--deletetable", "--create"};
    Entry<Integer,String> pair = cluster.getClusterControl().execWithStdout(TracingExample.class, args);
    Assert.assertEquals("Expected return code of zero. STDOUT=" + pair.getValue(), 0, pair.getKey().intValue());
    String result = pair.getValue();
    Pattern pattern = Pattern.compile("TraceID: ([0-9a-f]+)");
    Matcher matcher = pattern.matcher(result);
    int count = 0;
    while (matcher.find()) {
      args = new String[] {"-c", getConnectionFile(), "--traceid", matcher.group(1)};
      pair = cluster.getClusterControl().execWithStdout(TraceDumpExample.class, args);
      count++;
    }
    assertTrue(count > 0);
    assertTrue("Output did not contain myApp@myHost", pair.getValue().contains("myApp@myHost"));
    if (ClusterType.MINI == getClusterType() && null != trace) {
      trace.destroy();
    }
  }

  @Test
  public void testClasspath() throws Exception {
    Entry<Integer,String> entry = getCluster().getClusterControl().execWithStdout(Main.class, new String[] {"classpath"});
    assertEquals(0, entry.getKey().intValue());
    String result = entry.getValue();
    int level1 = result.indexOf("Level 1");
    int level2 = result.indexOf("Level 2");
    int level3 = result.indexOf("Level 3");
    int level4 = result.indexOf("Level 4");
    assertTrue("Level 1 classloader not present.", level1 >= 0);
    assertTrue("Level 2 classloader not present.", level2 > 0);
    assertTrue("Level 3 classloader not present.", level3 > 0);
    assertTrue("Level 4 classloader not present.", level4 > 0);
    assertTrue(level1 < level2);
    assertTrue(level2 < level3);
    assertTrue(level3 < level4);
  }

  @Test
  public void testDirList() throws Exception {
    String[] names = getUniqueNames(3);
    String dirTable = names[0], indexTable = names[1], dataTable = names[2];
    String[] args;
    String dirListDirectory;
    switch (getClusterType()) {
      case MINI:
        dirListDirectory = ((MiniAccumuloClusterImpl) getCluster()).getConfig().getDir().getAbsolutePath();
        break;
      case STANDALONE:
        dirListDirectory = ((StandaloneAccumuloCluster) getCluster()).getAccumuloHome();
        break;
      default:
        throw new RuntimeException("Unknown cluster type");
    }
    assumeTrue(new File(dirListDirectory).exists());
    // Index a directory listing on /tmp. If this is running against a standalone cluster, we can't guarantee Accumulo source will be there.
    args = new String[] {"-c", getConnectionFile(), "--dirTable", dirTable, "--indexTable", indexTable, "--dataTable", dataTable, "--vis", visibility,
        "--chunkSize", Integer.toString(10000), dirListDirectory};

    Entry<Integer,String> entry = getClusterControl().execWithStdout(Ingest.class, args);
    assertEquals("Got non-zero return code. Stdout=" + entry.getValue(), 0, entry.getKey().intValue());

    String expectedFile;
    switch (getClusterType()) {
      case MINI:
        // Should be present in a minicluster dir
        expectedFile = "accumulo-site.xml";
        break;
      case STANDALONE:
        // Should be in place on standalone installs (not having to follow symlinks)
        expectedFile = "LICENSE";
        break;
      default:
        throw new RuntimeException("Unknown cluster type");
    }

    args = new String[] {"-c", getConnectionFile(), "-t", indexTable, "--auths", auths, "--search", "--path", expectedFile};
    entry = getClusterControl().execWithStdout(QueryUtil.class, args);
    if (ClusterType.MINI == getClusterType()) {
      MiniAccumuloClusterImpl impl = (MiniAccumuloClusterImpl) cluster;
      for (LogWriter writer : impl.getLogWriters()) {
        writer.flush();
      }
    }

    log.info("result " + entry.getValue());
    assertEquals(0, entry.getKey().intValue());
    assertTrue(entry.getValue().contains(expectedFile));
  }

  @Test
  public void testAgeoffFilter() throws Exception {
    String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    is = new IteratorSetting(10, AgeOffFilter.class);
    AgeOffFilter.setTTL(is, 1000L);
    c.tableOperations().attachIterator(tableName, is);
    sleepUninterruptibly(500, TimeUnit.MILLISECONDS); // let zookeeper updates propagate.
    bw = c.createBatchWriter(tableName, bwc);
    Mutation m = new Mutation("foo");
    m.put("a", "b", "c");
    bw.addMutation(m);
    bw.close();
    sleepUninterruptibly(1, TimeUnit.SECONDS);
    assertEquals(0, Iterators.size(c.createScanner(tableName, Authorizations.EMPTY).iterator()));
  }

  @Test
  public void testStatsCombiner() throws Exception {
    String table = getUniqueNames(1)[0];
    c.tableOperations().create(table);
    is = new IteratorSetting(10, StatsCombiner.class);
    StatsCombiner.setCombineAllColumns(is, true);
    StatsCombiner.setRadix(is, 10);
    assertTrue(is.getOptions().containsKey(StatsCombiner.RADIX_OPTION));

    c.tableOperations().attachIterator(table, is);
    bw = c.createBatchWriter(table, bwc);
    // Write two mutations otherwise the NativeMap would dedupe them into a single update
    Mutation m = new Mutation("foo");
    m.put("a", "b", "1");
    bw.addMutation(m);
    m = new Mutation("foo");
    m.put("a", "b", "3");
    bw.addMutation(m);
    bw.flush();

    Iterator<Entry<Key,Value>> iter = c.createScanner(table, Authorizations.EMPTY).iterator();
    assertTrue("Iterator had no results", iter.hasNext());
    Entry<Key,Value> e = iter.next();
    assertEquals("Results ", "1,3,4,2", e.getValue().toString());
    assertFalse("Iterator had additional results", iter.hasNext());

    m = new Mutation("foo");
    m.put("a", "b", "0,20,20,2");
    bw.addMutation(m);
    bw.close();

    iter = c.createScanner(table, Authorizations.EMPTY).iterator();
    assertTrue("Iterator had no results", iter.hasNext());
    e = iter.next();
    assertEquals("Results ", "0,20,24,4", e.getValue().toString());
    assertFalse("Iterator had additional results", iter.hasNext());
  }

  @Test
  public void testBloomFilters() throws Exception {
    String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    c.tableOperations().setProperty(tableName, Property.TABLE_BLOOM_ENABLED.getKey(), "true");
    String[] args = new String[] {"--seed", "7", "-c", getConnectionFile(), "--num", "100000", "--min", "0", "--max", "1000000000", "--size", "50",
        "--batchMemory", "2M", "--batchLatency", "60", "--batchThreads", "3", "-t", tableName};

    goodExec(RandomBatchWriter.class, args);
    c.tableOperations().flush(tableName, null, null, true);
    long diff = 0, diff2 = 0;
    // try the speed test a couple times in case the system is loaded with other tests
    for (int i = 0; i < 2; i++) {
      long now = System.currentTimeMillis();
      args = new String[] {"--seed", "7", "-c", getConnectionFile(), "--num", "10000", "--min", "0", "--max", "1000000000", "--size", "50", "--scanThreads",
          "4", "-t", tableName};
      goodExec(RandomBatchScanner.class, args);
      diff = System.currentTimeMillis() - now;
      now = System.currentTimeMillis();
      args = new String[] {"--seed", "8", "-c", getConnectionFile(), "--num", "10000", "--min", "0", "--max", "1000000000", "--size", "50", "--scanThreads",
          "4", "-t", tableName};
      int retCode = getClusterControl().exec(RandomBatchScanner.class, args);
      assertEquals(1, retCode);
      diff2 = System.currentTimeMillis() - now;
      if (diff2 < diff)
        break;
    }
    assertTrue(diff2 < diff);
  }

  @Test
  public void testShardedIndex() throws Exception {
    File src = new File(System.getProperty("user.dir") + "/src");
    assumeTrue(src.exists());
    String[] names = getUniqueNames(3);
    final String shard = names[0], index = names[1];
    c.tableOperations().create(shard);
    c.tableOperations().create(index);
    bw = c.createBatchWriter(shard, bwc);
    Index.index(30, src, "\\W+", bw);
    bw.close();
    BatchScanner bs = c.createBatchScanner(shard, Authorizations.EMPTY, 4);
    List<String> found = Query.query(bs, Arrays.asList("foo", "bar"), null);
    bs.close();
    // should find ourselves
    boolean thisFile = false;
    for (String file : found) {
      if (file.endsWith("/ExamplesIT.java"))
        thisFile = true;
    }
    assertTrue(thisFile);

    String[] args = new String[] {"-c", getConnectionFile(), "--shardTable", shard, "--doc2Term", index};

    // create a reverse index
    goodExec(Reverse.class, args);
    args = new String[] {"-c", getConnectionFile(), "--shardTable", shard, "--doc2Term", index, "--terms", "5", "--count", "1000"};
    // run some queries
    goodExec(ContinuousQuery.class, args);
  }

  @Test
  public void testMaxMutationConstraint() throws Exception {
    String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    c.tableOperations().addConstraint(tableName, MaxMutationSize.class.getName());
    TestIngest.Opts opts = new TestIngest.Opts();
    opts.rows = 1;
    opts.cols = 1000;
    opts.setTableName(tableName);
    opts.setPrincipal(getAdminPrincipal());
    try {
      TestIngest.ingest(c, opts, bwOpts);
    } catch (MutationsRejectedException ex) {
      assertEquals(1, ex.getConstraintViolationSummaries().size());
    }
  }

  @Test
  public void testBulkIngest() throws Exception {
    // TODO Figure out a way to run M/R with Kerberos
    assumeTrue(getAdminToken() instanceof PasswordToken);
    String tableName = getUniqueNames(1)[0];
    FileSystem fs = getFileSystem();
    Path p = new Path(dir, "tmp");
    if (fs.exists(p)) {
      fs.delete(p, true);
    }
    goodExec(GenerateTestData.class, "--start-row", "0", "--count", "10000", "--output", dir + "/tmp/input/data");

    List<String> commonArgs = new ArrayList<>(Arrays.asList(new String[] {"-c", getConnectionFile(), "--table", tableName}));

    List<String> args = new ArrayList<>(commonArgs);
    goodExec(SetupTable.class, args.toArray(new String[0]));

    args = new ArrayList<>(commonArgs);
    args.addAll(Arrays.asList(new String[] {"--inputDir", dir + "/tmp/input", "--workDir", dir + "/tmp"}));
    goodExec(BulkIngestExample.class, args.toArray(new String[0]));

    args = new ArrayList<>(commonArgs);
    args.addAll(Arrays.asList(new String[] {"--start-row", "0", "--count", "10000"}));
    goodExec(VerifyIngest.class, args.toArray(new String[0]));
  }

  @Test
  public void testTeraSortAndRead() throws Exception {
    // TODO Figure out a way to run M/R with Kerberos
    assumeTrue(getAdminToken() instanceof PasswordToken);
    String tableName = getUniqueNames(1)[0];
    String[] args = new String[] {"--count", (1000 * 1000) + "", "-nk", "10", "-xk", "10", "-nv", "10", "-xv", "10", "-t", tableName, "-c", getConnectionFile(),
        "--splits", "4"};
    goodExec(TeraSortIngest.class, args);
    Path output = new Path(dir, "tmp/nines");
    if (fs.exists(output)) {
      fs.delete(output, true);
    }
    args = new String[] {"-c", getConnectionFile(), "-t", tableName, "--rowRegex", ".*999.*", "--output", output.toString()};
    goodExec(RegexExample.class, args);
    args = new String[] {"-c", getConnectionFile(), "-t", tableName, "--column", "c:"};
    goodExec(RowHash.class, args);
    output = new Path(dir, "tmp/tableFile");
    if (fs.exists(output)) {
      fs.delete(output, true);
    }
    args = new String[] {"-c", getConnectionFile(), "-t", tableName, "--output", output.toString()};
    goodExec(TableToFile.class, args);
  }

  @Test
  public void testWordCount() throws Exception {
    // TODO Figure out a way to run M/R with Kerberos
    assumeTrue(getAdminToken() instanceof PasswordToken);
    String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    is = new IteratorSetting(10, SummingCombiner.class);
    SummingCombiner.setColumns(is, Collections.singletonList(new IteratorSetting.Column(new Text("count"))));
    SummingCombiner.setEncodingType(is, SummingCombiner.Type.STRING);
    c.tableOperations().attachIterator(tableName, is);
    Path readme = new Path(new Path(System.getProperty("user.dir")).getParent(), "README.md");
    if (!new File(readme.toString()).exists()) {
      log.info("Not running test: README.md does not exist)");
      return;
    }
    fs.copyFromLocalFile(readme, new Path(dir + "/tmp/wc/README.md"));
    String[] args;
    args = new String[] {"-c", getConnectionFile(), "--input", dir + "/tmp/wc", "-t", tableName};
    goodExec(WordCount.class, args);
  }

  @Test
  public void testInsertWithBatchWriterAndReadData() throws Exception {
    String tableName = getUniqueNames(1)[0];
    String[] args;
    args = new String[] {"-c", getConnectionFile(), "-t", tableName};
    goodExec(InsertWithBatchWriter.class, args);
    goodExec(ReadData.class, args);
  }

  @Test
  public void testIsolatedScansWithInterference() throws Exception {
    String[] args;
    args = new String[] {"-c", getConnectionFile(), "-t", getUniqueNames(1)[0], "--iterations", "100000", "--isolated"};
    goodExec(InterferenceTest.class, args);
  }

  @Test
  public void testScansWithInterference() throws Exception {
    String[] args;
    args = new String[] {"-c", getConnectionFile(), "-t", getUniqueNames(1)[0], "--iterations", "100000"};
    goodExec(InterferenceTest.class, args);
  }

  @Test
  public void testRowOperations() throws Exception {
    String[] args;
    args = new String[] {"-c", getConnectionFile()};
    goodExec(RowOperations.class, args);
  }

  @Test
  public void testBatchWriter() throws Exception {
    String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    String[] args;
    args = new String[] {"-c", getConnectionFile(), "-t", tableName, "--start", "0", "--num", "100000", "--size", "50", "--batchMemory", "10000000",
        "--batchLatency", "1000", "--batchThreads", "4", "--vis", visibility};
    goodExec(SequentialBatchWriter.class, args);

  }

  @Test
  public void testReadWriteAndDelete() throws Exception {
    String tableName = getUniqueNames(1)[0];
    String[] args;
    args = new String[] {"-c", getConnectionFile(), "--auths", auths, "--table", tableName, "--createtable", "--create"};
    goodExec(ReadWriteExample.class, args);
    args = new String[] {"-c", getConnectionFile(), "--auths", auths, "--table", tableName, "--delete"};
    goodExec(ReadWriteExample.class, args);

  }

  @Test
  public void testRandomBatchesAndFlush() throws Exception {
    String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    String[] args = new String[] {"-c", getConnectionFile(), "--table", tableName, "--num", "100000", "--min", "0", "--max", "100000", "--size", "100",
        "--batchMemory", "1000000", "--batchLatency", "1000", "--batchThreads", "4", "--vis", visibility};
    goodExec(RandomBatchWriter.class, args);

    args = new String[] {"-c", getConnectionFile(), "--table", tableName, "--num", "10000", "--min", "0", "--max", "100000", "--size", "100", "--scanThreads",
        "4", "--auths", auths};
    goodExec(RandomBatchScanner.class, args);

    args = new String[] {"-c", getConnectionFile(), "--table", tableName};
    goodExec(Flush.class, args);
  }

  private void goodExec(Class<?> theClass, String... args) throws InterruptedException, IOException {
    Entry<Integer,String> pair;
    if (Tool.class.isAssignableFrom(theClass) && ClusterType.STANDALONE == getClusterType()) {
      StandaloneClusterControl control = (StandaloneClusterControl) getClusterControl();
      pair = control.execMapreduceWithStdout(theClass, args);
    } else {
      // We're already slurping stdout into memory (not redirecting to file). Might as well add it to error message.
      pair = getClusterControl().execWithStdout(theClass, args);
    }
    Assert.assertEquals("stdout=" + pair.getValue(), 0, pair.getKey().intValue());
  }
}
