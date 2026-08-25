package com.nokta.pos.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/**
 * Cardápio persistido em tabelas reais (Room), não mais um JSON único em
 * DataStore. A diferença que importa para offline-first: com tabelas, a UI
 * lê direto do banco local sempre — nunca "se tiver cache, desserializa o
 * blob inteiro; senão, espera rede" (era assim antes). O fluxo de escrita
 * é sempre API → Room → UI (via Flow), nunca API → UI direto.
 *
 * As relações são montadas com queries explícitas no DAO (não `@Relation`
 * aninhado de 3 níveis: Room não suporta bem `@Relation` cujo tipo de
 * retorno é outro POJO que também tem `@Relation` dentro — funciona melhor
 * como consultas separadas compostas no repository).
 */
@Entity(tableName = "menu")
data class MenuEntity(
    @PrimaryKey val menuId: Long,
    val organizationId: Long,
    val nome: String,
    /** Quando este cardápio foi baixado por último — decide o aviso "dados de Xmin atrás". */
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "menu_category",
    foreignKeys = [
        ForeignKey(
            entity = MenuEntity::class,
            parentColumns = ["menuId"],
            childColumns = ["menuId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("menuId")],
)
data class MenuCategoryEntity(
    @PrimaryKey val categoryId: Long,
    val menuId: Long,
    val nome: String,
    val displayOrder: Int,
)

@Entity(
    tableName = "menu_product",
    foreignKeys = [
        ForeignKey(
            entity = MenuCategoryEntity::class,
            parentColumns = ["categoryId"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId"), Index("menuId")],
)
data class MenuProductEntity(
    /** menuItemId — o vínculo produto↔cardápio, mesma chave usada em CreateOrderItemRequest. */
    @PrimaryKey val menuItemId: Long,
    val categoryId: Long,
    /** Denormalizado a partir da categoria — evita join só para filtrar produtos de um cardápio inteiro. */
    val menuId: Long,
    val productId: Long,
    val nome: String,
    val descricao: String?,
    val imageUrl: String?,
    val available: Boolean,
    val displayOrder: Int,
)

@Entity(
    tableName = "menu_variant",
    foreignKeys = [
        ForeignKey(
            entity = MenuProductEntity::class,
            parentColumns = ["menuItemId"],
            childColumns = ["menuItemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("menuItemId")],
)
data class MenuVariantEntity(
    @PrimaryKey val variantId: Long,
    val menuItemId: Long,
    val nome: String,
    val priceCents: Long,
)

/**
 * Adicionais de um produto — baixados sob demanda (só quando o operador abre
 * o produto), não fazem parte do preview do cardápio. Guardados por
 * `productId` (não `menuItemId`): é a chave que `GET .../modifier-groups`
 * espera, e um mesmo produto pode estar em mais de um cardápio.
 */
@Entity(tableName = "modifier_group")
data class ModifierGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val modifierGroupId: Long,
    val nome: String,
    val required: Boolean,
    val minSelect: Int,
    val maxSelect: Int?,
    val fetchedAtEpochMs: Long,
)

@Entity(
    tableName = "modifier_option",
    foreignKeys = [
        ForeignKey(
            entity = ModifierGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupRowId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("groupRowId")],
)
data class ModifierOptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupRowId: Long,
    val modifierOptionId: Long,
    val nome: String,
    val priceCents: Long,
)

/** Um produto + suas variantes (1 nível), montado à parte e combinado no repository. */
data class MenuProductWithVariants(
    @Embedded val product: MenuProductEntity,
    @Relation(parentColumn = "menuItemId", entityColumn = "menuItemId")
    val variants: List<MenuVariantEntity>,
)

data class ModifierGroupWithOptions(
    @Embedded val group: ModifierGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupRowId")
    val options: List<ModifierOptionEntity>,
)
