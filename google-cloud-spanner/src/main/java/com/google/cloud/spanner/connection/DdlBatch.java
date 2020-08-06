/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spanner.connection;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.AsyncResultSet;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Options.QueryOption;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerExceptionFactory;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.connection.BaseConnection.InternalMetadataQuery;
import com.google.cloud.spanner.connection.StatementParser.ParsedStatement;
import com.google.cloud.spanner.connection.StatementParser.StatementType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.spanner.admin.database.v1.UpdateDatabaseDdlMetadata;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang3.ArrayUtils;

/**
 * {@link UnitOfWork} that is used when a DDL batch is started. These batches only accept DDL
 * statements. All DDL statements are buffered locally and sent to Spanner when runBatch() is
 * called. Running a {@link DdlBatch} is not an atomic operation. If the execution fails, then some
 * (possibly empty) prefix of the statements in the batch have been successfully applied to the
 * database, and the others have not. Note that the statements that succeed may not all happen at
 * the same time, but they will always happen in order.
 */
class DdlBatch extends AbstractBaseUnitOfWork {
  private final DdlClient ddlClient;
  private final DatabaseClient dbClient;
  private final List<String> statements = new ArrayList<>();
  private UnitOfWorkState state = UnitOfWorkState.STARTED;

  static class Builder extends AbstractBaseUnitOfWork.Builder<Builder, DdlBatch> {
    private DdlClient ddlClient;
    private DatabaseClient dbClient;

    private Builder() {}

    Builder setDdlClient(DdlClient client) {
      Preconditions.checkNotNull(client);
      this.ddlClient = client;
      return this;
    }

    Builder setDatabaseClient(DatabaseClient client) {
      Preconditions.checkNotNull(client);
      this.dbClient = client;
      return this;
    }

    @Override
    DdlBatch build() {
      Preconditions.checkState(ddlClient != null, "No DdlClient specified");
      Preconditions.checkState(dbClient != null, "No DbClient specified");
      return new DdlBatch(this);
    }
  }

  static Builder newBuilder() {
    return new Builder();
  }

  private DdlBatch(Builder builder) {
    super(builder);
    this.ddlClient = builder.ddlClient;
    this.dbClient = builder.dbClient;
  }

  @Override
  public Type getType() {
    return Type.BATCH;
  }

  @Override
  public UnitOfWorkState getState() {
    return this.state;
  }

