/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.calciteadapter.rel.logical;

import java.util.List;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.util.ImmutableBitSet;

public class VoltDBLAggregate extends Aggregate  implements VoltDBLRel {

    /** Constructor */
    private VoltDBLAggregate(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            RelNode child,
            boolean indicator,
            ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets,
            List<AggregateCall> aggCalls) {
      super(cluster, traitSet, child, indicator, groupSet, groupSets, aggCalls);
    }

    @Override
    public VoltDBLAggregate copy(RelTraitSet traitSet, RelNode input,
            boolean indicator, ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
        return VoltDBLAggregate.create(
                getCluster(),
                traitSet,
                input,
                indicator,
                groupSet,
                groupSets,
                aggCalls);
    }

    public static VoltDBLAggregate create(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            RelNode child,
            boolean indicator,
            ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets,
            List<AggregateCall> aggCalls) {
        return new VoltDBLAggregate(
                cluster,
                traitSet,
                child,
                indicator,
                groupSet,
                groupSets,
                aggCalls);
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner,
            RelMetadataQuery mq) {
        // REVIEW jvs 24-Aug-2008:  This is bogus, but no more bogus
        // than what's currently in Join.
        double rowCount = mq.getRowCount(this);
        // Aggregates with more aggregate functions cost a bit more
        float multiplier = 1f;
        for (AggregateCall aggCall : aggCalls) {
            if (aggCall.getAggregation().getName().equals("AVG")) {
                // to make sure that AVG loses to SUM / COUNT
                multiplier *= 10.f;
            }
        }
        return planner.getCostFactory().makeCost(rowCount * multiplier, 0, 0);
    }

}
