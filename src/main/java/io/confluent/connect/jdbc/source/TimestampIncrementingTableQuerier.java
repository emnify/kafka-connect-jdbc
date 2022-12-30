/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.jdbc.source;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collections;
import java.util.TimeZone;

import io.confluent.connect.jdbc.dialect.DatabaseDialect;
import io.confluent.connect.jdbc.source.SchemaMapping.FieldSetter;
import io.confluent.connect.jdbc.source.TimestampIncrementingCriteria.CriteriaValues;
import io.confluent.connect.jdbc.source.JdbcSourceConnectorConfig.TimestampGranularity;
import io.confluent.connect.jdbc.util.ColumnDefinition;
import io.confluent.connect.jdbc.util.ColumnId;
import io.confluent.connect.jdbc.util.DateTimeUtils;
import io.confluent.connect.jdbc.util.ExpressionBuilder;

/**
 * <p>
 *   TimestampIncrementingTableQuerier performs incremental loading of data using two mechanisms: a
 *   timestamp column provides monotonically incrementing values that can be used to detect new or
 *   modified rows and a strictly incrementing (e.g. auto increment) column allows detecting new
 *   rows or combined with the timestamp provide a unique identifier for each update to the row.
 * </p>
 * <p>
 *   At least one of the two columns must be specified (or left as "" for the incrementing column
 *   to indicate use of an auto-increment column). If both columns are provided, they are both
 *   used to ensure only new or updated rows are reported and to totally order updates so
 *   recovery can occur no matter when offsets were committed. If only the incrementing fields is
 *   provided, new rows will be detected but not updates. If only the timestamp field is
 *   provided, both new and updated rows will be detected, but stream offsets will not be unique
 *   so failures may cause duplicates or losses.
 * </p>
 */
public class TimestampIncrementingTableQuerier extends TableQuerier implements CriteriaValues {
  private static final Logger log = LoggerFactory.getLogger(
      TimestampIncrementingTableQuerier.class
  );

  protected final List<String> timestampColumnNames;
  protected TimestampIncrementingOffset committedOffset;
  protected TimestampIncrementingOffset offset;
  protected TimestampIncrementingCriteria criteria;
  protected final Map<String, String> partition;
  protected final String topic;
  protected final TimestampGranularity timestampGranularity;
  private final List<ColumnId> timestampColumns;
  private String incrementingColumnName;
  private boolean incrementingRelaxed;
  private long timestampDelay;
  private final TimeZone timeZone;

  public TimestampIncrementingTableQuerier(DatabaseDialect dialect, QueryMode mode, String name,
                                           String topicPrefix,
                                           List<String> timestampColumnNames,
                                           String incrementingColumnName,
                                           boolean incrementingRelaxed,
                                           Map<String, Object> offsetMap, Long timestampDelay,
                                           TimeZone timeZone, String suffix,
                                           TimestampGranularity timestampGranularity) {
    super(dialect, mode, name, topicPrefix, suffix);
    this.incrementingColumnName = incrementingColumnName;
    this.incrementingRelaxed = incrementingRelaxed;
    this.timestampColumnNames = timestampColumnNames != null
        ? timestampColumnNames : Collections.emptyList();
    this.timestampDelay = timestampDelay;
    this.committedOffset = this.offset = TimestampIncrementingOffset.fromMap(offsetMap);
    this.timestampColumns = new ArrayList<>();
    for (String timestampColumn : this.timestampColumnNames) {
      if (timestampColumn != null && !timestampColumn.isEmpty()) {
        timestampColumns.add(new ColumnId(tableId, timestampColumn));
      }
    }

    switch (mode) {
      case TABLE:
        String tableName = tableId.tableName();
        topic = topicPrefix + tableName;// backward compatible
        partition = OffsetProtocols.sourcePartitionForProtocolV1(tableId);
        break;
      case QUERY:
        partition = Collections.singletonMap(JdbcSourceConnectorConstants.QUERY_NAME_KEY,
            JdbcSourceConnectorConstants.QUERY_NAME_VALUE);
        topic = topicPrefix;
        break;
      default:
        throw new ConnectException("Unexpected query mode: " + mode);
    }

    this.timeZone = timeZone;
    this.timestampGranularity = timestampGranularity;
  }

  private static String DATETIME = "datetime";

  @Override
  protected void createPreparedStatement(Connection db) throws SQLException {
    findDefaultAutoIncrementingColumn(db);

    ColumnId incrementingColumn = null;
    if (incrementingColumnName != null && !incrementingColumnName.isEmpty()) {
      incrementingColumn = new ColumnId(tableId, incrementingColumnName);
    }

    ExpressionBuilder builder = dialect.expressionBuilder();
    switch (mode) {
      case TABLE:
        builder.append("SELECT * FROM ");
        builder.append(tableId);
        break;
      case QUERY:
        builder.append(query);
        break;
      default:
        throw new ConnectException("Unknown mode encountered when preparing query: " + mode);
    }

    // Append the criteria using the columns ...
    criteria = dialect.criteriaFor(incrementingColumn, incrementingRelaxed, timestampColumns);
    criteria.whereClause(builder);

    addSuffixIfPresent(builder);
    
    String queryString = builder.toString();
    recordQuery(queryString);
    log.debug("{} prepared SQL query: {}", this, queryString);
    stmt = dialect.createPreparedStatement(db, queryString);
  }