  @Override
  public boolean isActive() {
    return getState().isActive();
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public ResultSet executeQuery(
      final ParsedStatement statement, AnalyzeMode analyzeMode, QueryOption... options) {
    final QueryOption[] internalOptions = verifyQueryForDdlBatch(statement, analyzeMode, options);
    Callable<ResultSet> callable =
        new Callable<ResultSet>() {
          @Override
          public ResultSet call() throws Exception {
            return DirectExecuteResultSet.ofResultSet(
                dbClient.singleUse().executeQuery(statement.getStatement(), internalOptions));
          }
        };
    return asyncExecuteStatement(statement, callable);
  }

  @Override
  public AsyncResultSet executeQueryAsync(
      final ParsedStatement statement, AnalyzeMode analyzeMode, QueryOption... options) {
    final QueryOption[] internalOptions = verifyQueryForDdlBatch(statement, analyzeMode, options);
    return dbClient.singleUse().executeQueryAsync(statement.getStatement(), internalOptions);
  }

  private QueryOption[] verifyQueryForDdlBatch(
      ParsedStatement statement, AnalyzeMode analyzeMode, QueryOption... options) {
    if (options != null) {
      for (int i = 0; i < options.length; i++) {
        if (options[i] instanceof InternalMetadataQuery) {
          Preconditions.checkNotNull(statement);
          Preconditions.checkArgument(statement.isQuery(), "Statement is not a query");
          Preconditions.checkArgument(
              analyzeMode == AnalyzeMode.NONE, "Analyze is not allowed for DDL batch");
          // Queries marked with internal metadata queries are allowed during a DDL batch.
          // These can only be generated by library internal methods and may be used to check
          // whether a database object such as table or an index exists.
          return ArrayUtils.remove(options, i);
        }
      }
    }
    // Queries are by default not allowed on DDL batches.
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Executing queries is not allowed for DDL batches.");
  }

  @Override
  public Timestamp getReadTimestamp() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "There is no read timestamp available for DDL batches.");
  }

  @Override
  public Timestamp getReadTimestampOrNull() {
    return null;
  }

  @Override
  public Timestamp getCommitTimestamp() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "There is no commit timestamp available for DDL batches.");
  }

  @Override
  public Timestamp getCommitTimestampOrNull() {
    return null;
  }

  @Override
  public void executeDdl(ParsedStatement ddl) {
    ConnectionPreconditions.checkState(
        state == UnitOfWorkState.STARTED,
        "The batch is no longer active and cannot be used for further statements");
    Preconditions.checkArgument(
        ddl.getType() == StatementType.DDL,
        "Only DDL statements are allowed. \""
            + ddl.getSqlWithoutComments()
            + "\" is not a DDL-statement.");
    statements.add(ddl.getSqlWithoutComments());
  }

  @Override
  public long executeUpdate(ParsedStatement update) {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Executing updates is not allowed for DDL batches.");
  }

  @Override
  public long[] executeBatchUpdate(Iterable<ParsedStatement> updates) {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Executing batch updates is not allowed for DDL batches.");
  }

  @Override
  public void write(Mutation mutation) {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Writing mutations is not allowed for DDL batches.");
  }

  @Override
  public void write(Iterable<Mutation> mutations) {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Writing mutations is not allowed for DDL batches.");
  }

  /**
   * Create a {@link ParsedStatement} that we can use as input for the generic execute method when
   * the {@link #runBatch()} method is executed. This method uses the generic execute method that
   * allows statements to be cancelled and to timeout, which requires the input to be a {@link
   * ParsedStatement}.
   */
  private static final ParsedStatement RUN_BATCH =
      StatementParser.INSTANCE.parse(Statement.of("RUN BATCH"));

  @Override
  public long[] runBatch() {
    ConnectionPreconditions.checkState(
        state == UnitOfWorkState.STARTED, "The batch is no longer active and cannot be ran");
    try {
      if (!statements.isEmpty()) {
        // create a statement that can be passed in to the execute method
        Callable<UpdateDatabaseDdlMetadata> callable =
            new Callable<UpdateDatabaseDdlMetadata>() {
              @Override
              public UpdateDatabaseDdlMetadata call() throws Exception {
                OperationFuture<Void, UpdateDatabaseDdlMetadata> operation =
                    ddlClient.executeDdl(statements);
                try {
                  // Wait until the operation has finished.
                  operation.get();
                  // Return metadata.
                  return operation.getMetadata().get();
                } catch (ExecutionException e) {
                  SpannerException spannerException = extractSpannerCause(e);
                  long[] updateCounts = extractUpdateCounts(operation.getMetadata().get());
                  throw SpannerExceptionFactory.newSpannerBatchUpdateException(
                      spannerException == null
                          ? ErrorCode.UNKNOWN
                          : spannerException.getErrorCode(),
                      e.getMessage(),
                      updateCounts);
                } catch (InterruptedException e) {
                  long[] updateCounts = extractUpdateCounts(operation.getMetadata().get());
                  throw SpannerExceptionFactory.newSpannerBatchUpdateException(
                      ErrorCode.CANCELLED, e.getMessage(), updateCounts);
                }
              }
            };
        asyncExecuteStatement(RUN_BATCH, callable);
      }
      this.state = UnitOfWorkState.RAN;
      long[] updateCounts = new long[statements.size()];
      Arrays.fill(updateCounts, 1L);
      return updateCounts;
    } catch (SpannerException e) {
      this.state = UnitOfWorkState.RUN_FAILED;
      throw e;
    }
  }

  private SpannerException extractSpannerCause(ExecutionException e) {
    Throwable cause = e.getCause();
    Set<Throwable> causes = new HashSet<>();
    while (cause != null && !causes.contains(cause)) {
      if (cause instanceof SpannerException) {
        return (SpannerException) cause;
      }
      causes.add(cause);
      cause = cause.getCause();
    }
    return null;
  }

  @VisibleForTesting
  long[] extractUpdateCounts(UpdateDatabaseDdlMetadata metadata) {
    long[] updateCounts = new long[metadata.getStatementsCount()];
    for (int i = 0; i < updateCounts.length; i++) {
      if (metadata.getCommitTimestampsCount() > i && metadata.getCommitTimestamps(i) != null) {
        updateCounts[i] = 1L;
      } else {
        updateCounts[i] = 0L;
      }
    }
    return updateCounts;
  }

  @Override
  public void abortBatch() {
    ConnectionPreconditions.checkState(
        state == UnitOfWorkState.STARTED, "The batch is no longer active and cannot be aborted.");
    this.state = UnitOfWorkState.ABORTED;
  }

  @Override
  public void commit() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Commit is not allowed for DDL batches.");
  }

  @Override
  public void rollback() {
    throw SpannerExceptionFactory.newSpannerException(
        ErrorCode.FAILED_PRECONDITION, "Rollback is not allowed for DDL batches.");
  }
}
