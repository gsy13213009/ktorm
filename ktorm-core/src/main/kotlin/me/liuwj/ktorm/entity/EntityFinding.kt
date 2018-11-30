package me.liuwj.ktorm.entity

import me.liuwj.ktorm.dsl.*
import me.liuwj.ktorm.expression.BinaryExpression
import me.liuwj.ktorm.expression.BinaryExpressionType
import me.liuwj.ktorm.expression.ScalarExpression
import me.liuwj.ktorm.expression.SqlExpression
import me.liuwj.ktorm.schema.*
import kotlin.reflect.KClass

/**
 * 根据 ID 批量获取实体对象，会自动 left join 所有的引用表
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>, K : Any> Table<E>.findMapByIds(ids: Collection<K>): Map<K, E> {
    return findListByIds(ids).associateBy { it.getPrimaryKeyValue(this) as K }
}

/**
 * 根据 ID 批量获取实体对象，会自动 left join 所有的引用表
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.findListByIds(ids: Collection<Any>): List<E> {
    if (ids.isEmpty()) {
        return kotlin.collections.emptyList()
    } else {
        val primaryKey = (this.primaryKey as? Column<Any>) ?: kotlin.error("Table $tableName dosen't have a primary key.")
        return findList { primaryKey inList ids }
    }
}

/**
 * 根据 ID 获取对象，会自动 left join 所有的引用表
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.findById(id: Any): E? {
    val primaryKey = (this.primaryKey as? Column<Any>) ?: kotlin.error("Table $tableName dosen't have a primary key.")
    return findOne { primaryKey eq id }
}

/**
 * 根据指定条件获取对象，会自动 left join 所有的引用表
 */
fun <E : Entity<E>, T : Table<E>> T.findOne(block: (T) -> ScalarExpression<Boolean>): E? {
    val list = findList(block)
    when (list.size) {
        0 -> return null
        1 -> return list[0]
        else -> throw IllegalStateException("Expected one result(or null) to be returned by findOne(), but found: ${list.size}")
    }
}

/**
 * 获取表中的所有记录，会自动 left join 所有的引用表
 */
fun <E : Entity<E>> Table<E>.findAll(): List<E> {
    return this
        .joinReferencesAndSelect()
        .map { row -> this.createEntity(row) }
}

/**
 * 根据指定条件获取对象列表，会自动 left join 所有的引用表
 */
fun <E : Entity<E>, T : Table<E>> T.findList(block: (T) -> ScalarExpression<Boolean>): List<E> {
    return this
        .joinReferencesAndSelect()
        .where { block(this) }
        .map { row -> this.createEntity(row) }
}

/**
 * 返回一个查询对象 [Query]，left join 所有引用表，select 所有字段
 */
fun Table<*>.joinReferencesAndSelect(): Query {
    val joinedTables = ArrayList<Table<*>>()

    return this
        .joinReferences(this.asExpression(), joinedTables)
        .select(joinedTables.flatMap { it.columns })
}

private fun Table<*>.joinReferences(
    expr: SqlExpression,
    joinedTables: MutableList<Table<*>>
): SqlExpression {

    var curr = expr

    joinedTables += this

    for (column in columns) {
        val binding = column.binding
        if (binding is ReferenceBinding) {
            val rightTable = binding.referenceTable
            val primaryKey = rightTable.primaryKey ?: kotlin.error("Table ${rightTable.tableName} dosen't have a primary key.")

            curr = curr.leftJoin(rightTable, on = column eq primaryKey)
            curr = rightTable.joinReferences(curr, joinedTables)
        }
    }

    return curr
}

private infix fun ColumnDeclaring<*>.eq(column: ColumnDeclaring<*>): BinaryExpression<Boolean> {
    return BinaryExpression(BinaryExpressionType.EQUAL, asExpression(), column.asExpression(), BooleanSqlType)
}

/**
 * 从结果集中创建实体对象，会自动级联创建引用表的实体对象
 */
@Suppress("UNCHECKED_CAST")
fun <E : Entity<E>> Table<E>.createEntity(row: QueryRowSet): E {
    return doCreateEntity(row) as E
}

private fun Table<*>.doCreateEntity(row: QueryRowSet): Entity<*> {
    val entityClass = this.entityClass ?: kotlin.error("No entity class configured for table: $tableName")
    val entity = Entity.create(entityClass, this, null)

    for (column in columns) {
        try {
            retrieveColumn(row, column, intoEntity = entity)
        } catch (e: Throwable) {
            throw IllegalStateException("Error occur while retrieving column: $column, binding: ${column.binding}", e)
        }
    }

    return entity.apply { discardChanges() }
}

private fun Table<*>.retrieveColumn(row: QueryRowSet, column: Column<*>, intoEntity: Entity<*>) {
    val binding = column.binding?.takeIf { row.hasColumn(column) } ?: return
    when (binding) {
        is SimpleBinding -> {
            intoEntity[binding.property.name] = row[column]
        }
        is NestedBinding -> {
            var child = intoEntity[binding.property1.name] as Entity<*>?
            if (child == null) {
                child = Entity.create(binding.property1.returnType.classifier as KClass<*>, this, binding.property1.name)
                intoEntity[binding.property1.name] = child
            }

            child[binding.property2.name] = row[column]
            child.discardChanges()
        }
        is ReferenceBinding -> {
            val child = binding.referenceTable.doCreateEntity(row)
            if (child.getPrimaryKeyValue(binding.referenceTable) != null) {
                intoEntity[binding.onProperty.name] = child
            }
        }
    }
}
