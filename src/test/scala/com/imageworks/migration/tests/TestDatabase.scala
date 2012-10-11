/*
 * Copyright (c) 2011 Sony Pictures Imageworks Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the
 * distribution.  Neither the name of Sony Pictures Imageworks nor the
 * names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.imageworks.migration.tests

import com.imageworks.migration.{AutoCommit,
                                 ConnectionBuilder,
                                 DatabaseAdapter,
                                 DerbyDatabaseAdapter,
                                 PostgresqlDatabaseAdapter,
                                 With}

import java.sql.DriverManager
import scala.Some

/**
 * Sealed trait abstracting the database to use for testing.
 */
sealed trait TestDatabase
{
  /**
   * Get the schema name the tests are being run in.
   */
  def getSchemaName: String

  /**
   * Get the username of the admin account.
   */
  def getAdminAccountName: String

  /**
   * Get a connection builder that builds connections with access to
   * the entire schema.
   */
  def getAdminConnectionBuilder: ConnectionBuilder

  /**
   * Get the username of the user account.
   */
  def getUserAccountName: String

  /**
   * Get a connection builder that builds connections that connect as
   * a user with restricted privileges.
   */
  def getUserConnectionBuilder: ConnectionBuilder

  /**
   * The DatabaseAdapter to use for the test database.
   */
  def getDatabaseAdapter: DatabaseAdapter

  /**
   * Set up anything before tests are run.
   */
  def setUp(): Unit

  /**
   * Tear down anything that was set up before tests were run.
   */
  def tearDown(): Unit
}

sealed trait BaseTestDatabase
  extends TestDatabase
{
  // Username of the admin account, which will be the owner of the
  // database.
  private
  val admin_username = "admin"

  override
  def getAdminAccountName = admin_username

  // Password for the admin account.
  private
  val admin_password = "foobar"

  def getAdminPassword: String = admin_password

  // Username of the user account.
  private
  val user_username = "user"

  override
  def getUserAccountName = user_username

  // Password for the user account.
  private
  val user_password = "baz"

  def getUserPassword: String = user_password

  // The base JDBC URL.
  def getUrl: String

  override
  def getSchemaName: String =
  {
    admin_username
  }

  override
  def getAdminConnectionBuilder: ConnectionBuilder =
  {
    new ConnectionBuilder(getUrl, admin_username, admin_password)
  }

  override
  def getUserConnectionBuilder: ConnectionBuilder =
  {
    new ConnectionBuilder(getUrl, user_username, user_password)
  }
}

/**
 * Derby test database implementation.
 */
object DerbyTestDatabase
  extends BaseTestDatabase
{
  private val url = "jdbc:derby:" + System.currentTimeMillis.toString

  override
  def getUrl = url

  def setUp() {
    // Set the Derby system home directory to "target/test-databases" so
    // the derby.log file and all databases will be placed in there.
    System.getProperties.setProperty("derby.system.home",
                                     "target/test-databases")

    // Load the Derby database driver.
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver")

    // Create the database,  set it up for connection and SQL
    // authorization and then shut it down, so the next connection will
    // "boot" it with connection and SQL authorizations enabled.

    // Create the database.
    With.connection(DriverManager.getConnection(getUrl + ";create=true",
                                                getAdminAccountName,
                                                getAdminPassword)) { c =>
      TestDatabase.execute(
        getAdminConnectionBuilder,
        """CALL SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY(
             'derby.connection.requireAuthentication', 'true')""")

      // Setting this property cannot be undone.  See
      // http://db.apache.org/derby/docs/10.7/ref/rrefpropersqlauth.html .
      TestDatabase.execute(
        getAdminConnectionBuilder,
        """CALL SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY(
             'derby.database.sqlAuthorization', 'true')""")

      TestDatabase.execute(
        getAdminConnectionBuilder,
        """CALL SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY(
             'derby.authentication.provider', 'BUILTIN')""")

      TestDatabase.execute(
        getAdminConnectionBuilder,
        """CALL SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY(
             'derby.user.""" + getAdminAccountName + """', '""" + getAdminPassword + """')""")

      TestDatabase.execute(
        getAdminConnectionBuilder,
          """CALL SYSCS_UTIL.SYSCS_SET_DATABASE_PROPERTY(
               'derby.user.""" + getUserAccountName + """', '""" + getUserPassword + """')""")
    }

    // Shutdown Derby.
    try {
      With.connection(DriverManager.getConnection(getUrl + ";shutdown=true",
                                                  getAdminAccountName,
                                                  getAdminPassword)) { _ =>
      }
    }
    catch {
      // For JDBC3 (JDK 1.5)
      case e: org.apache.derby.impl.jdbc.EmbedSQLException =>

      // For JDBC4 (JDK 1.6), a
      // java.sql.SQLNonTransientConnectionException is thrown, but this
      // exception class does not exist in JDK 1.5, so catch a
      // java.sql.SQLException instead.
      case e: java.sql.SQLException =>
    }
  }

  def tearDown() {
    // Nothing to do.
  }

  override
  def getDatabaseAdapter: DatabaseAdapter =
  {
    new DerbyDatabaseAdapter(Some(getSchemaName))
  }
}

