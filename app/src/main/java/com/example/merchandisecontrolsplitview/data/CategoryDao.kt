package com.example.merchandisecontrolsplitview.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CategoryDao {
    /**
     * Inserisce una nuova categoria. Se una categoria con lo stesso nome esiste già,
     * l'inserimento viene ignorato grazie a OnConflictStrategy.IGNORE.
     * @param category La categoria da inserire.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    /**
     * Trova una categoria dal suo nome.
     * @param name Il nome esatto della categoria.
     * @return L'entità Category o null se non trovata.
     */
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Category?

    /**
     * Cerca categorie il cui nome contiene la stringa di ricerca.
     * @param query La stringa da cercare.
     * @return Una lista di categorie che corrispondono.
     */
    @Query("SELECT * FROM categories WHERE name LIKE '%' || :query || '%' ORDER BY name ASC")
    suspend fun searchByName(query: String): List<Category>

    /**
     * Recupera tutte le categorie nel database, ordinate per nome.
     * @return Una lista di tutte le categorie.
     */
    @Query("SELECT * FROM categories ORDER BY name ASC")
    suspend fun getAll(): List<Category>

    /**
     * Recupera una categoria tramite il suo ID.
     * @param id L'ID univoco della categoria.
     * @return L'entità Category o null se non trovata.
     */
    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Category?

    @Query(
        """
        SELECT c.id AS id,
               c.name AS name,
               COUNT(p.id) AS productCount
        FROM categories c
        LEFT JOIN products p ON p.categoryId = c.id
        WHERE (:query IS NULL OR :query = '' OR c.name LIKE '%' || :query || '%')
        GROUP BY c.id, c.name
        ORDER BY c.name COLLATE NOCASE ASC
        """
    )
    suspend fun getCatalogItems(query: String?): List<CatalogListItem>

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
