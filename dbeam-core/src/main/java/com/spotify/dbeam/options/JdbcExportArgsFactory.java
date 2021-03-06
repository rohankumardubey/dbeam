/*-
 * -\-\-
 * DBeam Core
 * --
 * Copyright (C) 2016 - 2019 Spotify AB
 * --
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * -/-/-
 */

package com.spotify.dbeam.options;

import static com.google.common.base.Preconditions.checkArgument;

import com.spotify.dbeam.args.JdbcAvroArgs;
import com.spotify.dbeam.args.JdbcConnectionArgs;
import com.spotify.dbeam.args.JdbcExportArgs;
import com.spotify.dbeam.args.QueryBuilderArgs;
import com.spotify.dbeam.avro.BeamJdbcAvroSchema;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Optional;
import org.apache.beam.sdk.options.PipelineOptions;

public class JdbcExportArgsFactory {

  private static DateTimeFormatter INSTANT_PARSER_WITH_ZONE =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("yyyy[-MM][-dd['T'HH[:mm[:ss]]]]")
          .parseDefaulting(ChronoField.MONTH_OF_YEAR, 1)
          .parseDefaulting(ChronoField.DAY_OF_MONTH, 1)
          .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
          .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
          .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
          .optionalStart() // optionally support offset id
          .appendOffsetId()
          .toFormatter()
          .withZone(ZoneId.of("UTC"));

  public static JdbcExportArgs fromPipelineOptions(PipelineOptions options)
      throws ClassNotFoundException, IOException {
    final JdbcExportPipelineOptions exportOptions = options.as(JdbcExportPipelineOptions.class);
    final JdbcAvroArgs jdbcAvroArgs = JdbcAvroArgs.create(
        JdbcConnectionArgs.create(exportOptions.getConnectionUrl())
            .withUsername(exportOptions.getUsername())
            .withPassword(PasswordReader.INSTANCE.readPassword(exportOptions).orElse(null)),
        exportOptions.getFetchSize(),
        exportOptions.getAvroCodec());

    return JdbcExportArgs.create(
        jdbcAvroArgs,
        createQueryArgs(exportOptions),
        exportOptions.getAvroSchemaNamespace(),
        Optional.ofNullable(exportOptions.getAvroDoc()),
        exportOptions.isUseAvroLogicalTypes(),
        Duration.parse(exportOptions.getExportTimeout()),
        BeamJdbcAvroSchema.parseOptionalInputAvroSchemaFile(exportOptions.getAvroSchemaFilePath())
    );
  }

  public static QueryBuilderArgs createQueryArgs(JdbcExportPipelineOptions options)
      throws IOException {
    final Period partitionPeriod = Optional.ofNullable(options.getPartitionPeriod())
        .map(Period::parse).orElse(Period.ofDays(1));
    Optional<Instant> partition = Optional.ofNullable(options.getPartition())
        .map(JdbcExportArgsFactory::parseInstant);
    Optional<String> partitionColumn = Optional.ofNullable(options.getPartitionColumn());
    checkArgument(
        !partitionColumn.isPresent() || partition.isPresent(),
        "To use --partitionColumn the --partition parameter must also be configured");

    if (!(options.isSkipPartitionCheck() || partitionColumn.isPresent())) {
      Instant minPartitionDateTime = Optional.ofNullable(options.getMinPartitionPeriod())
          .map(JdbcExportArgsFactory::parseInstant)
          .orElse(Instant.now().minus(partitionPeriod.multipliedBy(2)));
      partition.map(p -> validatePartition(p, minPartitionDateTime));
    }

    final Optional<String> splitColumn = Optional.ofNullable(options.getSplitColumn());
    final Optional<Integer> queryParallelism = Optional.ofNullable(options.getQueryParallelism());
    checkArgument(queryParallelism.isPresent() == splitColumn.isPresent(),
                  "Either both --queryParallelism and --splitColumn must be present or "
                  + "none of them");
    queryParallelism.ifPresent(p -> checkArgument(
        p > 0,
        "Query Parallelism must be a positive number. Specified queryParallelism was %s", p));

    return createQueryBuilderArgs(options)
        .builder()
        .setLimit(Optional.ofNullable(options.getLimit()))
        .setPartitionColumn(partitionColumn)
        .setPartition(partition)
        .setPartitionPeriod(partitionPeriod)
        .setSplitColumn(splitColumn)
        .setQueryParallelism(queryParallelism)
        .build();
  }

  private static QueryBuilderArgs createQueryBuilderArgs(JdbcExportPipelineOptions options)
      throws IOException {
    if (options.getSqlFile() != null) {
      return QueryBuilderArgs.create(
          options.getTable(), PasswordReader.readFromFile(options.getSqlFile()));
    } else {
      return QueryBuilderArgs.create(options.getTable());
    }
  }

  public static Instant parseInstant(String v) {
    return Instant.from(INSTANT_PARSER_WITH_ZONE.parse(v));
  }

  private static Instant validatePartition(
      Instant partitionDateTime, Instant minPartitionDateTime) {
    checkArgument(
        partitionDateTime.isAfter(minPartitionDateTime),
        "Too old partition date %s. Use a partition date >= %s or use --skip-partition-check",
        partitionDateTime, minPartitionDateTime
    );
    return partitionDateTime;
  }

}
