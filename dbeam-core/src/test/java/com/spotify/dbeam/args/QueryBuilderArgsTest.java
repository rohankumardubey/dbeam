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

package com.spotify.dbeam.args;

import com.google.common.collect.Lists;
import com.spotify.dbeam.DbTestHelper;
import com.spotify.dbeam.TestHelper;
import com.spotify.dbeam.options.JdbcExportArgsFactory;
import com.spotify.dbeam.options.JdbcExportPipelineOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;


public class QueryBuilderArgsTest {

  private static Path TEST_DIR = TestHelper.createTmpDirName("jdbc-export-args-test");
  private static Path COFFEES_SQL_QUERY_PATH =
      Paths.get(TEST_DIR.toString(), "coffees_query_1.sql");
  private static String CONNECTION_URL =
      "jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
  private static Connection CONNECTION;


  @BeforeClass
  public static void beforeAll() throws SQLException, ClassNotFoundException, IOException {
    CONNECTION =  DbTestHelper.createConnection(CONNECTION_URL);
    DbTestHelper.createFixtures(CONNECTION_URL);
    Files.createDirectories(TEST_DIR);
    Files.write(COFFEES_SQL_QUERY_PATH,
                "SELECT * FROM COFFEES WHERE SIZE > 10".getBytes(StandardCharsets.UTF_8));
  }

  @AfterClass
  public static void afterAll() throws IOException, SQLException {
    Files.delete(COFFEES_SQL_QUERY_PATH);
    Files.walk(TEST_DIR)
        .sorted(Comparator.reverseOrder())
        .forEach(p -> p.toFile().delete());
    CONNECTION.close();
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldFailOnNullTableName() {
    QueryBuilderArgs.create(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldFailOnInvalidTableName() {
    QueryBuilderArgs.create("*invalid#name@!");
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldFailOnTableNameWithDots() {
    QueryBuilderArgs.create("foo.bar");
  }

  @Test
  public void shouldCreateValidSqlQueryFromUserQuery() {
    QueryBuilderArgs args =
        QueryBuilderArgs.create("some_table", "SELECT * FROM some_table");

    Assert.assertEquals("some_table", args.tableName());
    Assert.assertEquals(
        "SELECT * FROM (SELECT * FROM some_table) WHERE 1=1",
        args.baseSqlQuery().build()
    );
  }

  @Test
  public void shouldConfigureLimit() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--limit=7");

    Assert.assertEquals(
        Lists.newArrayList("SELECT * FROM some_table WHERE 1=1 LIMIT 7"),
        actual.buildQueries(null));
  }

  @Test
  public void shouldConfigurePartition() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-07-31");

    Assert.assertEquals(
        Optional.of(Instant.parse("2027-07-31T00:00:00Z")),
        actual.partition()
    );
    Assert.assertEquals(
        Lists.newArrayList("SELECT * FROM some_table WHERE 1=1"),
        actual.buildQueries(null));
  }

  @Test
  public void shouldConfigurePartitionForFullIsoString() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-07-31T13:37:59Z");

    Assert.assertEquals(
        Optional.of(Instant.parse("2027-07-31T13:37:59Z")),
        actual.partition()
    );
  }

  @Test
  public void shouldConfigurePartitionForMonthlySchedule() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-05");

    Assert.assertEquals(
        Optional.of(Instant.parse("2027-05-01T00:00:00Z")),
        actual.partition()
    );
  }

  @Test
  public void shouldConfigurePartitionForHourlySchedule() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-05-02T23");

