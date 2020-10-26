/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.accumulo.test;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.accumulo.core.client.*;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.CompactionConfig;
import org.apache.accumulo.core.client.admin.NewTableConfiguration;
import org.apache.accumulo.core.client.admin.PluginConfig;
import org.apache.accumulo.core.client.admin.compaction.CompactableFile;
import org.apache.accumulo.core.client.admin.compaction.CompactionSelector;
import org.apache.accumulo.core.client.admin.compaction.CompressionConfigurer;
import org.apache.accumulo.core.client.admin.compaction.TooManyDeletesSelector;
import org.apache.accumulo.core.client.summary.SummarizerConfiguration;
import org.apache.accumulo.core.client.summary.Summary;
import org.apache.accumulo.core.client.summary.summarizers.DeletesSummarizer;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.RegExFilter;
import org.apache.accumulo.core.metadata.StoredTabletFile;
import org.apache.accumulo.core.metadata.schema.TabletMetadata.ColumnType;
import org.apache.accumulo.core.metadata.schema.TabletsMetadata;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.spi.compaction.CompactionExecutorId;
import org.apache.accumulo.core.spi.compaction.CompactionKind;
import org.apache.accumulo.core.spi.compaction.CompactionPlan;
import org.apache.accumulo.core.spi.compaction.CompactionPlanner;
import org.apache.accumulo.harness.SharedMiniClusterBase;
import org.apache.accumulo.test.functional.SummaryIT;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Text;
import org.bouncycastle.util.Arrays;
import org.junit.*;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class CompactionIT extends SharedMiniClusterBase {
  public String fooString = "";
  static PrintWriter writer = null;

  public static final Logger log = LoggerFactory.getLogger(CompactionIT.class);

  @SuppressFBWarnings(value = "PREDICTABLE_RANDOM",
      justification = "predictable random is okay for testing")
  public static class TestPlanner implements CompactionPlanner {

    private int filesPerCompaction;
    private List<CompactionExecutorId> executorIds;
    private EnumSet<CompactionKind> kindsToProcess = EnumSet.noneOf(CompactionKind.class);
    private Random rand = new Random();

    @Override
    public void init(InitParameters params) {
      var executors = Integer.parseInt(params.getOptions().get("executors"));
      this.filesPerCompaction = Integer.parseInt(params.getOptions().get("filesPerCompaction"));
      this.executorIds = new ArrayList<>();
      for (String kind : params.getOptions().get("process").split(",")) {
        kindsToProcess.add(CompactionKind.valueOf(kind.toUpperCase()));
      }

      for (int i = 0; i < executors; i++) {
        var ceid = params.getExecutorManager().createExecutor("e" + i, 2);
        executorIds.add(ceid);
      }

    }

    static String getFirstChar(CompactableFile cf) {
      return cf.getFileName().substring(0, 1);
    }

    @Override
    public CompactionPlan makePlan(PlanningParameters params) {

      if (Boolean.parseBoolean(params.getExecutionHints().getOrDefault("compact_all", "false"))) {
        return params.createPlanBuilder()
            .addJob(1, executorIds.get(rand.nextInt(executorIds.size())), params.getCandidates())
            .build();
      }

      if (kindsToProcess.contains(params.getKind())) {
        var planBuilder = params.createPlanBuilder();

        // Group files by first char, like F for flush files or C for compaction produced files.
        // This prevents F and C files from compacting together, which makes it easy to reason about
        // the number of expected files produced by compactions from known number of F files.
        params.getCandidates().stream().collect(Collectors.groupingBy(TestPlanner::getFirstChar))
            .values().forEach(files -> {
              for (int i = filesPerCompaction; i <= files.size(); i += filesPerCompaction) {
                planBuilder.addJob(1, executorIds.get(rand.nextInt(executorIds.size())),
                    files.subList(i - filesPerCompaction, i));
              }
            });

        return planBuilder.build();
      } else {
        return params.createPlanBuilder().build();
      }
    }
  }

  @BeforeClass
  public static void setup() throws Exception {
    SharedMiniClusterBase.startMiniClusterWithConfig((miniCfg, coreSite) -> {
      Map<String,String> siteCfg = new HashMap<>();

      var csp = Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey();
      siteCfg.put(csp + "cs1.planner", TestPlanner.class.getName());
      siteCfg.put(csp + "cs1.planner.opts.executors", "3");
      siteCfg.put(csp + "cs1.planner.opts.filesPerCompaction", "5");
      siteCfg.put(csp + "cs1.planner.opts.process", "SYSTEM");

      siteCfg.put(csp + "cs2.planner", TestPlanner.class.getName());
      siteCfg.put(csp + "cs2.planner.opts.executors", "2");
      siteCfg.put(csp + "cs2.planner.opts.filesPerCompaction", "7");
      siteCfg.put(csp + "cs2.planner.opts.process", "SYSTEM");

      siteCfg.put(csp + "cs3.planner", TestPlanner.class.getName());
      siteCfg.put(csp + "cs3.planner.opts.executors", "1");
      siteCfg.put(csp + "cs3.planner.opts.filesPerCompaction", "3");
      siteCfg.put(csp + "cs3.planner.opts.process", "USER");

      siteCfg.put(csp + "cs4.planner", TestPlanner.class.getName());
      siteCfg.put(csp + "cs4.planner.opts.executors", "2");
      siteCfg.put(csp + "cs4.planner.opts.filesPerCompaction", "11");
      siteCfg.put(csp + "cs4.planner.opts.process", "USER");

      // this is meant to be dynamically reconfigured
      siteCfg.put(csp + "recfg.planner", TestPlanner.class.getName());
      siteCfg.put(csp + "recfg.planner.opts.executors", "2");
      siteCfg.put(csp + "recfg.planner.opts.filesPerCompaction", "11");
      siteCfg.put(csp + "recfg.planner.opts.process", "SYSTEM");

      miniCfg.setSiteConfig(siteCfg);
    });
  }

  @AfterClass
  public static void teardown() {
    SharedMiniClusterBase.stopMiniCluster();
  }

  @After
  public void cleanup() {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().list().stream()
          .filter(tableName -> !tableName.startsWith("accumulo.")).forEach(tableName -> {
            try {
              client.tableOperations().delete(tableName);
            } catch (AccumuloException | AccumuloSecurityException | TableNotFoundException e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  @Test
  public void testReconfigureCompactionService() throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      createTable(client, "rctt", "recfg");

      addFiles(client, "rctt", 22);

      while (getFiles(client, "rctt").size() > 2) {
        Thread.sleep(100);
      }

      assertEquals(2, getFiles(client, "rctt").size());

      client.instanceOperations().setProperty(Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey()
          + "recfg.planner.opts.filesPerCompaction", "5");
      client.instanceOperations().setProperty(
          Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey() + "recfg.planner.opts.executors", "1");

      addFiles(client, "rctt", 10);

      while (getFiles(client, "rctt").size() > 4) {
        Thread.sleep(100);
      }

      assertEquals(4, getFiles(client, "rctt").size());
    }
  }

  @Test
  public void testAddCompactionService() throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.instanceOperations().setProperty(Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey()
          + "newcs.planner.opts.filesPerCompaction", "7");
      client.instanceOperations().setProperty(
          Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey() + "newcs.planner.opts.process",
          "SYSTEM");
      client.instanceOperations().setProperty(
          Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey() + "newcs.planner.opts.executors", "3");
      client.instanceOperations().setProperty(
          Property.TSERV_COMPACTION_SERVICE_PREFIX.getKey() + "newcs.planner",
          TestPlanner.class.getName());

      createTable(client, "acst", "newcs");

      addFiles(client, "acst", 42);

      while (getFiles(client, "acst").size() > 6) {
        Thread.sleep(100);
      }

      assertEquals(6, getFiles(client, "acst").size());
    }
  }

  /**
   * Test ensures that system compactions are dispatched to a configured compaction service. The
   * compaction services produce a very specific number of files, so the test indirectly checks
   * dispatching worked by observing how many files a tablet ends up with.
   */
  @Test
  public void testDispatchSystem() throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      createTable(client, "dst1", "cs1");
      createTable(client, "dst2", "cs2");

      addFiles(client, "dst1", 14);
      addFiles(client, "dst2", 13);

      assertTrue(getFiles(client, "dst1").size() >= 6);
      assertTrue(getFiles(client, "dst2").size() >= 7);

      addFiles(client, "dst1", 1);
      addFiles(client, "dst2", 1);

      while (getFiles(client, "dst1").size() > 3 || getFiles(client, "dst2").size() > 2) {
        Thread.sleep(100);
      }

      assertEquals(3, getFiles(client, "dst1").size());
      assertEquals(2, getFiles(client, "dst2").size());
    }
  }

  @Test
  public void testDispatchUser() throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      createTable(client, "dut1", "cs3");
      createTable(client, "dut2", "cs3", "special", "cs4");

      addFiles(client, "dut1", 6);
      addFiles(client, "dut2", 33);

      assertEquals(6, getFiles(client, "dut1").size());
      assertEquals(33, getFiles(client, "dut2").size());

      client.tableOperations().compact("dut1", new CompactionConfig().setWait(false));

      // The hint should cause the compaction to dispatch to service cs4 which will produce a
      // different number of files.
      client.tableOperations().compact("dut2", new CompactionConfig().setWait(false)
          .setExecutionHints(Map.of("compaction_type", "special")));

      while (getFiles(client, "dut1").size() > 2 || getFiles(client, "dut2").size() > 3) {
        Thread.sleep(100);
      }

      assertEquals(2, getFiles(client, "dut1").size());
      assertEquals(3, getFiles(client, "dut2").size());

      // The way the compaction services were configured, they would never converge to one file for
      // the user compactions. However Accumulo will keep asking the planner for a plan until a user
      // compaction converges to one file. So cancel the compactions.
      client.tableOperations().cancelCompaction("dut1");
      client.tableOperations().cancelCompaction("dut2");

      assertEquals(2, getFiles(client, "dut1").size());
      assertEquals(3, getFiles(client, "dut2").size());

      client.tableOperations().compact("dut1",
          new CompactionConfig().setWait(true).setExecutionHints(Map.of("compact_all", "true")));
      client.tableOperations().compact("dut2",
          new CompactionConfig().setWait(true).setExecutionHints(Map.of("compact_all", "true")));

      assertEquals(1, getFiles(client, "dut1").size());
      assertEquals(1, getFiles(client, "dut2").size());
    }

  }

  @Test
  public void testTooManyDeletes() throws Exception {
    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      Map<String,
          String> props = Map.of(Property.TABLE_COMPACTION_SELECTOR.getKey(),
              TooManyDeletesSelector.class.getName(),
              Property.TABLE_COMPACTION_SELECTOR_OPTS.getKey() + "threshold", ".4");
      var deleteSummarizerCfg =
          SummarizerConfiguration.builder(DeletesSummarizer.class.getName()).build();
      client.tableOperations().create("tmd_selector", new NewTableConfiguration()
          .setProperties(props).enableSummarization(deleteSummarizerCfg));
      client.tableOperations().create("tmd_control1",
          new NewTableConfiguration().enableSummarization(deleteSummarizerCfg));
      client.tableOperations().create("tmd_control2",
          new NewTableConfiguration().enableSummarization(deleteSummarizerCfg));
      client.tableOperations().create("tmd_control3",
          new NewTableConfiguration().enableSummarization(deleteSummarizerCfg));

      addFile(client, "tmd_selector", 1, 1000, false);
      addFile(client, "tmd_selector", 1, 1000, true);

      addFile(client, "tmd_control1", 1, 1000, false);
      addFile(client, "tmd_control1", 1, 1000, true);

      addFile(client, "tmd_control2", 1, 1000, false);
      addFile(client, "tmd_control2", 1000, 2000, false);

      addFile(client, "tmd_control3", 1, 2000, false);
      addFile(client, "tmd_control3", 1, 1000, true);

      assertEquals(2, getFiles(client, "tmd_control1").size());
      assertEquals(2, getFiles(client, "tmd_control2").size());
      assertEquals(2, getFiles(client, "tmd_control3").size());

      while (getFiles(client, "tmd_selector").size() != 0) {
        Thread.sleep(100);
      }

      assertEquals(2, getFiles(client, "tmd_control1").size());
      assertEquals(2, getFiles(client, "tmd_control2").size());
      assertEquals(2, getFiles(client, "tmd_control3").size());

      var cc1 = new CompactionConfig()
          .setSelector(
              new PluginConfig(TooManyDeletesSelector.class.getName(), Map.of("threshold", ".99")))
          .setWait(true);

      client.tableOperations().compact("tmd_control1", cc1);
      client.tableOperations().compact("tmd_control2", cc1);
      client.tableOperations().compact("tmd_control3", cc1);

      assertEquals(0, getFiles(client, "tmd_control1").size());
      assertEquals(2, getFiles(client, "tmd_control2").size());
      assertEquals(2, getFiles(client, "tmd_control3").size());

      var cc2 = new CompactionConfig()
          .setSelector(
              new PluginConfig(TooManyDeletesSelector.class.getName(), Map.of("threshold", ".40")))
          .setWait(true);

      client.tableOperations().compact("tmd_control1", cc2);
      client.tableOperations().compact("tmd_control2", cc2);
      client.tableOperations().compact("tmd_control3", cc2);

      assertEquals(0, getFiles(client, "tmd_control1").size());
      assertEquals(2, getFiles(client, "tmd_control2").size());
      assertEquals(1, getFiles(client, "tmd_control3").size());

      client.tableOperations().compact("tmd_control2", new CompactionConfig().setWait(true));

      assertEquals(1, getFiles(client, "tmd_control2").size());

    }
  }

  @Test
  public void testIteratorsWithRange() throws Exception {

    String tableName = "tiwr";

    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().create(tableName);
      SortedSet<Text> splits = new TreeSet<>();
      splits.add(new Text("f"));
      splits.add(new Text("m"));
      splits.add(new Text("r"));
      splits.add(new Text("t"));
      client.tableOperations().addSplits(tableName, splits);

      Map<String,String> expected = new TreeMap<>();

      try (var writer = client.createBatchWriter(tableName)) {
        int v = 0;
        for (String row : List.of("a", "h", "o", "s", "x")) {
          Mutation m = new Mutation(row);
          for (int q = 0; q < 10; q++) {
            String qual = String.format("%03d", q);
            String val = "v" + v++;
            m.at().family("f").qualifier(qual).put(val);
            expected.put(row + ":f:" + qual, val);
          }
          writer.addMutation(m);
        }
      }

      IteratorSetting iterSetting = new IteratorSetting(20, "rf", RegExFilter.class.getName());
      RegExFilter.setRegexs(iterSetting, null, null, "004|007", null, false);
      RegExFilter.setNegate(iterSetting, true);
      client.tableOperations().compact(tableName,
          new CompactionConfig().setStartRow(new Text("b")).setEndRow(new Text("m"))
              .setIterators(List.of(iterSetting)).setWait(true).setFlush(true));

      for (String row : List.of("a", "h")) {
        assertTrue(expected.remove(row + ":f:004") != null);
        assertTrue(expected.remove(row + ":f:007") != null);
      }

      Map<String,String> actual = scanTable(client, tableName);
      assertEquals(expected, actual);

      iterSetting = new IteratorSetting(20, "rf", RegExFilter.class.getName());
      RegExFilter.setRegexs(iterSetting, null, null, "002|005|009", null, false);
      RegExFilter.setNegate(iterSetting, true);
      client.tableOperations().compact(tableName, new CompactionConfig().setStartRow(new Text("m"))
          .setEndRow(new Text("u")).setIterators(List.of(iterSetting)).setWait(true));

      for (String row : List.of("o", "s", "x")) {
        assertTrue(expected.remove(row + ":f:002") != null);
        assertTrue(expected.remove(row + ":f:005") != null);
        assertTrue(expected.remove(row + ":f:009") != null);
      }

      actual = scanTable(client, tableName);
      assertEquals(expected, actual);

      iterSetting = new IteratorSetting(20, "rf", RegExFilter.class.getName());
      RegExFilter.setRegexs(iterSetting, null, null, "00[18]", null, false);
      RegExFilter.setNegate(iterSetting, true);
      client.tableOperations().compact(tableName,
          new CompactionConfig().setIterators(List.of(iterSetting)).setWait(true));

      for (String row : List.of("a", "h", "o", "s", "x")) {
        assertTrue(expected.remove(row + ":f:001") != null);
        assertTrue(expected.remove(row + ":f:008") != null);
      }

      actual = scanTable(client, tableName);
      assertEquals(expected, actual);

      // add all data back and force a compaction to ensure iters do not run again
      try (var writer = client.createBatchWriter(tableName)) {
        int v = 1000;
        for (String row : List.of("a", "h", "o", "s", "x")) {
          Mutation m = new Mutation(row);
          for (int q = 0; q < 10; q++) {
            String qual = String.format("%03d", q);
            String val = "v" + v++;
            m.at().family("f").qualifier(qual).put(val);
            expected.put(row + ":f:" + qual, val);
          }
          writer.addMutation(m);
        }
      }

      client.tableOperations().compact(tableName,
          new CompactionConfig().setWait(true).setFlush(true));

      actual = scanTable(client, tableName);
      assertEquals(expected, actual);

    }
  }

  @Test
  public void testConfigurer() throws Exception {
    String tableName = "tcc";

    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      var ntc = new NewTableConfiguration()
          .setProperties(Map.of(Property.TABLE_FILE_COMPRESSION_TYPE.getKey(), "none"));
      client.tableOperations().create(tableName, ntc);

      byte[] data = new byte[100000];
      Arrays.fill(data, (byte) 65);

      try (var writer = client.createBatchWriter(tableName)) {
        for (int row = 0; row < 10; row++) {
          Mutation m = new Mutation(row + "");
          m.at().family("big").qualifier("stuff").put(data);
          writer.addMutation(m);
        }
      }

      client.tableOperations().flush(tableName, null, null, true);

      // without compression, expect file to be large
      long sizes = getFileSizes(client, tableName);
      assertTrue("Unexpected files sizes : " + sizes,
          sizes > data.length * 10 && sizes < data.length * 11);

      client.tableOperations().compact(tableName,
          new CompactionConfig().setWait(true)
              .setConfigurer(new PluginConfig(CompressionConfigurer.class.getName(),
                  Map.of(CompressionConfigurer.LARGE_FILE_COMPRESSION_TYPE, "gz",
                      CompressionConfigurer.LARGE_FILE_COMPRESSION_THRESHOLD, data.length + ""))));

      // after compacting with compression, expect small file
      sizes = getFileSizes(client, tableName);
      assertTrue("Unexpected files sizes : " + sizes, sizes < data.length);

      client.tableOperations().compact(tableName, new CompactionConfig().setWait(true));

      // after compacting without compression, expect big files again
      sizes = getFileSizes(client, tableName);
      assertTrue("Unexpected files sizes : " + sizes,
          sizes > data.length * 10 && sizes < data.length * 11);

    }
  }

  private long getFileSizes(AccumuloClient client, String tableName) {
    var tableId = TableId.of(client.tableOperations().tableIdMap().get(tableName));

    try (var tabletsMeta =
        TabletsMetadata.builder().forTable(tableId).fetch(ColumnType.FILES).build(client)) {
      return tabletsMeta.stream().flatMap(tm -> tm.getFiles().stream()).mapToLong(stf -> {
        try {
          return FileSystem.getLocal(new Configuration()).getFileStatus(stf.getPath()).getLen();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      ).sum();
    }
  }

  @Test
  public void testIncorrectSelectorType() throws Exception {
    String tableName = "tist";

    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().create(tableName);
      addFiles(client, tableName, 5);

      var msg = assertThrows(AccumuloException.class, () -> {
        client.tableOperations().compact(tableName, new CompactionConfig()
            .setSelector(new PluginConfig(CompressionConfigurer.class.getName())).setWait(true));
      }).getMessage();

      assertTrue("Unexcpected message : " + msg,
          msg.contains("TabletServer could not load CompactionSelector"));

    }
  }

  @Test
  public void testIncorrectConfigurerType() throws Exception {
    String tableName = "tict";

    try (AccumuloClient client = Accumulo.newClient().from(getClientProps()).build()) {
      client.tableOperations().create(tableName);
      addFiles(client, tableName, 5);

      var msg = assertThrows(AccumuloException.class, () -> {
        client.tableOperations().compact(tableName, new CompactionConfig()
            .setConfigurer(new PluginConfig(TooManyDeletesSelector.class.getName())).setWait(true));
      }).getMessage();

      assertTrue("Unexcpected message : " + msg,
          msg.contains("TabletServer could not load CompactionConfigurer"));
    }
  }

  @Test
  public void testCompactionSelector() throws Exception {
    PluginConfig csc = new PluginConfig(CompactionIT.FooSelector.class.getName());
    CompactionConfig compactConfig = new CompactionConfig().setSelector(csc);
    compactionTest(compactConfig);
  }

  private void write(BatchWriter bw, String row, String family, String qualifier, String value)
      throws MutationsRejectedException {
    Mutation m1 = new Mutation(row);
    m1.put(family, qualifier, value);
    bw.addMutation(m1);
  }

  public void compactionTest(CompactionConfig compactConfig) throws Exception {
    final Random rand = new Random();
    final String table = getUniqueNames(1)[0];

    long fooCount = 1 + (long) rand.nextInt(10), barCount = 1 + (long) rand.nextInt(10);
    int i, k, count = 2;

    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      NewTableConfiguration ntc = new NewTableConfiguration();
      SummarizerConfiguration sc1 =
          SummarizerConfiguration.builder(SummaryIT.FooCounter.class.getName()).build();
      ntc.enableSummarization(sc1);
      c.tableOperations().create(table, ntc);

      for (i = 0; i < barCount; i++) {
        try (BatchWriter bw = c.createBatchWriter(table)) {
          write(bw, "bar" + i, "f" + i, "q" + i, "v" + i);
        }
      }
      for (k = 0; k < fooCount; k++) {
        try (BatchWriter bw = c.createBatchWriter(table)) {
          write(bw, "foo" + k, "f" + k, "q" + k, "v" + k);
        }
      }

      if (fooCount > barCount) {
        count = 1;
        fooCount = 0;
      }

      List<IteratorSetting> iterators =
          Collections.singletonList(new IteratorSetting(100, SummaryIT.FooFilter.class));
      compactConfig = compactConfig.setFlush(true).setIterators(iterators).setWait(true);

      c.tableOperations().compact(table, compactConfig);

      try (Scanner scanner = c.createScanner(table, Authorizations.EMPTY)) {
        Stream<Entry<Key,Value>> stream = StreamSupport.stream(scanner.spliterator(), false);
        Map<String,Long> counts = stream.map(e -> e.getKey().getRowData().toString()) // convert to
            // row
            .map(r -> r.replaceAll("[0-9]+", "")) // strip numbers off row
            .collect(groupingBy(identity(), counting())); // count different row types

        assertEquals(fooCount, (long) counts.getOrDefault("foo", 0L));
        assertEquals(barCount, (long) counts.getOrDefault("bar", 0L));
        assertEquals(count, counts.size());
      }

    }

  }

  static class fooSelectorException extends RuntimeException {
    public fooSelectorException(String message) {
      super(message);
    }
  }

  public static class FailureListener extends RunListener {

    private RunNotifier runNotifier;

    public FailureListener(RunNotifier runNotifier) {
      super();
      this.runNotifier = runNotifier;
    }

    @Override
    public void testFailure(Failure failure) throws Exception {
      super.testFailure(failure);
      this.runNotifier.pleaseStop();
    }
  }

  public static class Retry implements TestRule {
    private int retryCount;

    public Retry(int retryCount) {
      this.retryCount = retryCount;
    }

    public Statement apply(Statement base, Description description) {
      return statement(base, description);
    }

    private Statement statement(final Statement base, final Description description) {
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          Throwable caughtThrowable = null;

          // implement retry logic here
          for (int i = 0; i < retryCount; i++) {
            try {
              base.evaluate();
              return;
            } catch (Throwable t) {
              caughtThrowable = t;
              System.err.println(description.getDisplayName() + ": run " + (i + 1) + " failed");
            }
          }
          System.err.println(
              description.getDisplayName() + ": giving up after " + retryCount + " failures");
          throw caughtThrowable;
        }
      };
    }
  }

  @Rule
  public Retry retry = new Retry(3);

  public static class FooSelector implements CompactionSelector {

    public static final Logger log2 = LoggerFactory.getLogger(FooSelector.class);

    boolean odd = false;

    @Override
    public void init(InitParamaters iparams) {}

    @Override
    public Selection select(SelectionParameters sparams) {
      Collection<Summary> summaries = sparams.getSummaries(sparams.getAvailableFiles(),
          conf -> conf.getClassName().contains("FooCounter"));

      if (summaries.size() == 1) {
        Summary summary = summaries.iterator().next();
        Long foos = summary.getStatistics().getOrDefault("foos", 0L);
        Long bars = summary.getStatistics().getOrDefault("bars", 0L);
        odd = isOdd(foos);

        if (odd) {
          throw new fooSelectorException("Exception Thrown");
        } else if (foos > bars)
          return new Selection(sparams.getAvailableFiles());

      }
      return new Selection(Set.of());

    }

    boolean isOdd(long foos) {
      System.out.println("This is Ty Barnes ");

      if ((foos & 0x1) == 1) {
        return true;
      }
      return false;
    }

  }

  private Map<String,String> scanTable(AccumuloClient client, String tableName)
      throws TableNotFoundException, AccumuloSecurityException, AccumuloException {
    Map<String,String> actual = new TreeMap<>();
    try (var scanner = client.createScanner(tableName)) {
      for (Entry<Key,Value> e : scanner) {
        var k = e.getKey();
        actual.put(
            k.getRowData() + ":" + k.getColumnFamilyData() + ":" + k.getColumnQualifierData(),
            e.getValue().toString());
      }
    }
    return actual;
  }

  private Set<String> getFiles(AccumuloClient client, String name) {
    var tableId = TableId.of(client.tableOperations().tableIdMap().get(name));

    try (var tabletsMeta =
        TabletsMetadata.builder().forTable(tableId).fetch(ColumnType.FILES).build(client)) {
      return tabletsMeta.stream().flatMap(tm -> tm.getFiles().stream())
          .map(StoredTabletFile::getFileName).collect(Collectors.toSet());
    }
  }

  private void addFile(AccumuloClient client, String table, int startRow, int endRow,
      boolean delete) throws Exception {
    try (var writer = client.createBatchWriter(table)) {
      for (int i = startRow; i < endRow; i++) {
        Mutation mut = new Mutation(String.format("%09d", i));
        if (delete)
          mut.putDelete("f1", "q1");
        else
          mut.put("f1", "q1", "v" + i);
        writer.addMutation(mut);

      }
    }
    client.tableOperations().flush(table, null, null, true);
  }

  private void addFiles(AccumuloClient client, String table, int num) throws Exception {
    try (var writer = client.createBatchWriter(table)) {
      for (int i = 0; i < num; i++) {
        Mutation mut = new Mutation("r" + i);
        mut.put("f1", "q1", "v" + i);
        writer.addMutation(mut);
        writer.flush();
        client.tableOperations().flush(table, null, null, true);
      }
    }
  }

  private void createTable(AccumuloClient client, String name, String compactionService)
      throws Exception {
    NewTableConfiguration ntc = new NewTableConfiguration().setProperties(
        Map.of(Property.TABLE_COMPACTION_DISPATCHER_OPTS.getKey() + "service", compactionService));
    client.tableOperations().create(name, ntc);
  }

  private void createTable(AccumuloClient client, String name, String compactionService,
      String userType, String userService) throws Exception {
    var tcdo = Property.TABLE_COMPACTION_DISPATCHER_OPTS.getKey();

    NewTableConfiguration ntc = new NewTableConfiguration().setProperties(
        Map.of(tcdo + "service", compactionService, tcdo + "service.user." + userType, userService,
            Property.TABLE_MAJC_RATIO.getKey(), "100"));

    client.tableOperations().create(name, ntc);
  }
}
