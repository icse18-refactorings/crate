/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.engine.distribution;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.crate.analyze.WhereClause;
import io.crate.core.collections.TreeMapBuilder;
import io.crate.data.RowConsumer;
import io.crate.execution.dsl.phases.MergePhase;
import io.crate.execution.dsl.phases.NodeOperation;
import io.crate.execution.dsl.phases.RoutedCollectPhase;
import io.crate.execution.jobs.kill.TransportKillJobsNodeAction;
import io.crate.execution.support.Paging;
import io.crate.metadata.Routing;
import io.crate.metadata.RowGranularity;
import io.crate.planner.distribution.DistributionInfo;
import io.crate.test.integration.CrateDummyClusterServiceUnitTest;
import io.crate.types.DataType;
import io.crate.types.LongType;
import org.elasticsearch.common.settings.Settings;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.instanceOf;
import static org.mockito.Mockito.mock;

public class DistributingConsumerFactoryTest extends CrateDummyClusterServiceUnitTest {

    private DistributingConsumerFactory rowDownstreamFactory;

    @Before
    public void prepare() {
        rowDownstreamFactory = new DistributingConsumerFactory(
            Settings.EMPTY,
            clusterService,
            THREAD_POOL,
            mock(TransportKillJobsNodeAction.class),
            mock(TransportDistributedResultAction.class)
        );
    }

    private RowConsumer createDownstream(Set<String> downstreamExecutionNodes) {
        UUID jobId = UUID.randomUUID();
        Routing routing = new Routing(
            TreeMapBuilder.<String, Map<String, List<Integer>>>newMapBuilder()
                .put("n1", TreeMapBuilder.<String, List<Integer>>newMapBuilder()
                    .put("i1", Arrays.asList(1, 2)).map()).map());
        RoutedCollectPhase collectPhase = new RoutedCollectPhase(
            jobId,
            1,
            "collect",
            routing,
            RowGranularity.DOC,
            ImmutableList.of(),
            ImmutableList.of(),
            WhereClause.MATCH_ALL,
            DistributionInfo.DEFAULT_MODULO,
            null
        );
        MergePhase mergePhase = new MergePhase(
            jobId,
            2,
            "merge",
            1,
            1,
            downstreamExecutionNodes,
            ImmutableList.<DataType>of(LongType.INSTANCE),
            ImmutableList.of(),
            DistributionInfo.DEFAULT_BROADCAST,
            null
        );
        NodeOperation nodeOperation = NodeOperation.withDownstream(collectPhase, mergePhase, (byte) 0);
        return rowDownstreamFactory.create(nodeOperation, collectPhase.distributionInfo(), jobId, Paging.PAGE_SIZE);
    }

    @Test
    public void testCreateDownstreamOneNode() throws Exception {
        RowConsumer downstream = createDownstream(ImmutableSet.of("downstream_node"));
        assertThat(downstream, instanceOf(DistributingConsumer.class));
        assertThat(((DistributingConsumer) downstream).multiBucketBuilder, instanceOf(BroadcastingBucketBuilder.class));
    }

    @Test
    public void testCreateDownstreamMultipleNode() throws Exception {
        RowConsumer downstream = createDownstream(ImmutableSet.of("downstream_node1", "downstream_node2"));
        assertThat(((DistributingConsumer) downstream).multiBucketBuilder, instanceOf(ModuloBucketBuilder.class));
    }
}
