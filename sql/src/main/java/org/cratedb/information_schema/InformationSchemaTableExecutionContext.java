package org.cratedb.information_schema;

import com.google.common.collect.ImmutableMap;
import org.cratedb.action.sql.ITableExecutionContext;
import org.cratedb.index.ColumnDefinition;
import org.cratedb.sql.TableUnknownException;
import org.cratedb.sql.types.SQLFieldMapper;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InformationSchemaTableExecutionContext implements ITableExecutionContext {

    public static final String SCHEMA_NAME = "INFORMATION_SCHEMA";
    private final String tableName;

    private final ImmutableMap<String, InformationSchemaTable> tablesMap;

    @Inject
    public InformationSchemaTableExecutionContext(Map<String,
                InformationSchemaTable> informationSchemaTables, @Assisted String tableName) {
        this.tablesMap = ImmutableMap.copyOf(informationSchemaTables);
        if (!this.tablesMap.containsKey(tableName)) {
            throw new TableUnknownException(tableName);
        }
        this.tableName = tableName;
    }

    public ImmutableMap<String, InformationSchemaColumn> fieldMapper() {
        return tablesMap.get(tableName).fieldMapper();
    }

    @Override
    public SQLFieldMapper mapper() {
        throw new UnsupportedOperationException("Information Schema currently has no " +
                "SQLFieldMapper");
    }

    @Override
    public Object mappedValue(String name, Object value) {
        return value;
    }

    @Override
    public List<String> primaryKeys() {
        return new ArrayList<>(0);
    }

    @Override
    public List<String> primaryKeysIncludingDefault() {
        return new ArrayList<>(0);
    }

    @Override
    public Iterable<String> allCols() {
        return tablesMap.get(tableName).cols();
    }

    @Override
    public boolean hasCol(String name) {
        return tablesMap.get(tableName).fieldMapper().containsKey(name);
    }

    @Override
    public Boolean isRouting(String name) {
        return false;
    }

    public boolean tableIsAlias() {
        return false;
    }

    @Override
    public boolean isMultiValued(String columnName) {
        return fieldMapper().get(columnName).allowMultipleValues;
    }
}