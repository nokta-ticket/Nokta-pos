package com.nokta.pos.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.nokta.pos.data.local.dao.MenuDao
import com.nokta.pos.data.local.dao.OutboxDao
import com.nokta.pos.data.local.dao.TabDao
import com.nokta.pos.data.local.entity.MenuCategoryEntity
import com.nokta.pos.data.local.entity.MenuEntity
import com.nokta.pos.data.local.entity.MenuProductEntity
import com.nokta.pos.data.local.entity.MenuVariantEntity
import com.nokta.pos.data.local.entity.ModifierGroupEntity
import com.nokta.pos.data.local.entity.ModifierOptionEntity
import com.nokta.pos.data.local.entity.OutboxEntity
import com.nokta.pos.data.local.entity.OutboxOperationType
import com.nokta.pos.data.local.entity.OutboxStatus
import com.nokta.pos.data.local.entity.SyncState
import com.nokta.pos.data.local.entity.TabEntity
import com.nokta.pos.data.local.entity.TabItemEntity
import com.nokta.pos.data.local.entity.TabOrderEntity
import com.nokta.pos.data.local.entity.TabPaymentEntity
import com.nokta.pos.data.local.entity.VenueTableEntity

class EnumConverters {
    @TypeConverter fun fromSyncState(v: SyncState): String = v.name
    @TypeConverter fun toSyncState(v: String): SyncState = SyncState.valueOf(v)

    @TypeConverter fun fromOutboxType(v: OutboxOperationType): String = v.name
    @TypeConverter fun toOutboxType(v: String): OutboxOperationType = OutboxOperationType.valueOf(v)

    @TypeConverter fun fromOutboxStatus(v: OutboxStatus): String = v.name
    @TypeConverter fun toOutboxStatus(v: String): OutboxStatus = OutboxStatus.valueOf(v)
}

/**
 * Banco operacional do POS — a fonte de verdade que a UI lê. A API deixa de
 * ser algo que a UI consulta diretamente; ela só alimenta este banco (via
 * repositories) e o `SyncEngine` esvazia a fila de volta pra API.
 *
 * Versão 1: primeira versão do banco, projeto ainda não publicado em
 * produção com Room — sem necessidade de `Migration`, `fallbackToDestructiveMigration`
 * nunca vai apagar dado real de campo.
 */
@Database(
    entities = [
        MenuEntity::class,
        MenuCategoryEntity::class,
        MenuProductEntity::class,
        MenuVariantEntity::class,
        ModifierGroupEntity::class,
        ModifierOptionEntity::class,
        TabEntity::class,
        TabItemEntity::class,
        TabOrderEntity::class,
        TabPaymentEntity::class,
        VenueTableEntity::class,
        OutboxEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(EnumConverters::class)
abstract class NoktaDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun tabDao(): TabDao
    abstract fun outboxDao(): OutboxDao
}