  @Override
  public void reset(long now, boolean resetOffset) {
    if (this.incrementingRelaxed
        && this.incrementingColumnName != null
        && this.incrementingColumnName.length() > 0
        && this.db != null
    ) {
      updateMaximumSeenOffset();
    } else {
      if (this.incrementingRelaxed) {
        log.warn("Skipping maximum update, but incrementing.relaxed.monotonic is enabled.");
      }
    }
    if (resetOffset) {
      this.offset = this.committedOffset;
    }
    super.reset(now, resetOffset);
  }

  private void updateMaximumSeenOffset() throws ConnectException {
    if (this.db != null) {
      try (
              PreparedStatement st = createSelectMaximumPreparedStatement(db);
              ResultSet rs = executeMaxQuery(st)
      ) {
        this.offset = extractMaximumOffset(rs);
      } catch (Throwable th) {
        throw new ConnectException("Unable to fetch new maximum", th);
      }
    } else {
      log.warn("Unable to update maximum seen offset. Database connection closed.");
    }
  }

  protected PreparedStatement createSelectMaximumPreparedStatement(Connection db)
          throws SQLException {
    ColumnId incrementingColumn = null;
    if (incrementingColumnName != null && !incrementingColumnName.isEmpty()) {
      incrementingColumn = new ColumnId(tableId, incrementingColumnName);
      String queryString = dialect.buildSelectMaxStatement(tableId, incrementingColumn);
      recordQuery(queryString);
      log.debug("{} prepared SQL query: {}", this, queryString);
      return dialect.createPreparedStatement(db, queryString);
    } else {
      throw new ConnectException("Unable to find incrementing column");
    }
  }


  private void findDefaultAutoIncrementingColumn(Connection db) throws SQLException {
    // Default when unspecified uses an autoincrementing column
    if (incrementingColumnName != null && incrementingColumnName.isEmpty()) {
      // Find the first auto-incremented column ...
      for (ColumnDefinition defn : dialect.describeColumns(
          db,
          tableId.catalogName(),
          tableId.schemaName(),
          tableId.tableName(),
          null).values()) {
        if (defn.isAutoIncrement()) {
          incrementingColumnName = defn.id().name();
          break;
        }
      }
    }
    // If still not found, query the table and use the result set metadata.
    // This doesn't work if the table is empty.
    if (incrementingColumnName != null && incrementingColumnName.isEmpty()) {
      log.debug("Falling back to describe '{}' table by querying {}", tableId, db);
      for (ColumnDefinition defn : dialect.describeColumnsByQuerying(db, tableId).values()) {
        if (defn.isAutoIncrement()) {
          incrementingColumnName = defn.id().name();
          break;
        }
      }
    }
  }

  @Override
  protected ResultSet executeQuery() throws SQLException {
    criteria.setQueryParameters(stmt, this);
    log.trace("Statement to execute: {}", stmt.toString());
    return stmt.executeQuery();
  }

  private ResultSet executeMaxQuery(PreparedStatement st) throws SQLException {
    log.trace("Statement to execute: {}", st.toString());
    return st.executeQuery();
  }

  @Override
  public SourceRecord extractRecord() throws SQLException {
    Struct record = new Struct(schemaMapping.schema());
    for (FieldSetter setter : schemaMapping.fieldSetters()) {
      try {
        setter.setField(record, resultSet);
      } catch (IOException e) {
        log.warn("Error mapping fields into Connect record", e);
        throw new ConnectException(e);
      } catch (SQLException e) {
        log.warn("SQL error mapping fields into Connect record", e);
        throw new DataException(e);
      }
    }
    offset = criteria.extractValues(schemaMapping.schema(), record, offset, timestampGranularity);
    return new SourceRecord(partition, offset.toMap(), topic, record.schema(), record);
  }

  protected TimestampIncrementingOffset extractMaximumOffset(ResultSet rs) throws SQLException {
    try {
      // TODO: add test case
      if (rs.next()) {
        String schemaName = tableId != null ? tableId.tableName() : null; // backwards compatible
        SchemaMapping mapping = SchemaMapping.create(schemaName, resultSet.getMetaData(), dialect);
        assert !mapping.fieldSetters().isEmpty();
        FieldSetter se = mapping.fieldSetters().get(0);
        assert se.field().schema().name() == incrementingColumnName;
        Struct st = new Struct(mapping.schema());
        se.setField(st, rs);
        return criteria.extractMaximumSeenOffset(this.schemaMapping.schema(), st, this.offset);
      } else {
        log.info("No maximum found. Skipping table.");
        return this.offset;
      }
    } catch (IOException e) {
      log.warn("Error extracting maximum", e);
      throw new ConnectException(e);
    } catch (SQLException e) {
      log.warn("SQL error mapping incrementing column into maximum offset", e);
      throw new DataException(e);
    }
  }

  @Override
  public Timestamp beginTimestampValue() {
    return offset.getTimestampOffset();
  }

  @Override
  public Timestamp endTimestampValue()  throws SQLException {
    final long currentDbTime = dialect.currentTimeOnDB(
        stmt.getConnection(),
        DateTimeUtils.getTimeZoneCalendar(timeZone)
    ).getTime();
    return new Timestamp(currentDbTime - timestampDelay);
  }

  @Override
  public Long lastIncrementedValue() {
    return offset.getIncrementingOffset();
  }

  @Override
  public Long maximumSeenValue() {
    return offset.getMaximumSeenOffset();
  }

  @Override
  public String toString() {
    return "TimestampIncrementingTableQuerier{"
           + "table=" + tableId
           + ", query='" + query + '\''
           + ", topicPrefix='" + topicPrefix + '\''
           + ", incrementingColumn='" + (incrementingColumnName != null
                                        ? incrementingColumnName
                                        : "") + '\''
           + ", timestampColumns=" + timestampColumnNames
           + '}';
  }
}
