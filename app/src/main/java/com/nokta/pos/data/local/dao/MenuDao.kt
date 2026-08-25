package com.nokta.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.nokta.pos.data.local.entity.MenuCategoryEntity
import com.nokta.pos.data.local.entity.MenuEntity
import com.nokta.pos.data.local.entity.MenuProductEntity
import com.nokta.pos.data.local.entity.MenuProductWithVariants
import com.nokta.pos.data.local.entity.MenuVariantEntity
import com.nokta.pos.data.local.entity.ModifierGroupEntity
import com.nokta.pos.data.local.entity.ModifierGroupWithOptions
import com.nokta.pos.data.local.entity.ModifierOptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuDao {

    @Query("SELECT * FROM menu WHERE menuId = :menuId")
    fun observeMenu(menuId: Long): Flow<MenuEntity?>

    @Query("SELECT * FROM menu WHERE menuId = :menuId")
    suspend fun getMenu(menuId: Long): MenuEntity?

    @Query("SELECT * FROM menu_category WHERE menuId = :menuId ORDER BY displayOrder ASC")
    fun observeCategories(menuId: Long): Flow<List<MenuCategoryEntity>>

    @Query("SELECT * FROM menu_product WHERE menuId = :menuId ORDER BY categoryId ASC, displayOrder ASC")
    @Transaction
    fun observeProductsWithVariants(menuId: Long): Flow<List<MenuProductWithVariants>>

    @Query("SELECT * FROM menu_product WHERE menuItemId = :menuItemId")
    @Transaction
    suspend fun getProductWithVariants(menuItemId: Long): MenuProductWithVariants?

    /**
     * Substitui o cardápio inteiro numa transação: apaga categorias antigas
     * (o CASCADE cuida de produtos/variantes) e grava a versão nova. Menos
     * código que fazer diff campo a campo, e o cardápio inteiro é pequeno
     * (dezenas de produtos, não milhares) — o custo de reescrever tudo a
     * cada sync é desprezível perto da simplicidade de nunca ter estado
     * parcialmente migrado.
     */
    @Transaction
    suspend fun replaceMenu(
        menu: MenuEntity,
        categories: List<MenuCategoryEntity>,
        products: List<MenuProductEntity>,
        variants: List<MenuVariantEntity>,
    ) {
        upsertMenu(menu)
        deleteCategoriesForMenu(menu.menuId)
        insertCategories(categories)
        insertProducts(products)
        insertVariants(variants)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMenu(menu: MenuEntity)

    @Query("DELETE FROM menu_category WHERE menuId = :menuId")
    suspend fun deleteCategoriesForMenu(menuId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<MenuCategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<MenuProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariants(variants: List<MenuVariantEntity>)

    // ---- Adicionais ----

    @Transaction
    @Query("SELECT * FROM modifier_group WHERE productId = :productId")
    suspend fun getModifierGroups(productId: Long): List<ModifierGroupWithOptions>

    @Transaction
    suspend fun replaceModifierGroups(
        productId: Long,
        groups: List<ModifierGroupEntity>,
        options: Map<Int, List<ModifierOptionEntity>>,
    ) {
        deleteModifierGroupsForProduct(productId)
        groups.forEachIndexed { index, group ->
            val groupRowId = insertModifierGroup(group)
            options[index]?.let { opts ->
                insertModifierOptions(opts.map { it.copy(groupRowId = groupRowId) })
            }
        }
    }

    @Query("DELETE FROM modifier_group WHERE productId = :productId")
    suspend fun deleteModifierGroupsForProduct(productId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModifierGroup(group: ModifierGroupEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModifierOptions(options: List<ModifierOptionEntity>)

    @Delete
    suspend fun deleteModifierGroups(groups: List<ModifierGroupEntity>)
}
