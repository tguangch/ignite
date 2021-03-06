/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.affinity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.internal.processors.cache.query.GridCacheSqlQuery;
import org.apache.ignite.internal.processors.query.h2.IgniteH2Indexing;
import org.apache.ignite.internal.processors.query.h2.opt.GridH2Table;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlAlias;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlAst;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlColumn;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlConst;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlElement;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlOperation;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlOperationType;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlParameter;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlQuery;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlSelect;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlTable;
import org.apache.ignite.internal.util.typedef.F;
import org.h2.table.Column;
import org.h2.value.Value;
import org.jetbrains.annotations.Nullable;

/**
 * Partition tree extractor.
 */
public class PartitionExtractor {
    /**
     * Maximum number of partitions to be used in case of between expression.
     * In case of exceeding all partitions will be used.
     */
    private static final int DFLT_MAX_EXTRACTED_PARTS_FROM_BETWEEN = 16;

    /** Indexing. */
    private final IgniteH2Indexing idx;

    /** Maximum number of partitions to be used in case of between expression. */
    private final int maxPartsCntBetween;

    /**
     * Constructor.
     *
     * @param idx Indexing.
     */
    public PartitionExtractor(IgniteH2Indexing idx) {
        this.idx = idx;

        maxPartsCntBetween = Integer.getInteger(
            IgniteSystemProperties.IGNITE_SQL_MAX_EXTRACTED_PARTS_FROM_BETWEEN,
            DFLT_MAX_EXTRACTED_PARTS_FROM_BETWEEN
        );
    }

    /**
     * Extract partitions.
     *
     * @param qry Query.
     * @return Partitions.
     */
    public PartitionResult extract(GridSqlQuery qry) throws IgniteCheckedException {
        // No unions support yet.
        if (!(qry instanceof GridSqlSelect))
            return null;

        GridSqlSelect select = (GridSqlSelect)qry;

        // Currently we can extract data only from a single table.
        GridSqlTable tbl = unwrapTable(select.from());

        if (tbl == null)
            return null;

        // Do extract.
        PartitionNode tree = extractFromExpression(select.where());

        assert tree != null;

        // Reduce tree if possible.
        tree = tree.optimize();

        if (tree instanceof PartitionAllNode)
            return null;

        // Return.
        PartitionTableDescriptor desc = descriptor(tbl.dataTable());

        return new PartitionResult(desc, tree);
    }

    /**
     * Merge partition info from multiple queries.
     *
     * @param qrys Queries.
     * @return Partition result or {@code null} if nothing is resolved.
     */
    @SuppressWarnings("IfMayBeConditional")
    public PartitionResult merge(List<GridCacheSqlQuery> qrys) {
        // Check if merge is possible.
        PartitionTableDescriptor desc = null;

        for (GridCacheSqlQuery qry : qrys) {
            PartitionResult qryRes = (PartitionResult)qry.derivedPartitions();

            if (qryRes == null)
                // Failed to get results for one query -> broadcast.
                return null;

            if (desc == null)
                desc = qryRes.descriptor();
            else if (!F.eq(desc, qryRes.descriptor()))
                // Queries refer to different tables, cannot merge -> broadcast.
                return null;
        }

        // Merge.
        PartitionNode tree = null;

        for (GridCacheSqlQuery qry : qrys) {
            PartitionResult qryRes = (PartitionResult)qry.derivedPartitions();

            if (tree == null)
                tree = qryRes.tree();
            else
                tree = new PartitionCompositeNode(tree, qryRes.tree(), PartitionCompositeNodeOperator.OR);
        }

        // Optimize.
        assert tree != null;

        tree = tree.optimize();

        if (tree instanceof PartitionAllNode)
            return null;

        return new PartitionResult(desc, tree);
    }

    /**
     * Try unwrapping the table.
     *
     * @param from From.
     * @return Table or {@code null} if not a table.
     */
    @Nullable private static GridSqlTable unwrapTable(GridSqlAst from) {
        if (from instanceof GridSqlAlias)
            from = from.child();

        if (from instanceof GridSqlTable)
            return (GridSqlTable)from;

        return null;
    }

    /**
     * Extract partitions from expression.
     *
     * @param expr Expression.
     * @return Partition tree.
     */
    @SuppressWarnings("EnumSwitchStatementWhichMissesCases")
    private PartitionNode extractFromExpression(GridSqlAst expr) throws IgniteCheckedException {
        PartitionNode res = PartitionAllNode.INSTANCE;

        if (expr instanceof GridSqlOperation) {
            GridSqlOperation op = (GridSqlOperation)expr;

            switch (op.operationType()) {
                case AND:
                    res = extractFromAnd(op);

                    break;

                case OR:
                    res = extractFromOr(op);

                    break;

                case IN:
                    res = extractFromIn(op);

                    break;

                case EQUAL:
                    res = extractFromEqual(op);
            }
        }

        // Cannot determine partition.
        return res;
    }

