/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.executor.transport;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import io.crate.Constants;
import io.crate.DataType;
import io.crate.PartitionName;
import io.crate.analyze.WhereClause;
import io.crate.executor.Job;
import io.crate.executor.transport.task.elasticsearch.*;
import io.crate.integrationtests.SQLTransportIntegrationTest;
import io.crate.metadata.*;
import io.crate.metadata.doc.DocSchemaInfo;
import io.crate.metadata.sys.SysClusterTableInfo;
import io.crate.metadata.sys.SysNodesTableInfo;
import io.crate.operation.operator.AndOperator;
import io.crate.operation.operator.EqOperator;
import io.crate.operation.operator.OrOperator;
import io.crate.operation.projectors.TopN;
import io.crate.operation.scalar.DateTruncFunction;
import io.crate.planner.Plan;
import io.crate.planner.RowGranularity;
import io.crate.planner.node.dml.ESDeleteByQueryNode;
import io.crate.planner.node.dml.ESDeleteNode;
import io.crate.planner.node.dml.ESIndexNode;
import io.crate.planner.node.dml.ESUpdateNode;
import io.crate.planner.node.dql.*;
import io.crate.planner.projection.Projection;
import io.crate.planner.projection.TopNProjection;
import io.crate.planner.symbol.*;
import io.crate.test.integration.CrateIntegrationTest;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.search.SearchHits;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static java.util.Arrays.asList;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.greaterThan;

@CrateIntegrationTest.ClusterScope(scope = CrateIntegrationTest.Scope.GLOBAL)
public class TransportExecutorTest extends SQLTransportIntegrationTest {

    static {
        ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
    }

    private ClusterService clusterService;
    private ClusterName clusterName;
    private TransportExecutor executor;
    private String copyFilePath = getClass().getResource("/essetup/data/copy").getPath();

    TableIdent table = new TableIdent(null, "characters");
    Reference id_ref = new Reference(new ReferenceInfo(
            new ReferenceIdent(table, "id"), RowGranularity.DOC, DataType.INTEGER));
    Reference name_ref = new Reference(new ReferenceInfo(
            new ReferenceIdent(table, "name"), RowGranularity.DOC, DataType.STRING));
    Reference version_ref = new Reference(new ReferenceInfo(
            new ReferenceIdent(table, "_version"), RowGranularity.DOC, DataType.LONG));

    TableIdent partedTable = new TableIdent(null, "parted");
    Reference parted_id_ref = new Reference(new ReferenceInfo(
            new ReferenceIdent(partedTable, "id"), RowGranularity.DOC, DataType.INTEGER));
    Reference parted_name_ref = new Reference(new ReferenceInfo(
            new ReferenceIdent(partedTable, "name"), RowGranularity.DOC, DataType.STRING));
    Reference parted_date_ref = new Reference(new ReferenceInfo(
            new ReferenceIdent(partedTable, "date"), RowGranularity.DOC, DataType.TIMESTAMP));

    @Before
    public void transportSetUp() {
        clusterService = cluster().getInstance(ClusterService.class);
        clusterName = cluster().getInstance(ClusterName.class);
        executor = cluster().getInstance(TransportExecutor.class);
    }

    private void insertCharacters() {
        execute("create table characters (id int primary key, name string)");
        ensureGreen();
        execute("insert into characters (id, name) values (1, 'Arthur')");
        execute("insert into characters (id, name) values (2, 'Ford')");
        execute("insert into characters (id, name) values (3, 'Trillian')");
        refresh();
    }

    private void createPartitionedTable() {
        execute("create table parted (id int, name string, date timestamp) partitioned by (date)");
        ensureGreen();
        execute("insert into parted (id, name, date) values (?, ?, ?), (?, ?, ?), (?, ?, ?)",
                new Object[]{
                        1, "Trillian", null,
                        2, null, 0L,
                        3, "Ford", 1396388720242L
                });
        ensureGreen();
        refresh();
    }

