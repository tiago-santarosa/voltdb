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

package org.voltdb.calciteadapter.rel.physical;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBitSet;
import org.voltdb.calciteadapter.converter.ExpressionTypeConverter;
import org.voltdb.calciteadapter.converter.RelConverter;
import org.voltdb.calciteadapter.converter.RexConverter;
import org.voltdb.expressions.AbstractExpression;
import org.voltdb.newplanner.guards.CalcitePlanningException;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.AggregatePlanNode;
import org.voltdb.plannodes.NodeSchema;
import org.voltdb.types.ExpressionType;

import java.util.List;

public abstract class AbstractVoltDBPAggregate extends Aggregate implements VoltDBPRel {

    // HAVING expression
    final private RexNode m_postPredicate;

    final private int m_splitCount;

    // TRUE if this aggregate relation is part of a coordinator tree.
    // The indicator may be useful during the Exchange Transform rule when a coordinator aggregate
    // differs from a fragment one
    final private boolean m_isCoordinatorAggr;

    /**
     * Constructor.
     *
     * @param cluster Cluster
     * @param traitSet Traits
     * @param child Child
     * @param indicator Whether row type should include indicator fields to
     *                  indicate which grouping set is active; true is deprecated
     * @param groupSet Bit set of grouping fields
     * @param groupSets List of all grouping sets; null for just {@code groupSet}
     * @param aggCalls Collection of calls to aggregate functions
     * @param havingExpression HAVING expression
     * @param splitCount Number of concurrent processes that this VoltDBPRel will be executed in
     * @param isCoordinatorAggr If this aggregate relation is part of a coordinator tree.
     */
    protected AbstractVoltDBPAggregate(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            RelNode child,
            boolean indicator,
            ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets,
            List<AggregateCall> aggCalls,
            RexNode havingExpression,
            int splitCount,
            boolean isCoordinatorAggr) {
        super(cluster, traitSet, child, indicator, groupSet, groupSets, aggCalls);
        m_postPredicate = havingExpression;
        m_splitCount = splitCount;
        m_isCoordinatorAggr = isCoordinatorAggr;
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        super.explainTerms(pw);
        pw.item("split", m_splitCount);
        pw.item("coorinator", m_isCoordinatorAggr);
        pw.itemIf("having", m_postPredicate, m_postPredicate != null);
        return pw;
    }

    @Override
    protected String computeDigest() {
        String digest = super.computeDigest();
        digest += "_split_" + m_splitCount;
        digest += "_coordinator_" + m_isCoordinatorAggr;
        if (m_postPredicate != null) {
            digest += m_postPredicate.toString();
        }
        return digest;
    }

    @Override
    public RelOptCost computeSelfCost(RelOptPlanner planner,
                                      RelMetadataQuery mq) {
        double rowCount = getInput().estimateRowCount(mq);
        return planner.getCostFactory().makeCost(rowCount, 0, 0);
    }

    /**
     * Copy self, refer to the constructor.
     *
     * @param cluster Cluster
     * @param traitSet Traits
     * @param input Input
     * @param indicator Whether row type should include indicator fields to
     *                  indicate which grouping set is active; true is deprecated
     * @param groupSet Bit set of grouping fields
     * @param groupSets List of all grouping sets; null for just {@code groupSet}
     * @param aggCalls Collection of calls to aggregate functions
     * @param havingExpression HAVING expression
     * @param splitCount Number of concurrent processes that this VoltDBPRel will be executed in
     * @param isCoordinatorAggr If this aggregate relation is part of a coordinator tree.
     * @return A cloned {@link AbstractVoltDBPAggregate}.
     */
    public abstract AbstractVoltDBPAggregate copy(
            RelOptCluster cluster,
            RelTraitSet traitSet,
            RelNode input,
            boolean indicator,
            ImmutableBitSet groupSet,
            List<ImmutableBitSet> groupSets,
            List<AggregateCall> aggCalls,
            RexNode havingExpression,
            int splitCount,
            boolean isCoordinatorAggr);

    public RexNode getPostPredicate() {
        return m_postPredicate;
    }

    @Override
    public int getSplitCount() {
        return m_splitCount;
    }

    public boolean getIsCoordinatorAggr() {
        return m_isCoordinatorAggr;
    }

    protected abstract AggregatePlanNode getAggregatePlanNode();

    @Override
    public AbstractPlanNode toPlanNode() {
        AbstractPlanNode apn = toPlanNode(getInput().getRowType());

        // Convert child
        AbstractPlanNode child = inputRelNodeToPlanNode(this, 0);
        apn.addAndLinkChild(child);

        return apn;
    }

    /**
     * Convert self to a VoltDB plan node
     *
     * @param inputRowType
     * @return
     */
    AbstractPlanNode toPlanNode(RelDataType inputRowType) {
        AggregatePlanNode apn = getAggregatePlanNode();

        // Generate output schema
        NodeSchema schema = RexConverter.convertToVoltDBNodeSchema(getRowType());
        apn.setOutputSchema(schema);

        // The Aggregate's record layout seems to be
        // - GROUP BY expressions
        // - AGGR expressions form SELECT clause - corresponding aggrCall has name matching the filed name
        // - AGGR expressions from HAVING clause - aggrCall name is NULL
        RelDataType aggrRowType = getRowType();
        List<RelDataTypeField> fields = inputRowType.getFieldList();
        // Aggreagte fields start right after the grouping ones in order of the aggregate calls
        int aggrFieldIdx = 0 + getGroupCount();
        for(AggregateCall aggrCall : getAggCallList()) {
            // Aggr type
            ExpressionType aggrType =
                    ExpressionTypeConverter.calciteTypeToVoltType(aggrCall.getAggregation().kind);
            if (aggrType == null) {
                throw new CalcitePlanningException("Unsupported aggregate function: " + aggrCall.getAggregation().kind.lowerName);
            }

            List<Integer> aggrExprIndexes = aggrCall.getArgList();
            // VoltDB supports aggregates with only one parameter
            assert(aggrExprIndexes.size() < 2);
            AbstractExpression aggrExpr = null;
            if (!aggrExprIndexes.isEmpty()) {
                RelDataTypeField field = fields.get(aggrExprIndexes.get(0));
                aggrExpr = RelConverter.convertDataTypeField(field);
            } else if (ExpressionType.AGGREGATE_COUNT == aggrType) {
                aggrType = ExpressionType.AGGREGATE_COUNT_STAR;
            }

            assert(aggrFieldIdx < aggrRowType.getFieldCount());
            apn.addAggregate(aggrType, aggrCall.isDistinct(),  aggrFieldIdx, aggrExpr);
            // Increment aggregate field index
            aggrFieldIdx++;
        }
        // Group by
        setGroupByExpressions(apn);
        // Having
        setPostPredicate(apn);

        return apn;
    }

    private void setGroupByExpressions(AggregatePlanNode apn) {
        ImmutableBitSet groupBy = getGroupSet();
        List<RelDataTypeField> rowTypeList = this.getRowType().getFieldList();
        for (int index = groupBy.nextSetBit(0); index != -1; index = groupBy.nextSetBit(index + 1)) {
            assert(index < rowTypeList.size());
            AbstractExpression groupByExpr = RelConverter.convertDataTypeField(rowTypeList.get(index));
            apn.addGroupByExpression(groupByExpr);
        }
    }

    private void setPostPredicate(AggregatePlanNode apn) {
        if (m_postPredicate != null) {
            AbstractExpression havingExpression = RexConverter.convert(m_postPredicate);
            apn.setPostPredicate(havingExpression);
        }
    }

}