    /**
     * Extract partition information from AND.
     *
     * @param op Operation.
     * @return Partition.
     */
    private PartitionNode extractFromAnd(GridSqlOperation op) throws IgniteCheckedException {
        assert op.size() == 2;

        PartitionNode betweenNodes = tryExtractBetween(op);

        if (betweenNodes != null)
            return betweenNodes;

        PartitionNode part1 = extractFromExpression(op.child(0));
        PartitionNode part2 = extractFromExpression(op.child(1));

        return new PartitionCompositeNode(part1, part2, PartitionCompositeNodeOperator.AND);
    }

    /**
     * Extract partition information from OR.
     *
     * @param op Operation.
     * @return Partition.
     */
    private PartitionNode extractFromOr(GridSqlOperation op) throws IgniteCheckedException {
        assert op.size() == 2;

        PartitionNode part1 = extractFromExpression(op.child(0));
        PartitionNode part2 = extractFromExpression(op.child(1));

        return new PartitionCompositeNode(part1, part2, PartitionCompositeNodeOperator.OR);
    }

    /**
     * Extract partition information from IN.
     *
     * @param op Operation.
     * @return Partition.
     */
    private PartitionNode extractFromIn(GridSqlOperation op) throws IgniteCheckedException {
        // Operation should contain at least two children: left (column) and right (const or column).
        if (op.size() < 2)
            return PartitionAllNode.INSTANCE;

        // Left operand should be column.
        GridSqlAst left = op.child();

        GridSqlColumn leftCol;

        if (left instanceof GridSqlColumn)
            leftCol = (GridSqlColumn)left;
        else
            return PartitionAllNode.INSTANCE;

        // Can work only with Ignite tables.
        if (!(leftCol.column().getTable() instanceof GridH2Table))
            return PartitionAllNode.INSTANCE;

        Set<PartitionSingleNode> parts = new HashSet<>();

        for (int i = 1; i < op.size(); i++) {
            GridSqlAst right = op.child(i);

            GridSqlConst rightConst;
            GridSqlParameter rightParam;

            if (right instanceof GridSqlConst) {
                rightConst = (GridSqlConst)right;
                rightParam = null;
            }
            else if (right instanceof GridSqlParameter) {
                rightConst = null;
                rightParam = (GridSqlParameter)right;
            }
            else
                // One of members of "IN" list is neither const, nor param, so we do no know it's partition.
                // As this is disjunction, not knowing partition of a single element leads to unknown partition
                // set globally. Hence, returning null.
                return PartitionAllNode.INSTANCE;

            // Do extract.
            PartitionSingleNode part = extractSingle(leftCol.column(), rightConst, rightParam);

            // Same thing as above: single unknown partition in disjunction defeats optimization.
            if (part == null)
                return PartitionAllNode.INSTANCE;

            parts.add(part);
        }

        return parts.size() == 1 ? parts.iterator().next() : new PartitionGroupNode(parts);
    }

    /**
     * Extract partition information from equality.
     *
     * @param op Operation.
     * @return Partition.
     */
    private PartitionNode extractFromEqual(GridSqlOperation op) throws IgniteCheckedException {
        assert op.operationType() == GridSqlOperationType.EQUAL;

        GridSqlElement left = op.child(0);
        GridSqlElement right = op.child(1);

        GridSqlColumn leftCol;

        if (left instanceof GridSqlColumn)
            leftCol = (GridSqlColumn)left;
        else
            return PartitionAllNode.INSTANCE;

        if (!(leftCol.column().getTable() instanceof GridH2Table))
            return PartitionAllNode.INSTANCE;

        GridSqlConst rightConst;
        GridSqlParameter rightParam;

        if (right instanceof GridSqlConst) {
            rightConst = (GridSqlConst)right;
            rightParam = null;
        }
        else if (right instanceof GridSqlParameter) {
            rightConst = null;
            rightParam = (GridSqlParameter)right;
        }
        else
            return PartitionAllNode.INSTANCE;

        PartitionSingleNode part = extractSingle(leftCol.column(), rightConst, rightParam);

        return part != null ? part : PartitionAllNode.INSTANCE;
    }

    /**
     * Extract single partition.
     *
     * @param leftCol Left column.
     * @param rightConst Right constant.
     * @param rightParam Right parameter.
     * @return Partition or {@code null} if failed to extract.
     */
    @Nullable private PartitionSingleNode extractSingle(Column leftCol, GridSqlConst rightConst,
        GridSqlParameter rightParam) throws IgniteCheckedException {
        assert leftCol != null;
        assert leftCol.getTable() != null;
        assert leftCol.getTable() instanceof GridH2Table;

        GridH2Table tbl = (GridH2Table)leftCol.getTable();

        if (!tbl.isColumnForPartitionPruning(leftCol))
            return null;

        PartitionTableDescriptor tblDesc = descriptor(tbl);

        if (rightConst != null) {
            int part = idx.kernalContext().affinity().partition(tbl.cacheName(), rightConst.value().getObject());

            return new PartitionConstantNode(tblDesc, part);
        }
        else if (rightParam != null)
            return new PartitionParameterNode(tblDesc, idx, rightParam.index(), leftCol.getType());
        else
            return null;
    }