    @Test
    public void testRemoteCollectTask() throws Exception {
        Map<String, Map<String, Set<Integer>>> locations = new HashMap<>(2);

        for (DiscoveryNode discoveryNode : clusterService.state().nodes()) {
            locations.put(discoveryNode.id(), new HashMap<String, Set<Integer>>());
        }

        Routing routing = new Routing(locations);
        ReferenceInfo load1 = SysNodesTableInfo.INFOS.get(new ColumnIdent("load", "1"));
        Symbol reference = new Reference(load1);

        CollectNode collectNode = new CollectNode("collect", routing);
        collectNode.toCollect(Arrays.<Symbol>asList(reference));
        collectNode.outputTypes(asList(load1.type()));
        collectNode.maxRowGranularity(RowGranularity.NODE);

        Plan plan = new Plan();
        plan.add(collectNode);
        Job job = executor.newJob(plan);

        List<ListenableFuture<Object[][]>> result = executor.execute(job);

        assertThat(result.size(), is(2));
        for (ListenableFuture<Object[][]> nodeResult : result) {
            assertEquals(1, nodeResult.get().length);
            assertThat((Double) nodeResult.get()[0][0], is(greaterThan(0.0)));

        }
    }

    @Test
    public void testMapSideCollectTask() throws Exception {
        ReferenceInfo clusterNameInfo = SysClusterTableInfo.INFOS.get(new ColumnIdent("name"));
        Symbol reference = new Reference(clusterNameInfo);

        CollectNode collectNode = new CollectNode("lcollect", new Routing());
        collectNode.toCollect(asList(reference, new FloatLiteral(2.3f)));
        collectNode.outputTypes(asList(clusterNameInfo.type()));
        collectNode.maxRowGranularity(RowGranularity.CLUSTER);

        Plan plan = new Plan();
        plan.add(collectNode);
        Job job = executor.newJob(plan);

        List<ListenableFuture<Object[][]>> results = executor.execute(job);
        assertThat(results.size(), is(1));
        Object[][] result = results.get(0).get();
        assertThat(result.length, is(1));
        assertThat(result[0].length, is(2));

        assertThat(((BytesRef)result[0][0]).utf8ToString(), is(clusterName.value()));
        assertThat((Float)result[0][1], is(2.3f));
    }

