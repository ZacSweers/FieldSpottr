package dev.zacsweers.fieldspottr.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.Companion.IN_MEMORY
import dev.zacsweers.fieldspottr.FSDatabase
import java.sql.SQLException
import org.junit.rules.ExternalResource

class TemporaryDatabase<T>(
  val databaseConstructor: (SqlDriver) -> T,
  val schema: SqlSchema<QueryResult.AsyncValue<Unit>>,
) : ExternalResource() {
  private var driver: SqlDriver? = null
  private var dbBacker: T? = null

  val db: T
    get() {
      dbBacker?.let {
        return it
      }

      val driver =
        try {
          JdbcSqliteDriver(IN_MEMORY)
        } catch (_: SQLException) {
          JdbcSqliteDriver(IN_MEMORY)
        }
      schema.create(driver)
      val instance = databaseConstructor(driver)
      this.dbBacker = instance
      this.driver = driver
      return instance
    }

  override fun after() {
    driver?.close()
    driver = null
  }
}

@Suppress("TestFunctionName") // Emulating a constructor for the default :db database.
@JvmName("create")
fun TemporaryDatabase(): TemporaryDatabase<FSDatabase> {
  return TemporaryDatabase(SqlDriver::createFSDatabase, FSDatabase.Schema)
}