    /**
     * Get descriptor from table.
     *
     * @param tbl Table.
     * @return Descriptor.
     */
    private static PartitionTableDescriptor descriptor(GridH2Table tbl) {
        return new PartitionTableDescriptor(tbl.cacheName(), tbl.getName());
    }

    /**
     * Try to extract partitions from {@code op} assuming that it's between operation or simple range.
     *
     * @param op Sql operation.
     * @return {@code PartitionSingleNode} if operation reduced to one partition,
     *   {@code PartitionGroupNode} if operation reduced to multiple partitions or null if operation is neither
     *   between nor simple range. Null also returns if it's not possible to extract partitions from given operation.
     * @throws IgniteCheckedException If failed.
     */
    private PartitionNode tryExtractBetween(GridSqlOperation op) throws IgniteCheckedException {
        // Between operation (or similar range) should contain exact two children.
        assert op.size() == 2;

        GridSqlAst left = op.child();
        GridSqlAst right = op.child(1);

        GridSqlOperationType leftOpType = retrieveOperationType(left);
        GridSqlOperationType rightOpType = retrieveOperationType(right);

        if ((GridSqlOperationType.BIGGER == rightOpType || GridSqlOperationType.BIGGER_EQUAL == rightOpType) &&
            (GridSqlOperationType.SMALLER == leftOpType || GridSqlOperationType.SMALLER_EQUAL == leftOpType)) {
            GridSqlAst tmp = left;
            left = right;
            right = tmp;
        }
        else if (!((GridSqlOperationType.BIGGER == leftOpType || GridSqlOperationType.BIGGER_EQUAL == leftOpType) &&
            (GridSqlOperationType.SMALLER == rightOpType || GridSqlOperationType.SMALLER_EQUAL == rightOpType)))
            return null;

        // Try parse left AST.
        GridSqlColumn leftCol;

        if (left instanceof GridSqlOperation && left.child() instanceof GridSqlColumn &&
            (((GridSqlColumn)left.child()).column().getTable() instanceof GridH2Table))
            leftCol = left.child();
        else
            return null;

        // Try parse right AST.
        GridSqlColumn rightCol;

        if (right instanceof GridSqlOperation && right.child() instanceof GridSqlColumn)
            rightCol = right.child();
        else
            return null;

        GridH2Table tbl = (GridH2Table)leftCol.column().getTable();

        // Check that columns might be used for partition pruning.
        if(!tbl.isColumnForPartitionPruning(leftCol.column()))
            return null;

        // Check that both left and right AST use same column.
        if (!F.eq(leftCol.schema(), rightCol.schema()) ||
            !F.eq(leftCol.columnName(), rightCol.columnName()) ||
            !F.eq(leftCol.tableAlias(), rightCol.tableAlias()))
            return null;

        // Check columns type
        if (!(leftCol.column().getType() == Value.BYTE || leftCol.column().getType() == Value.SHORT ||
            leftCol.column().getType() == Value.INT || leftCol.column().getType() == Value.LONG))
            return null;

        // Try parse left AST right value (value to the right of '>' or '>=').
        GridSqlConst leftConst;

        if (left.child(1) instanceof GridSqlConst)
            leftConst = left.child(1);
        else
            return null;

        // Try parse right AST right value (value to the right of '<' or '<=').
        GridSqlConst rightConst;

        if (right.child(1) instanceof GridSqlConst)
            rightConst = right.child(1);
        else
            return null;

        long leftLongVal;
        long rightLongVal;

        try {
            leftLongVal = leftConst.value().getLong();
            rightLongVal = rightConst.value().getLong();
        }
        catch (Exception e) {
            return null;
        }

        // Increment left long value if '>' is used.
        if (((GridSqlOperation)left).operationType() == GridSqlOperationType.BIGGER)
            leftLongVal++;

        // Decrement right long value if '<' is used.
        if (((GridSqlOperation)right).operationType() == GridSqlOperationType.SMALLER)
            rightLongVal--;

        Set<PartitionSingleNode> parts = new HashSet<>();

        PartitionTableDescriptor desc = descriptor(tbl);

        for (long i = leftLongVal; i <= rightLongVal; i++) {
            parts.add(new PartitionConstantNode(desc,
                idx.kernalContext().affinity().partition((tbl).cacheName(), i)));

            if (parts.size() > maxPartsCntBetween)
                return null;
        }

        return parts.isEmpty() ? PartitionNoneNode.INSTANCE :
            parts.size() == 1 ? parts.iterator().next() : new PartitionGroupNode(parts);
    }

    /**
     * Retrieves operation type.
     *
     * @param ast Tree
     * @return Operation type.
     */
    private GridSqlOperationType retrieveOperationType(GridSqlAst ast) {
        if (!(ast instanceof GridSqlOperation))
            return null;

        return ((GridSqlOperation)ast).operationType();
    }
}
