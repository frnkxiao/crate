package io.crate.metadata.table;

import com.google.common.collect.ImmutableList;
import io.crate.PartitionName;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.ReferenceInfo;
import io.crate.planner.symbol.DynamicReference;
import org.apache.lucene.util.BytesRef;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractTableInfo implements TableInfo {

    private static final BytesRef ZERO_REPLICAS = new BytesRef("0");

    @Override
    public int numberOfShards() {
        return 1;
    }

    @Override
    public BytesRef numberOfReplicas() {
        return ZERO_REPLICAS;
    }

    @Override
    public boolean hasAutoGeneratedPrimaryKey() {
        return false;
    }

    @Override
    public boolean isAlias() {
        return false;
    }

    @Override
    public boolean isPartitioned() {
        return false;
    }

    @Override
    public List<ReferenceInfo> partitionedByColumns() {
        return ImmutableList.of();
    }

    @Nullable
    @Override
    public String clusteredBy() {
        return null;
    }

    @Override
    public DynamicReference getDynamic(ColumnIdent ident) {
        return null;
    }

    @Override
    public List<PartitionName> partitions() {
        return new ArrayList<>(0);
    }

    @Override
    public List<String> partitionedBy() {
        return ImmutableList.of();
    }
}