/**
 * Postgresql test database implementation.
 */
object PostgresqlTestDatabase
  extends BaseTestDatabase
{
  private val dbName = "scala-migrations-tests"
  private val baseUrl = "jdbc:postgresql://localhost:5432/"
  private val url = baseUrl + dbName
  private val initUrl = baseUrl + "postgres"

  private def getInitConnectionBuilder =
    new ConnectionBuilder(initUrl, getAdminAccountName, getAdminPassword)

  override
  def getUrl = url

  def setUp() {
    // Load the Postgresql database driver.
    Class.forName("org.postgresql.Driver")

    // Drop any currently existing objects if they already exist.
    tearDown()

    // Create the database.
    TestDatabase.execute(
      getInitConnectionBuilder,
      "CREATE DATABASE \"" + dbName + "\" OWNER " + getAdminAccountName)

    // Create the user.
    TestDatabase.execute(
      getInitConnectionBuilder,
      "CREATE USER \"" + getUserAccountName + "\" WITH PASSWORD '" + getUserPassword + "'")

    // Create the schema.
    TestDatabase.execute(
      getAdminConnectionBuilder,
      "CREATE SCHEMA \"" + getAdminAccountName + "\"")
  }

  def tearDown() {
    // Drop the database.
    TestDatabase.execute(
      getInitConnectionBuilder,
      "DROP DATABASE IF EXISTS \"" + dbName + "\"")

    // Drop the user.
    TestDatabase.execute(
      getInitConnectionBuilder,
      "DROP ROLE IF EXISTS \"" + getUserAccountName + "\"")
  }

  override
  def getDatabaseAdapter: DatabaseAdapter =
  {
    new PostgresqlDatabaseAdapter(Some(getSchemaName))
  }
}

/**
 * Object which builds the correct TestDatabase according to the
 * system property "scala-migrations.db.vendor", defaulting to Derby if
 * the property is not set.
 */
object TestDatabase
  extends TestDatabase
{
  private
  val db: TestDatabase =
  {
    System.getProperty("scala-migrations.db.vendor", "derby") match {
      case "derby" => {
        DerbyTestDatabase
      }
      case "postgresql" => {
        PostgresqlTestDatabase
      }
      case v => {
        val m = "Unexpected value for scala-migrations.db.vendor property: " +
                v
        throw new RuntimeException(m)
      }
    }
  }

  override def getSchemaName = db.getSchemaName

  override def getAdminAccountName = db.getAdminAccountName

  override def getAdminConnectionBuilder = db.getAdminConnectionBuilder

  override def getUserAccountName = db.getUserAccountName

  override def getUserConnectionBuilder = db.getUserConnectionBuilder

  override def getDatabaseAdapter = db.getDatabaseAdapter

  override def setUp(): Unit = db.setUp()

  override def tearDown(): Unit = db.tearDown()

  def execute(connection_builder: ConnectionBuilder,
              sql: String): Boolean =
  {
    connection_builder.withConnection(AutoCommit) { c =>
      With.statement(c.prepareStatement(sql)) { s =>
        s.execute()
      }
    }
  }
}