    Assert.assertEquals(
        Optional.of(Instant.parse("2027-05-02T23:00:00Z")),
        actual.partition()
    );
  }

  @Test
  public void shouldConfigurePartitionColumn() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-07-31 --partitionColumn=col");

    Assert.assertEquals(
        Lists.newArrayList("SELECT * FROM some_table WHERE 1=1 "
                           + "AND col >= '2027-07-31' AND col < '2027-08-01'"),
        actual.buildQueries(null));
  }

  @Test
  public void shouldConfigurePartitionColumnAndLimit() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-07-31 --partitionColumn=col --limit=5");

    Assert.assertEquals(
        Lists.newArrayList("SELECT * FROM some_table WHERE 1=1 "
                           + "AND col >= '2027-07-31' AND col < '2027-08-01' LIMIT 5"),
        actual.buildQueries(null));
  }

  @Test
  public void shouldConfigurePartitionColumnAndPartitionPeriod() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--partition=2027-07-31 --partitionColumn=col --partitionPeriod=P1M");

    Assert.assertEquals(
        Lists.newArrayList("SELECT * FROM some_table WHERE 1=1 "
                           + "AND col >= '2027-07-31' AND col < '2027-08-31'"),
        actual.buildQueries(null));
  }

  // tests for --sqlFile parameter

  @Test
  public void shouldConfigureLimitForSqlFile() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(String.format(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--sqlFile=%s --limit=7", COFFEES_SQL_QUERY_PATH.toString()));

    Assert.assertEquals(Lists.newArrayList(
        "SELECT * FROM (SELECT * FROM COFFEES WHERE SIZE > 10) WHERE 1=1 LIMIT 7"),
                        actual.buildQueries(null));
  }

  @Test
  public void shouldConfigurePartitionColumnAndLimitForSqlFile() throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(String.format(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--sqlFile=%s --partition=2027-07-31 --partitionColumn=col --limit=7",
        COFFEES_SQL_QUERY_PATH.toString()));

    Assert.assertEquals(Lists.newArrayList(
        "SELECT * FROM (SELECT * FROM COFFEES WHERE SIZE > 10) WHERE 1=1 "
        + "AND col >= '2027-07-31' AND col < '2027-08-01' LIMIT 7"),
                        actual.buildQueries(null));
  }

  @Test
  public void shouldConfigurePartitionColumnAndPartitionPeriodForSqlFile()
      throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(String.format(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--sqlFile=%s --partition=2027-07-31 --partitionColumn=col --partitionPeriod=P1M",
        COFFEES_SQL_QUERY_PATH.toString()));

    Assert.assertEquals(Lists.newArrayList(
        "SELECT * FROM (SELECT * FROM COFFEES WHERE SIZE > 10) WHERE 1=1 "
        + "AND col >= '2027-07-31' AND col < '2027-08-31'"),
                        actual.buildQueries(null));
  }

  // tests for --queryParallelism
  @Test
  public void shouldCreateParallelQueries()
      throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(
        "--connectionUrl=jdbc:postgresql://some_db --table=COFFEES "
        + "--splitColumn=ROWNUM --queryParallelism=5");

    Assert.assertEquals(Lists.newArrayList(
        "SELECT * FROM COFFEES WHERE 1=1"
        + " AND ROWNUM >= 1 AND ROWNUM <= 2"),
                        actual.buildQueries(CONNECTION));
  }

  @Test
  public void shouldCreateParallelQueriesWithSqlFile()
      throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(String.format(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--sqlFile=%s --splitColumn=ROWNUM --queryParallelism=5",
        COFFEES_SQL_QUERY_PATH.toString()));

    Assert.assertEquals(Lists.newArrayList(
        "SELECT * FROM (SELECT * FROM COFFEES WHERE SIZE > 10) WHERE 1=1"
        + " AND ROWNUM >= 1 AND ROWNUM <= 2"),
                        actual.buildQueries(CONNECTION));
  }

  @Test
  public void shouldCreateParallelQueriesWithPartitionColumn()
      throws IOException, SQLException {
    QueryBuilderArgs actual = pareOptions(String.format(
        "--connectionUrl=jdbc:postgresql://some_db --table=some_table "
        + "--sqlFile=%s --partition=2027-07-31 "
        + "--partitionColumn=col --partitionPeriod=P1M --limit=7",
        COFFEES_SQL_QUERY_PATH.toString()));

    Assert.assertEquals(Lists.newArrayList(
        "SELECT * FROM (SELECT * FROM COFFEES WHERE SIZE > 10) WHERE 1=1"
        + " AND col >= '2027-07-31' AND col < '2027-08-31' LIMIT 7"),
                        actual.buildQueries(CONNECTION));
  }

  private QueryBuilderArgs pareOptions(String cmdLineArgs) throws IOException {
    JdbcExportPipelineOptions opts = commandLineToOptions(cmdLineArgs);
    return JdbcExportArgsFactory.createQueryArgs(opts);
  }

  public static JdbcExportPipelineOptions commandLineToOptions(final String cmdLineArgs) {
    PipelineOptionsFactory.register(JdbcExportPipelineOptions.class);
    return PipelineOptionsFactory.fromArgs(cmdLineArgs.split(" "))
        .withValidation()
        .create()
        .as(JdbcExportPipelineOptions.class);
  }

}
