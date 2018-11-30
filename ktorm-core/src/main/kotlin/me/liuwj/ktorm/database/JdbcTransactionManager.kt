package me.liuwj.ktorm.database

import org.slf4j.LoggerFactory
import java.sql.Connection

class JdbcTransactionManager(val connector: () -> Connection) : TransactionManager {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val threadLocal = ThreadLocal<Transaction>()

    override val defaultIsolation = TransactionIsolation.REPEATABLE_READ

    override val currentTransaction: Transaction? get() = threadLocal.get()

    override fun newTransaction(isolation: TransactionIsolation): Transaction {
        if (currentTransaction != null) {
            throw IllegalStateException("Current thread is already in a transaction.")
        }

        return JdbcTransaction(isolation).apply { threadLocal.set(this) }
    }

    override fun newConnection(): Connection {
        return connector.invoke()
    }

    override fun <T> transactional(func: () -> T): T {
        val current = currentTransaction
        val isOuter = current == null
        val transaction = current ?: newTransaction()

        try {
            val result = func()
            if (isOuter) transaction.commit()
            return result

        } catch (e: Throwable) {
            if (isOuter) transaction.rollback()
            throw e

        } finally {
            if (isOuter) transaction.close()
        }
    }

    private inner class JdbcTransaction(private val desiredIsolation: TransactionIsolation) : Transaction {
        private var originIsolation = defaultIsolation.level
        private var originAutoCommit = true

        private val connectionLazy = lazy(LazyThreadSafetyMode.NONE) {
            newConnection().apply {
                try {
                    originIsolation = transactionIsolation
                    if (originIsolation != desiredIsolation.level) {
                        transactionIsolation = desiredIsolation.level
                    }

                    originAutoCommit = autoCommit
                    if (originAutoCommit) {
                        autoCommit = false
                    }
                } catch (e: Throwable) {
                    closeSilently()
                    throw e
                }
            }
        }

        override val connection: Connection by connectionLazy

        override fun commit() {
            if (connectionLazy.isInitialized()) {
                connection.commit()
            }
        }

        override fun rollback() {
            if (connectionLazy.isInitialized() && !connection.isClosed) {
                connection.rollback()
            }
        }

        override fun close() {
            try {
                if (connectionLazy.isInitialized() && !connection.isClosed) {
                    connection.closeSilently()
                }
            } finally {
                threadLocal.remove()
            }
        }

        private fun Connection.closeSilently() {
            try {
                if (originIsolation != desiredIsolation.level) {
                    transactionIsolation = originIsolation
                }
                if (originAutoCommit) {
                    autoCommit = true
                }
            } catch (e: Throwable) {
                logger.error("Error closing connection $this", e)
            } finally {
                try {
                    close()
                } catch (e: Throwable) {
                    logger.error("Error closing connection $this", e)
                }
            }
        }
    }
}