    @Test
    public void testESGetTask() throws Exception {
        insertCharacters();

        ESGetNode node = new ESGetNode("characters", "2", "2");
        node.outputs(ImmutableList.<Symbol>of(id_ref, name_ref));
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(1));
        assertThat((Integer) objects[0][0], is(2));
        assertThat((String) objects[0][1], is("Ford"));
    }

    @Test
    public void testESGetTaskWithDynamicReference() throws Exception {
        insertCharacters();

        ESGetNode node = new ESGetNode("characters", "2", "2");
        node.outputs(ImmutableList.<Symbol>of(id_ref, new DynamicReference(
                new ReferenceIdent(new TableIdent(null, "characters"), "foo"), RowGranularity.DOC)));
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(1));
        assertThat((Integer) objects[0][0], is(2));
        assertNull(objects[0][1]);
    }

    @Test
    public void testESMultiGet() throws Exception {
        insertCharacters();
        ESGetNode node = new ESGetNode("characters", asList("1", "2"), asList("1", "2"));
        node.outputs(ImmutableList.<Symbol>of(id_ref, name_ref));
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(2));
    }

    @Test
    public void testESSearchTask() throws Exception {
        insertCharacters();

        ESSearchNode node = new ESSearchNode(
                new String[]{"characters"},
                Arrays.<Symbol>asList(id_ref, name_ref),
                Arrays.<Reference>asList(name_ref),
                new boolean[]{false},
                null, null, WhereClause.MATCH_ALL,
                null
        );
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        ESSearchTask task = (ESSearchTask) job.tasks().get(0);

        task.start();
        Object[][] rows = task.result().get(0).get();
        assertThat(rows.length, is(3));

        assertThat((Integer) rows[0][0], is(1));
        assertThat((String) rows[0][1], is("Arthur"));

        assertThat((Integer) rows[1][0], is(2));
        assertThat((String) rows[1][1], is("Ford"));

        assertThat((Integer) rows[2][0], is(3));
        assertThat((String) rows[2][1], is("Trillian"));
    }

    @Test
    public void testESSearchTaskWithFilter() throws Exception {
        insertCharacters();

        Function whereClause = new Function(new FunctionInfo(
                new FunctionIdent(EqOperator.NAME, asList(DataType.STRING, DataType.STRING)),
                DataType.BOOLEAN),
                Arrays.<Symbol>asList(name_ref, new StringLiteral("Ford")));

        ESSearchNode node = new ESSearchNode(
                new String[]{"characters"},
                Arrays.<Symbol>asList(id_ref, name_ref),
                Arrays.<Reference>asList(name_ref),
                new boolean[]{false},
                null, null,
                new WhereClause(whereClause),
                null
        );
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        ESSearchTask task = (ESSearchTask) job.tasks().get(0);

        task.start();
        Object[][] rows = task.result().get(0).get();
        assertThat(rows.length, is(1));

        assertThat((Integer) rows[0][0], is(2));
        assertThat((String) rows[0][1], is("Ford"));
    }

    @Test
    public void testESSearchTaskWithFunction() throws Exception {
        execute("create table searchf (id int primary key, date timestamp) with (number_of_replicas=0)");
        ensureGreen();
        execute("insert into searchf (id, date) values (1, '1980-01-01'), (2, '1980-01-02')");
        refresh();

        Reference id_ref = new Reference(new ReferenceInfo(
                new ReferenceIdent(
                        new TableIdent(DocSchemaInfo.NAME, "searchf"),
                        "id"),
                RowGranularity.DOC,
                DataType.INTEGER
        ));
        Reference date_ref = new Reference(new ReferenceInfo(
                new ReferenceIdent(
                        new TableIdent(DocSchemaInfo.NAME, "searchf"),
                        "date"),
                RowGranularity.DOC,
                DataType.TIMESTAMP
        ));
        Function function = new Function(new FunctionInfo(
                new FunctionIdent(DateTruncFunction.NAME, asList(DataType.STRING, DataType.TIMESTAMP)),
                DataType.TIMESTAMP, false
        ), Arrays.<Symbol>asList(new StringLiteral("day"), new InputColumn(1)));
        Function whereClause = new Function(new FunctionInfo(
                new FunctionIdent(EqOperator.NAME, asList(DataType.INTEGER, DataType.INTEGER)),
                DataType.BOOLEAN),
                Arrays.<Symbol>asList(id_ref, new IntegerLiteral(2))
        );

        ESSearchNode node = new ESSearchNode(
                new String[]{"searchf"},
                Arrays.<Symbol>asList(id_ref, date_ref),
                Arrays.asList(id_ref),
                new boolean[]{false},
                null, null,
                new WhereClause(whereClause),
                null
        );
        MergeNode mergeNode = new MergeNode("merge", 1);
        mergeNode.inputTypes(Arrays.asList(DataType.INTEGER, DataType.TIMESTAMP));
        mergeNode.outputTypes(Arrays.asList(DataType.INTEGER, DataType.TIMESTAMP));
        TopNProjection topN = new TopNProjection(2, TopN.NO_OFFSET);
        topN.outputs(Arrays.asList(new InputColumn(0), function));
        mergeNode.projections(Arrays.<Projection>asList(topN));
        Plan plan = new Plan();
        plan.add(node);
        plan.add(mergeNode);
        Job job = executor.newJob(plan);
        assertThat(job.tasks().size(), is(2));

        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();
        assertThat(rows.length, is(1));

        assertThat((Integer) rows[0][0], is(2));
        assertEquals(315619200000L, rows[0][1]);

    }

    @Test
    public void testESSearchTaskPartitioned() throws Exception {
        createPartitionedTable();
        // get partitions
        ImmutableOpenMap<String, List<AliasMetaData>> aliases = client().admin().indices().prepareGetAliases().addAliases("parted").execute().actionGet().getAliases();
        ESSearchNode node = new ESSearchNode(
                aliases.keys().toArray(String.class),
                Arrays.<Symbol>asList(parted_id_ref, parted_name_ref, parted_date_ref),
                Arrays.<Reference>asList(name_ref),
                new boolean[]{false},
                null, null,
                WhereClause.MATCH_ALL,
                Arrays.asList(parted_date_ref.info())
        );
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        ESSearchTask task = (ESSearchTask) job.tasks().get(0);

        task.start();
        Object[][] rows = task.result().get(0).get();
        assertThat(rows.length, is(3));

        assertThat((Integer) rows[0][0], is(3));
        assertThat((String)rows[0][1], is("Ford"));
        assertThat((Long) rows[0][2], is(1396388720242L));

        assertThat((Integer) rows[1][0], is(1));
        assertThat((String) rows[1][1], is("Trillian"));
        assertNull(rows[1][2]);

        assertThat((Integer) rows[2][0], is(2));
        assertNull(rows[2][1]);
        assertThat((Long) rows[2][2], is(0L));
    }

    @Test
    public void testESDeleteByQueryTask() throws Exception {
        insertCharacters();

        Function whereClause = new Function(new FunctionInfo(
                new FunctionIdent(EqOperator.NAME, asList(DataType.STRING, DataType.STRING)),
                DataType.BOOLEAN),
                Arrays.<Symbol>asList(id_ref, new IntegerLiteral(2)));

        ESDeleteByQueryNode node = new ESDeleteByQueryNode(
                new String[]{"characters"},
                new WhereClause(whereClause));
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        ESDeleteByQueryTask task = (ESDeleteByQueryTask) job.tasks().get(0);

        task.start();
        Object[][] rows = task.result().get(0).get();
        assertThat(rows.length, is(1));
        assertThat((Long)rows[0][0], is(-1L));

        // verify deletion
        ESSearchNode searchNode = new ESSearchNode(
                new String[]{"characters"},
                Arrays.<Symbol>asList(id_ref, name_ref),
                Arrays.<Reference>asList(name_ref),
                new boolean[]{false},
                null, null,
                new WhereClause(whereClause),
                null
        );
        plan = new Plan();
        plan.add(searchNode);
        job = executor.newJob(plan);
        ESSearchTask searchTask = (ESSearchTask) job.tasks().get(0);

        searchTask.start();
        rows = searchTask.result().get(0).get();
        assertThat(rows.length, is(0));
    }

    @Test
    public void testESDeleteTask() throws Exception {
        insertCharacters();

        ESDeleteNode node = new ESDeleteNode("characters", "2", "2", Optional.<Long>absent());
        Plan plan = new Plan();
        plan.add(node);
        Job job = executor.newJob(plan);
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();
        assertThat(rows.length, is(1)); // contains rowcount in first row
        assertThat((Long)rows[0][0], is(1L));

        // verify deletion
        ESGetNode getNode = new ESGetNode("characters", "2", "2");
        getNode.outputs(ImmutableList.<Symbol>of(id_ref, name_ref));
        plan = new Plan();
        plan.add(getNode);
        job = executor.newJob(plan);
        result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(0));

    }

    @Test
    public void testESIndexTask() throws Exception {
        execute("create table characters (id int primary key, name string)");
        ensureGreen();

        Map<String, Object> sourceMap = new HashMap<>();
        sourceMap.put(id_ref.info().ident().columnIdent().name(), 99);
        sourceMap.put(name_ref.info().ident().columnIdent().name(), "Marvin");

        ESIndexNode indexNode = new ESIndexNode(
                new String[]{"characters"},
                Arrays.asList(sourceMap),
                ImmutableList.of("99"),
                ImmutableList.of("99")
        );
        Plan plan = new Plan();
        plan.add(indexNode);
        Job job = executor.newJob(plan);
        assertThat(job.tasks().get(0), instanceOf(ESIndexTask.class));

        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();
        assertThat(rows.length, is(1));
        assertThat((Long)rows[0][0], is(1l));


        // verify insertion
        ESGetNode getNode = new ESGetNode("characters", "99", "99");
        getNode.outputs(ImmutableList.<Symbol>of(id_ref, name_ref));
        plan = new Plan();
        plan.add(getNode);
        job = executor.newJob(plan);
        result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(1));
        assertThat((Integer)objects[0][0], is(99));
        assertThat((String)objects[0][1], is("Marvin"));
    }

    @Test
    public void testESIndexPartitionedTableTask() throws Exception {
        execute("create table parted (" +
                "  id int, " +
                "  name string, " +
                "  date timestamp" +
                ") partitioned by (date)");
        ensureGreen();
        Map<String, Object> sourceMap = new MapBuilder<String, Object>()
                .put("id", 0L)
                .put("name", "Trillian")
                .map();
        PartitionName partitionName = new PartitionName("parted", Arrays.asList("13959981214861"));
        ESIndexNode indexNode = new ESIndexNode(
                new String[]{partitionName.stringValue()},
                Arrays.asList(sourceMap),
                ImmutableList.of("123"),
                ImmutableList.of("123")
                );
        Plan plan = new Plan();
        plan.add(indexNode);
        plan.expectsAffectedRows(true);
        Job job = executor.newJob(plan);
        assertThat(job.tasks().get(0), instanceOf(ESIndexTask.class));
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] indexResult = result.get(0).get();
        assertThat((Long)indexResult[0][0], is(1L));

        refresh();

        assertTrue(
                client().admin().indices().prepareExists(partitionName.stringValue())
                        .execute().actionGet().isExists()
        );
        assertTrue(
                client().admin().indices().prepareAliasesExist("parted")
                        .execute().actionGet().exists()
        );
        SearchHits hits = client().prepareSearch(partitionName.stringValue())
                .setTypes(Constants.DEFAULT_MAPPING_TYPE)
                .addFields("id", "name")
                .setQuery(new MapBuilder<String, Object>()
                                .put("match_all", new HashMap<String, Object>())
                                .map()
                ).execute().actionGet().getHits();
        assertThat(hits.getTotalHits(), is(1L));
        assertThat((Integer) hits.getHits()[0].field("id").getValues().get(0), is(0));
        assertThat((String)hits.getHits()[0].field("name").getValues().get(0), is("Trillian"));
    }

    @Test
    public void testESCountTask() throws Exception {
        insertCharacters();
        Plan plan = new Plan();
        WhereClause whereClause = new WhereClause(null, false);
        plan.add(new ESCountNode("characters", whereClause));

        List<ListenableFuture<Object[][]>> result = executor.execute(executor.newJob(plan));
        Object[][] rows = result.get(0).get();

        assertThat(rows.length, is(1));
        assertThat((Long)rows[0][0], is(3L));
    }

    @Test
    public void testESBulkInsertTask() throws Exception {
        execute("create table characters (id int primary key, name string)");
        ensureGreen();

        Map<String, Object> sourceMap1 = new HashMap<>();
        sourceMap1.put(id_ref.info().ident().columnIdent().name(), 99);
        sourceMap1.put(name_ref.info().ident().columnIdent().name(), "Marvin");

        Map<String, Object> sourceMap2 = new HashMap<>();
        sourceMap2.put(id_ref.info().ident().columnIdent().name(), 42);
        sourceMap2.put(name_ref.info().ident().columnIdent().name(), "Deep Thought");

        ESIndexNode indexNode = new ESIndexNode(
                new String[]{"characters"},
                Arrays.asList(sourceMap1, sourceMap2),
                ImmutableList.of("99", "42"),
                ImmutableList.of("99", "42")
        );

        Plan plan = new Plan();
        plan.add(indexNode);
        Job job = executor.newJob(plan);
        assertThat(job.tasks().get(0), instanceOf(ESBulkIndexTask.class));

        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();
        assertThat(rows.length, is(1));
        assertThat((Long)rows[0][0], is(2l));

        // verify insertion

        ESGetNode getNode = new ESGetNode("characters",
                Arrays.asList("99", "42"),
                Arrays.asList("99", "42"));
        getNode.outputs(ImmutableList.<Symbol>of(id_ref, name_ref));
        plan = new Plan();
        plan.add(getNode);
        job = executor.newJob(plan);
        result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(2));
        assertThat((Integer)objects[0][0], is(99));
        assertThat((String)objects[0][1], is("Marvin"));

        assertThat((Integer)objects[1][0], is(42));
        assertThat((String)objects[1][1], is("Deep Thought"));
    }

    @Test
    public void testESUpdateByIdTask() throws Exception {
        insertCharacters();

        // update characters set name='Vogon lyric fan' where id=1
        WhereClause whereClause = new WhereClause(null, false);
        whereClause.clusteredByLiteral(new StringLiteral("1"));
        ESUpdateNode updateNode = new ESUpdateNode(
                new String[]{"characters"},
                new HashMap<Reference, Symbol>(){{
                    put(name_ref, new StringLiteral("Vogon lyric fan"));
                }},
                whereClause,
                asList("1"),
                asList("1")
        );
        Plan plan = new Plan();
        plan.add(updateNode);
        plan.expectsAffectedRows(true);

        Job job = executor.newJob(plan);
        assertThat(job.tasks().get(0), instanceOf(ESUpdateByIdTask.class));
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();

        assertThat(rows.length, is(1));
        assertThat((Long)rows[0][0], is(1l));

        // verify update
        ESGetNode getNode = new ESGetNode("characters", Arrays.asList("1"), Arrays.asList("1"));
        getNode.outputs(ImmutableList.<Symbol>of(id_ref, name_ref));
        plan = new Plan();
        plan.add(getNode);
        job = executor.newJob(plan);
        result = executor.execute(job);
        Object[][] objects = result.get(0).get();

        assertThat(objects.length, is(1));
        assertThat((Integer)objects[0][0], is(1));
        assertThat((String)objects[0][1], is("Vogon lyric fan"));
    }

    @Test
    public void testUpdateByQueryTaskWithVersion() throws Exception {
        insertCharacters();

        // do update
        Function whereClauseFunction = new Function(AndOperator.INFO, Arrays.<Symbol>asList(
                new Function(new FunctionInfo(
                        new FunctionIdent(EqOperator.NAME, asList(DataType.LONG, DataType.LONG)),
                        DataType.BOOLEAN),
                        Arrays.<Symbol>asList(version_ref, new LongLiteral(1L))
                ),
                new Function(new FunctionInfo(
                        new FunctionIdent(EqOperator.NAME, asList(DataType.STRING, DataType.STRING)), DataType.BOOLEAN),
                        Arrays.<Symbol>asList(name_ref, new StringLiteral("Arthur"))
                )));

        // update characters set name='mostly harmless' where name='Arthur' and "_version"=?
        WhereClause whereClause = new WhereClause(whereClauseFunction);
        whereClause.version(1L);
        ESUpdateNode updateNode = new ESUpdateNode(
                new String[]{"characters"},
                new HashMap<Reference, Symbol>(){{
                    put(name_ref, new StringLiteral("mostly harmless"));
                }},
                whereClause,
                ImmutableList.<String>of(),
                ImmutableList.<String>of()
        );
        Plan plan = new Plan();
        plan.add(updateNode);
        plan.expectsAffectedRows(true);

        Job job = executor.newJob(plan);
        assertThat(job.tasks().get(0), instanceOf(ESUpdateByQueryTask.class));
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();
        assertThat((Long) rows[0][0], is(1l));

        ESGetNode getNode = new ESGetNode("characters", "1", "1");
        getNode.outputs(Arrays.<Symbol>asList(id_ref, name_ref, version_ref));
        plan = new Plan();
        plan.add(getNode);
        plan.expectsAffectedRows(false);

        job = executor.newJob(plan);
        result = executor.execute(job);
        rows = result.get(0).get();

        assertThat(rows.length, is(1));
        assertThat((Integer)rows[0][0], is(1));
        assertThat((String)rows[0][1], is("mostly harmless"));
        assertThat((Long)rows[0][2], is(2L));
    }

    @Test
    public void testUpdateByQueryTask() throws Exception {
        insertCharacters();

        Function whereClause = new Function(OrOperator.INFO, Arrays.<Symbol>asList(
                new Function(new FunctionInfo(
                        new FunctionIdent(EqOperator.NAME, asList(DataType.STRING, DataType.STRING)),
                        DataType.BOOLEAN),
                        Arrays.<Symbol>asList(name_ref, new StringLiteral("Trillian"))
                ),
                new Function(new FunctionInfo(
                        new FunctionIdent(EqOperator.NAME, asList(DataType.INTEGER, DataType.INTEGER)), DataType.BOOLEAN),
                        Arrays.<Symbol>asList(id_ref, new IntegerLiteral(1))
                )));

        // update characters set name='mostly harmless' where id=1 or name='Trillian'
        ESUpdateNode updateNode = new ESUpdateNode(
                new String[]{"characters"},
                new HashMap<Reference, Symbol>(){{
                    put(name_ref, new StringLiteral("mostly harmless"));
                }},
                new WhereClause(whereClause),
                new ArrayList<String>(0),
                new ArrayList<String>(0)
        );
        Plan plan = new Plan();
        plan.add(updateNode);
        plan.expectsAffectedRows(true);

        Job job = executor.newJob(plan);
        assertThat(job.tasks().get(0), instanceOf(ESUpdateByQueryTask.class));
        List<ListenableFuture<Object[][]>> result = executor.execute(job);
        Object[][] rows = result.get(0).get();
        assertThat((Long)rows[0][0], is(2l));

        refresh();

        // verify update
        Function searchWhereClause = new Function(new FunctionInfo(
                new FunctionIdent(EqOperator.NAME, asList(DataType.STRING, DataType.STRING)),
                DataType.BOOLEAN),
                Arrays.<Symbol>asList(name_ref, new StringLiteral("mostly harmless")));
        ESSearchNode node = new ESSearchNode(
                new String[]{"characters"},
                Arrays.<Symbol>asList(id_ref, name_ref),
                ImmutableList.of(id_ref),
                new boolean[]{false},
                null, null, new WhereClause(searchWhereClause),
                null
        );
        node.outputTypes(Arrays.asList(id_ref.info().type(), name_ref.info().type()));
        plan = new Plan();
        plan.add(node);
        plan.expectsAffectedRows(false);

        job = executor.newJob(plan);
        result = executor.execute(job);
        rows = result.get(0).get();

        assertThat(rows.length, is(2));
        assertThat((Integer)rows[0][0], is(1));
        assertThat((String)rows[0][1], is("mostly harmless"));

        assertThat((Integer)rows[1][0], is(3));
        assertThat((String)rows[1][1], is("mostly harmless"));

    }
}
